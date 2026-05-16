# Use Case 04: Auto-watch and incremental rebuild of the in-memory index

## Summary

Augment the daemon from UC01 so that, while running, it watches the project's classpath roots, source roots, and build/dependency-configuration files (`build.gradle`, `build.gradle.kts`, `settings.gradle*`, `gradle/libs.versions.toml`, `pom.xml`) and automatically rebuilds the in-memory indexes on change — without waiting for a manual `refresh-index`. The watcher is ON by default in daemon mode (suppressible via `--no-watch`) and is built on `java.nio.file.WatchService` only — no new runtime dependency. Where the change is limited to bytecode (`.class` files, JARs, WARs under classpath roots), the rebuild is incremental: only the affected classes' contributions to `CallGraph` / `SourceIndex` / `FieldAccessIndex` / `TestIndex` are recomputed, and the new snapshot is atomically swapped under the existing `AtomicReference` + `ReadWriteLock` scheme so concurrent reads never block. A 1000 ms debounce window coalesces bursts of FS events into one rebuild pass. `.java` changes with no matching `.class` event are logged as `skipped, source-only-no-bytecode-change` and do not mutate the index. When a build/dependency-configuration file changes — i.e., the launch scope is no longer valid — the daemon gracefully self-restarts in-process with its original launch arguments: it drains in-flight requests with a short grace period, closes its TCP and MCP surfaces, discards the index, re-runs the initial scan, re-binds (on a fresh OS-assigned ephemeral port), rewrites the discovery file, restarts the surfaces, and restarts the watcher. If any phase of the self-restart fails (e.g., the new initial scan throws because `build.gradle` is malformed), the daemon logs the failure, releases the discovery file, and exits non-zero — no retry, no zombie state. A new tenth query operation, `watcher-status`, exposes the watcher's current state to CLI and MCP clients and is also the single source of truth for "when was the index last touched", including manual `refresh-index` calls. Every rebuild and self-restart phase emits a structured JSON log line both to stderr and to a rotating log file under `~/.cache/java-class-call-scanning/<project-hash>.log`.

## Acceptance Criteria

1. Daemon gains a watcher subsystem, started **before** the daemon advertises its TCP port or MCP stdio surface and stopped cleanly on `daemon stop`, SIGTERM, idle-shutdown, and immediately before a self-restart cycle.
2. Watcher is ON by default in `daemon start`. New flag `--no-watch` suppresses it entirely. Watcher is OFF for the existing one-shot pipeline.
3. Watcher uses `java.nio.file.WatchService` exclusively — no new runtime dependency. Recursive watching is implemented by registering each subdirectory individually under classpath/source roots and re-registering newly created subdirectories on `ENTRY_CREATE`. macOS users are documented to see degraded latency due to the kqueue-based backend.
4. Watcher registers on: every classpath root passed at `daemon start`; every source root passed at `daemon start`; and the build/dependency configuration files (`build.gradle`, `build.gradle.kts`, `settings.gradle`, `settings.gradle.kts`, `gradle/libs.versions.toml`, `pom.xml`) discovered at or above each source root by nearest-ancestor lookup.
5. Debounce: FS events are coalesced into a single rebuild pass; window configurable via `--watch-debounce-ms <N>` with a default of **1000 ms**.
6. **Incremental class rebuild** — when changes are limited to `.class` files under classpath roots, only the affected classes' contributions are recomputed, a new snapshot is constructed, and the index is atomically swapped via the existing `AtomicReference<Index>` + `ReadWriteLock` discipline. In-flight reads complete against the previous snapshot.
7. **Archive rebuild** — when a JAR or WAR under a classpath root is replaced or modified, every class contributed by that archive is recomputed; classes no longer present in the new archive are removed from the swapped snapshot.
8. **Deletion** — when a `.class` file or archive is deleted, all its prior contributions are removed from the next swapped snapshot. Deletion detection includes a directory-state reconciliation step at the end of each debounce window (FS events do not reliably encode "rename"; we walk the affected directory tree to detect missing entries).
9. **Source-only change** (a `.java` file changed but no corresponding `.class` change in the debounce window): emits a `skipped` log entry with reason `source-only-no-bytecode-change` and does not mutate the index. The daemon does not invoke the build itself.
10. **Build-config change → graceful self-restart**: when a watched build/dependency-configuration file changes, after the debounce window the daemon:
    - Logs a `watch.self_restart` `phase: "starting"` entry with the triggering path.
    - Stops accepting new TCP and MCP requests.
    - Drains in-flight requests with a configurable grace period (`--restart-drain-ms <N>`, default 2000 ms); after the grace period, remaining connections are force-closed.
    - Releases the TCP port, closes the MCP stdio surface, and stops the watcher.
    - Discards the current index.
    - Re-runs the initial scan with the original launch arguments captured at `daemon start` (classpath, src, include/exclude, idle-timeout, foreground, debounce).
    - Re-binds on a fresh OS-assigned ephemeral port; rewrites the discovery file (same `<project-hash>` since canonical classpath + source roots are unchanged) with the new PID/port.
    - Restarts MCP stdio, restarts the watcher, then logs a `watch.self_restart` `phase: "completed"` entry with total elapsed time.
    - **On any restart-phase failure (e.g., the new initial scan throws): logs `watch.self_restart` `phase: "failed"` with the exception, releases the discovery file, exits non-zero. No retry, no fallback to the old snapshot.**
11. **Failure handling on incremental rebuild** — if rebuild throws (e.g., malformed bytecode mid-compile), one retry is attempted after the debounce window. On second failure, the prior snapshot remains active, a `failure` log entry is emitted with the exception class and message, and the daemon does not crash.
12. Each rebuild attempt and each self-restart phase emits exactly one structured JSON line with at minimum these fields:
    - `event` — `"watch.rebuild"` or `"watch.self_restart"`
    - `schema_version` — integer, starts at `1`
    - `trigger.watcher` — `"classpath"` / `"source"` / `"build-config"` / `"manual"` (the last when emitted by a `refresh-index` call updating watcher state)
    - `trigger.paths` — list of absolute paths
    - `trigger.event_count` — coalesced count (1 for manual)
    - `scope.classes` — list of FQNs
    - `scope.archives` — list of JAR/WAR paths
    - `outcome` — `"success"` / `"failure"` / `"skipped"` / `"full-rescan-fallback"` / `"self-restart"`
    - `outcome.reason` — free-text on `skipped` / `failure`
    - `phase` — `"starting"` / `"completed"` / `"failed"` (present only on `watch.self_restart`)
    - `elapsed_ms`
    - `index.methods_before` / `index.methods_after` / `index.edges_before` / `index.edges_after`
    - `timestamp` — ISO-8601 UTC
13. Log destination: **both** stderr (one JSON object per line) **and** a rotating log file at `~/.cache/java-class-call-scanning/<project-hash>.log`. Rotation: max 10 MB per file, 5 rotated files retained (`<project-hash>.log.1` … `<project-hash>.log.5`), oldest pruned on rotation. Configurable via `--log-file <path>`, `--log-max-size-mb <N>`, `--log-max-files <N>`.
14. **New query operation `watcher-status`** added as the **tenth** operation alongside UC01's nine. Exposed via both the CLI client (`watcher-status` subcommand) and as an MCP tool with matching JSON Schema. Returns:

    ```json
    {
      "enabled": true,
      "watched_classpath_roots": ["<abs>", "..."],
      "watched_source_roots": ["<abs>", "..."],
      "watched_build_config_files": ["<abs>", "..."],
      "debounce_ms": 1000,
      "last_rebuild": {
        "timestamp": "<ISO-8601>",
        "trigger_watcher": "classpath" | "source" | "build-config" | "manual" | null,
        "outcome": "success" | "failure" | "skipped" | "full-rescan-fallback" | "self-restart" | null,
        "elapsed_ms": "<int> | null",
        "scope_class_count": "<int>",
        "scope_archive_count": "<int>"
      },
      "last_error": { "class": "<FQN>", "message": "<str>", "timestamp": "<ISO-8601>" } | null,
      "log_file": "<abs>",
      "log_schema_version": 1
    }
    ```

    Returns `{"enabled": false, ...}` with sensible nulls if the daemon was started with `--no-watch`.
15. **`refresh-index` (UC01 §11) is the force-full-rebuild surface for users and LLMs who consider the tree outdated.** No new operation is introduced; the existing one is reaffirmed. Calling `refresh-index` while the watcher is running is explicitly supported — the two paths serialize naturally through the existing `ReadWriteLock` + `AtomicReference` swap. After a successful manual `refresh-index`, the daemon updates `watcher-status.last_rebuild` with `trigger_watcher: "manual"` and clears `last_error`, so `watcher-status` remains a single source of truth for "when was the index last touched."
16. Watcher introduces no new network socket, no external service call, and no telemetry.
17. JUnit 5 tests against the `benchmark` Spring Boot fixture cover:
    - (a) Modifying a `.class` triggers an incremental rebuild whose log lists the expected FQN.
    - (b) Replacing a JAR under classpath roots rebuilds and drops obsolete classes.
    - (c) Deleting a `.class` removes its contributions in the next snapshot.
    - (d) A `.java`-only change produces the `skipped` / `source-only-no-bytecode-change` log.
    - (e) A `build.gradle` change triggers a graceful self-restart that ends in `phase: "completed"`, with the daemon back on a new TCP port and the discovery file updated.
    - (f) A self-restart with a malformed `build.gradle` (the replaced scan throws) ends in `phase: "failed"`, the discovery file is removed, and the daemon exits non-zero.
    - (g) A burst of N events within the debounce window produces exactly one rebuild log.
    - (h) Malformed `.class` mid-compile triggers the retry-then-failure path without crashing the daemon.
    - (i) `watcher-status` returns the expected payload over both TCP and MCP after a rebuild, after a `skipped` event, and after `--no-watch`.
    - (j) Log rotation behaviour: writing past 10 MB rolls to `.log.1` and old entries remain tailable.
    - (k) Calling `refresh-index` while the watcher is mid-debounce or mid-rebuild produces no torn snapshot, no lost watcher event, and updates `watcher-status.last_rebuild` to `trigger_watcher: "manual"`.
18. README / daemon docs cover the watcher's flags (`--no-watch`, `--watch-debounce-ms`, `--restart-drain-ms`, `--log-file`, `--log-max-size-mb`, `--log-max-files`), defaults, log schema (with `schema_version`), the graceful self-restart contract (including that clients must re-read the discovery file because the TCP port changes on restart), the `watcher-status` operation, and the macOS latency caveat.

## Potential Pitfalls & Open Questions

- **Risk** — `java.nio.file.WatchService` does not watch recursively on its own. We re-register on each `ENTRY_CREATE` of a directory; if the build creates many nested package directories in rapid succession before we register them, events inside the new subtree are lost. The directory-reconciliation walk at the end of each debounce window (§8) is the safety net, but adds cost on large trees.
- **Risk** — On macOS, `WatchService` is backed by polling kqueue and adds noticeable latency (multi-second) between an FS write and our event. Acceptable per the chosen no-new-dep approach; documented in §18.
- **Edge case** — A `gradle clean` + recompile may exceed the 1000 ms debounce; we may issue a delete-only rebuild followed by an add rebuild. The directory-reconciliation walk and atomic swap mean readers never see an inconsistent state, but log volume doubles. Users can raise `--watch-debounce-ms`.
- **Edge case** — If a build-config change happens **while** an incremental rebuild is mid-flight, the build-config event is held until the rebuild finishes, then the self-restart sequence runs. Two debounce windows back-to-back, no overlap.
- **Edge case** — On graceful self-restart, the TCP port changes (OS-assigned ephemeral). Clients must re-read the discovery file on connect failure. UC01 §9 already requires this fall-back, so the contract is preserved.
- **Assumption** — In-process self-restart is sufficient — no JVM re-exec needed. Trade-off: a build-config change that introduces a different JVM-level setting (heap, GC tuning) passed on the original `java -jar` command line will NOT take effect on self-restart, since we stay in the same JVM. Acceptable for MVP.
- **Assumption** — Build-config discovery is "nearest-ancestor of each source root". Unusual layouts (e.g., a Gradle subproject's `build.gradle` outside the watched tree) will be missed; documented and tunable later.

## Original Description

Let's work in my java class scanner project. I want to add that, when in daemon mode, we have a "watch" over the files so if a .java fie is modified (or a dependency version) we automatically rebuid the in-memory tree we can run searches over.
This rebuild should be as far as possible incremental, not rebuild eerything.
We should have logs stating which file watch triggered this behaviour and its result and elapsed timeline.

Follow-up: We need to add an extra operation to force the full rebuild of the tree if that function doesn't already exist. For scenarios where an user/LLM considers the tree outdated.

## Clarifications

- Q: Should auto-watch be ON by default when the daemon starts, or opt-in via a flag?
  A: ON by default; `--no-watch` suppresses.
- Q: Which file watcher implementation should we use?
  A: `java.nio.file.WatchService` — no new runtime dependency. macOS latency caveat documented.
- Q: What should happen when a build-config file (`build.gradle`, `libs.versions.toml`, `pom.xml`, ...) changes?
  A: Graceful in-process self-restart with the original launch arguments; on any restart-phase failure, exit non-zero (no retry).
- Q: Where and in what form should the rebuild logs be written?
  A: Both stderr (one JSON object per line) and a rotating log file under `~/.cache/java-class-call-scanning/<project-hash>.log` (10 MB × 5 files).
- Q: Should the watcher monitor source roots (`.java` files) at all, given the tool scans bytecode?
  A: Yes — log `.java` changes as `skipped, source-only-no-bytecode-change`. Provides a breadcrumb for users wondering why the index didn't update.
- Q: Should the daemon expose a new query operation `watcher-status`?
  A: Yes — add it as the 10th operation alongside UC01's nine.
- Q: On self-restart failure, should the daemon retry or exit?
  A: Fail fast — exit non-zero, no retry, no zombie state.
- Q: What should the default debounce window be?
  A: 1000 ms (1 second), configurable via `--watch-debounce-ms`.
- Q: Given UC01's `refresh-index` already provides a manual full rebuild of the original launch scope, how should UC04 treat it?
  A: Keep UC01's `refresh-index` as-is; reaffirm it in UC04 as the force-full-rebuild surface. Add no new operation. Add a test that calls `refresh-index` while the watcher is running to confirm correct serialization.
- Q: Should `refresh-index` update `watcher-status` fields?
  A: Yes — after a manual rebuild, `watcher-status.last_rebuild` reflects it with `trigger_watcher: "manual"`, and `last_error` is cleared. Makes `watcher-status` a single source of truth for "when was the index last touched."

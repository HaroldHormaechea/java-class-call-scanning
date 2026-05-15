# Use Case 01: Daemon + CLI/MCP query API over the call-graph indexes

## Summary

A long-lived Java daemon scoped to a single target project holds the call-graph, source, field-access, and test indexes in memory and exposes nine query operations through two surfaces simultaneously: a CLI client over TCP loopback (ephemeral port, no auth) and an MCP server over stdio built on the official Anthropic MCP Java SDK. The daemon is launched via `daemon start --classpath ... --src ...`, runs an initial scan, writes a discovery file under `~/.cache/java-class-call-scanning/<project-hash>.json` (where the hash is over the canonical classpath + source roots) and stays alive. Every daemon process exposes both surfaces regardless of launch path. Method and field FQNs use JVM-descriptor form (`pkg.Cls#name(Ldesc;)Ret`, `pkg.Cls#name:Ldesc;`); `<init>` and `<clinit>` are queryable by their reserved names. `refresh-index` is strictly a re-scan of the original launch scope (changing classpath or source roots requires `daemon stop` + restart) and uses an `AtomicReference`-backed swap so reads never block under a `ReadWriteLock`. The CLI client emits all JSON (success or error) on stdout and distinguishes by exit code. Caller/callee trees mark truncated nodes with `"truncated": true` plus `"reason": "depth"|"cycle"`. Auto-idle-shutdown is disabled by default and opt-in via `--idle-timeout <minutes>`. The existing one-shot pipeline entry point continues to work for headless CI use without a daemon.

## Acceptance Criteria

1. New `daemon start` subcommand (`--classpath`, `--src`, optional include/exclude, optional `--idle-timeout <minutes>`, `--foreground` flag).
2. Daemon binds to `127.0.0.1` on an OS-assigned ephemeral port; refuses non-loopback binds.
3. Daemon also exposes an MCP stdio server (Anthropic MCP Java SDK) advertising the nine operations as MCP tools with JSON Schema for arguments and results. Both surfaces are active in every daemon process.
4. Discovery file at `~/.cache/java-class-call-scanning/<project-hash>.json`. `<project-hash>` is computed over the normalized, sorted set of canonical classpath roots and source roots.
5. One daemon per project. Re-running `daemon start` for an already-running project fails fast with the existing PID/port, unless `--force` is passed (terminates the existing daemon first).
6. Daemon shutdown paths: `daemon stop --project <id-or-path>` subcommand (reads discovery file, sends shutdown request); `SIGTERM` handled gracefully; opt-in `--idle-timeout <N>` auto-shutdown (no default).
7. `--foreground` flag keeps the daemon attached to the launching terminal; Ctrl-C exits cleanly.
8. CLI client subcommands (`refresh-index`, `find-callers`, `find-callees`, `methods-in-class`, `methods-at-line`, `find-field-readers`, `find-field-writers`, `impact-of-diff`, `tests-for-diff`) locate the daemon via the discovery file, send a JSON request over TCP loopback, and print the JSON response on stdout. Errors are JSON on stdout with `{"error": "<code>", "detail": "<message>"}` plus non-zero exit code.
9. If the discovery file exists but the PID is dead, the CLI client deletes the stale file and exits non-zero with a clear "no daemon running for this project — run `daemon start ...`" message.
10. Method and field FQNs are JVM descriptor-form on both input and output. `<init>` / `<clinit>` queryable by reserved names.
11. `refresh-index` returns `{"status": "ok", "methods": N, "edges": M, "scan_ms": T}` and re-scans **strictly** the original launch scope. Rebuilds use an `AtomicReference<Index>` swap so in-flight reads complete against the old snapshot; the index object is effectively immutable post-build.
12. Concurrency: `ReadWriteLock` (or equivalent snapshot-reference scheme) — many concurrent `find_*` queries; `refresh-index` waits for active readers, then swaps.
13. `find-callers` / `find-callees` accept `method_fqn` + `depth` (default callers=-1, callees=3); -1 means unbounded. Cycle-safe. Truncated nodes appear as `{"method": "...", "truncated": true, "reason": "depth"|"cycle"}` (no `children` key on truncated nodes).
14. `methods-in-class` returns each method as `{name, descriptor, line_start, line_end, is_test}`.
15. `methods-at-line` accepts `source_file` as either an absolute filesystem path or a path relative to a known source root; absolute paths are tried first and mapped back to a source root. Returns every method whose `LineNumberTable` covers the line.
16. `find-field-readers` / `find-field-writers` use the existing `FieldAccessIndex` and distinguish reads from writes.
17. `impact-of-diff` returns JSON byte-identical (modulo whitespace) to the current pipeline's `impactedTrees` output for the same diff and project scope. Locked in by a golden-file regression test.
18. `tests-for-diff` returns flat list `{fqn, type, displayName, root_change}` where `type ∈ {"junit5", "spock"}`.
19. Malformed inputs return the structured error payload over the same channel; daemon stays alive.
20. JUnit 5 tests under `src/test/java/` cover each of the nine operations against the `benchmark` Spring Boot fixture, exercising both the TCP-client path and the in-process MCP-tool path.
21. The existing one-shot pipeline entry point continues to work unchanged.

## Potential Pitfalls & Open Questions

- **Risk** — Anthropic MCP Java SDK adds transitive deps to the fat JAR; license review and size impact assessment fall to the dev-team at implementation time.
- **Edge case** — If the build generates new source roots mid-session, the daemon will scan stale paths until restarted. Acceptable trade-off per "refresh = re-scan original scope" decision; flag for users via daemon docs.
- **Assumption** — Discovery file location follows XDG Base Dir on Linux (`$XDG_CACHE_HOME` then `~/.cache`); macOS and Windows fall back to platform-appropriate cache dirs.

## Original Description

I want to implement some search functions:

refresh_index()
  → { status: "ok", methods: N, edges: M, scan_ms: T }

find_callers(method_fqn: str, depth: int = -1)
  → { tree: {...} }   # tu formato JSON actual de impactedTrees vale

find_callees(method_fqn: str, depth: int = 3)
  → { tree: {...} }

methods_in_class(class_fqn: str)
  → { methods: [{name, descriptor, line_start, line_end, is_test}, ...] }

methods_at_line(source_file: str, line: int)
  → { methods: [method_fqn, ...] }    # puede haber varios si overloads

find_field_readers(field_fqn: str)
  → { methods: [method_fqn, ...] }

find_field_writers(field_fqn: str)
  → { methods: [method_fqn, ...] }

impact_of_diff(diff_text: str)
  → { impactedTrees: [...] }   # tu output JSON actual tal cual

tests_for_diff(diff_text: str)
  → { tests: [{fqn, type, displayName, root_change}, ...] }

These functions shold for now be called from the CLI.

Follow-up: I want to link this to an MCP server: Daemon Java + MCP. The idea would be to launch it pointing to a project, and then be able to run commands against it, either via cli or via mcp.

## Clarifications

- Q: How should the index lifecycle work across these query calls?
  A: Long-lived daemon process, in-memory index, `refresh-index` rebuilds in place.
- Q: How should the CLI client talk to the daemon?
  A: TCP loopback on an OS-assigned ephemeral port (127.0.0.1).
- Q: Process model — how many daemons?
  A: One daemon per project.
- Q: MCP server implementation approach?
  A: Official Anthropic MCP Java SDK.
- Q: Canonical method FQN format (and field FQN by extension)?
  A: JVM descriptor form (`pkg.Cls#name(Ljava/lang/String;)V`, `pkg.Cls#name:Ljava/lang/String;`).
- Q: Where does the MCP server live relative to the daemon?
  A: MCP over stdio, spawned alongside the TCP daemon — both surfaces in every process.
- Q: How should `methods_at_line` resolve `source_file`?
  A: Accept both absolute and source-root-relative; prefer absolute when given.
- Q: How does the daemon stop?
  A: `daemon stop` subcommand + opt-in idle-timeout + `--foreground` mode.
- Q: Should the TCP loopback socket require an auth token?
  A: No auth — loopback only, trust local processes.
- Q: MCP stdio mode launches a daemon process — does that process also start the TCP server?
  A: Yes — every daemon process exposes both surfaces.
- Q: Concurrency — how to handle simultaneous requests?
  A: Concurrent reads, exclusive writes (ReadWriteLock around the index reference).
- Q: Where do CLI client errors go?
  A: All JSON on stdout (success and error), exit code distinguishes.
- Q: How should caller/callee trees represent truncated nodes?
  A: Include the node with `"truncated": true` and `"reason": "depth"|"cycle"`.
- Q: How should the daemon derive its `<project-hash>` for the discovery filename?
  A: Hash canonical classpath roots + source roots (normalized, sorted).
- Q: Can `refresh_index` change the daemon's project scope?
  A: No — strictly a re-scan of the original launch scope.
- Q: What's the default idle-timeout for auto-shutdown?
  A: Disabled by default; opt-in via `--idle-timeout <N>`.
- Q: When the CLI finds a stale discovery file (dead PID), what should happen?
  A: Delete the stale file and error out asking the user to run `daemon start`.

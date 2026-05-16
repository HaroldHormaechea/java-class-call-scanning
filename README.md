# java-class-call-scanning

Bytecode-level Java call-graph and diff-impact analyzer. Reads compiled `.class` files, JARs, and WARs (including nested `WEB-INF/lib/*.jar`) with ASM, builds a method-level call graph plus a field read/write index, maps a Git unified diff onto the methods it touches via the bytecode `LineNumberTable`, and walks the graph backward to find the transitive callers — flagging the ones that are JUnit 5 or Spock test methods.

## What it does

The tool is the same fat JAR in three usage modes:

1. **One-shot CLI** — pass `--compiled <paths>` and either `--diff <file>` / `--diff-stdin` (impact analysis) or `--print-hierarchy <ref>` (interactive exploration). Reads bytecode, prints results, exits.
2. **Daemon — TCP loopback CLI** — `daemon start` launches a long-lived process scoped to a project (classpath + sources). The same JAR run with a query subcommand (`find-callers`, `tests-for-diff`, …) talks to the daemon over a per-user loopback port and prints the JSON response. The indexes are built once and reused across queries.
3. **Daemon — MCP stdio server** — the daemon also speaks MCP on stdio, advertising nine tools (`refresh-index`, `find-callers`, `find-callees`, `methods-in-class`, `methods-at-line`, `find-field-readers`, `find-field-writers`, `impact-of-diff`, `tests-for-diff`). An MCP-aware client (e.g. an LLM agent) drives the same operations as tools.

The primary use case is selective test execution in CI: feed a pull-request diff to `tests-for-diff` (or `--diff` in one-shot mode) and run only the test classes/methods the tool reports as impacted. Secondary use cases are interactive exploration via `--print-hierarchy` and programmatic call-graph queries from an MCP client.

The tool reads only — it never executes the analyzed code, never instruments it, and never makes network calls.

## Requirements

- JDK 21+ (any LTS distribution: Temurin, Zulu, Liberica, Oracle, etc.).
- The Gradle wrapper (`./gradlew`) is checked into the repo — no separate Gradle install needed.
- ASM 9.8 and Gson 2.11.0 are bundled in the fat JAR; no other runtime dependencies.

## Building

```bash
./gradlew build        # compile + package → build/libs/java-class-call-scanning.jar (fat JAR)
./gradlew test         # run the test suite
./gradlew clean build  # full rebuild
```

The tool's compiled classes land in `build/classes/java/main/`.
Test fixtures (including the benchmark app) land in `build/classes/java/test/`.

## One-shot CLI

After `./gradlew build`, the self-contained fat JAR is at `build/libs/java-class-call-scanning.jar`. Releases attach the same artifact under that name. Copy it anywhere and run with `java -jar`:

```bash
java -jar java-class-call-scanning.jar --compiled <dir|jar|war> [--compiled <dir|jar|war> ...] \
                                       [--sources <srcDir>] \
                                       [--diff <diffFile> | --diff-stdin | --print-hierarchy <ref>] \
                                       [--export-format console|json]
```

`--compiled` can be specified multiple times to scan several directories/archives into a single graph. Typical: scan both production and test classes together so the diff's transitive callers include test methods.

```bash
java -jar java-class-call-scanning.jar \
  --compiled target/classes \
  --compiled target/test-classes \
  --sources src/main/java \
  --diff changes.patch \
  --export-format json
```

In-repo equivalent via Gradle:

```bash
./gradlew run --args="--compiled <dir|jar|war> [--compiled <dir|jar|war> ...] \
                      [--sources <srcDir>] \
                      [--diff <diffFile> | --diff-stdin | --print-hierarchy <ref>] \
                      [--export-format console|json]"
```

### Default — dump all edges

With no `--diff` / `--diff-stdin` / `--print-hierarchy` flag, the tool prints method count, edge count, source index size, then every `caller → callee` edge.

```bash
# Scan the bundled benchmark fixtures (compiled to test output)
./gradlew run --args="--compiled build/classes/java/test"

# Scan your own project's compiled output
./gradlew run --args="--compiled /path/to/your/project/build/classes/java/main"

# Scan a WAR file (incl. nested WEB-INF/lib/*.jar)
./gradlew run --args="--compiled /path/to/app.war"

# Scan a JAR
./gradlew run --args="--compiled /path/to/library.jar"
```

### `--diff <diffFile>` / `--diff-stdin` — impact analysis

```bash
./gradlew run --args="--compiled build/classes/java/test --diff path/to/changes.diff"

# With --sources for exact path matching (eliminates same-simple-name ambiguity)
./gradlew run --args="--compiled build/classes/java/test --sources src/test/java --diff path/to/changes.diff"

# From stdin
git diff HEAD~1 | ./gradlew -q run --args="--compiled build/classes/java/test --sources src/main/java --diff-stdin"
```

#### Console output (default)

```
=== Directly Changed Methods (N) ===
org.example.Service#doWork
...

=== Transitive Callers (M) ===
org.example.Controller#handleRequest
...
```

#### JSON output (`--export-format json`)

`impactedTrees` — full caller hierarchy trees rooted at each directly changed method, unlimited depth, cycle-safe:

```json
{
  "impactedTrees": [
    {
      "className": "org.example.Service",
      "methodName": "doWork",
      "fqn": "org.example.Service#doWork",
      "callers": [
        {
          "className": "org.example.Controller",
          "methodName": "handleRequest",
          "fqn": "org.example.Controller#handleRequest",
          "callers": [
            {
              "className": "org.example.ControllerTest",
              "methodName": "testHandleRequest",
              "fqn": "org.example.ControllerTest#testHandleRequest",
              "isTest": true,
              "testType": "JUnit5",
              "testDisplayName": "testHandleRequest"
            }
          ]
        }
      ]
    }
  ]
}
```

Nodes that form a cycle are marked `"cycle": true` and have no children.

#### Windows / PowerShell notes

PowerShell's `>` operator writes files as UTF-16LE, which differs from Git's UTF-8 default. The diff parser detects UTF-8, UTF-16LE/BE, and BOM-prefixed files, so the following all work:

```powershell
# Pipe directly (recommended — avoids encoding issues entirely)
git diff HEAD~1..HEAD | java -jar java-class-call-scanning.jar --compiled build\classes\java\main --sources src\main\java --diff-stdin

# Save to file with PowerShell (UTF-16LE is detected)
git diff HEAD~1..HEAD > changes.patch
java -jar java-class-call-scanning.jar --compiled build\classes\java\main --diff changes.patch

# Force UTF-8 output
git diff HEAD~1..HEAD | Out-File -Encoding utf8 changes.patch

# Use cmd for redirection (writes plain ASCII/UTF-8)
cmd /c "git diff HEAD~1..HEAD > changes.patch"
```

#### Multi-module projects (Gradle/Maven)

Point `--compiled` at a specific module or pass multiple paths into one graph:

```powershell
# Compile a single module
cd C:\path\to\spring-framework
.\gradlew :spring-context:compileJava

# Scan one module
git diff HEAD~10..HEAD | java -jar java-class-call-scanning.jar --compiled spring-context\build\classes\java\main --sources spring-context\src\main\java --diff-stdin

# Scan multiple modules together
java -jar java-class-call-scanning.jar --compiled spring-core\build\classes\java\main --compiled spring-context\build\classes\java\main --diff changes.patch
```

### `--print-hierarchy <ref>` — explore the graph

`<ref>` is a dotted fully-qualified name, optionally with a `#memberName` suffix.

#### Class mode — no `#`

```bash
./gradlew run --args="--compiled build/classes/java/test --print-hierarchy com.hhg.benchmark.service.OrderService"
```

```
=== Class: OrderService ===
Methods (17):
  <init>                         1 callee, called by 0
  cancelOrder                    15 callees, called by 1
  createOrder                    32 callees, called by 1
  findById                       2 callees, called by 3
  ...
```

#### Method mode — `#methodName`

```bash
./gradlew run --args="--compiled build/classes/java/test --print-hierarchy com.hhg.benchmark.service.OrderService#createOrder"
```

Prints a callee tree (what this method calls) and a caller tree (what calls this method), both up to depth 5. If the name matches multiple overloads, each is printed in turn.

```
=== Method: OrderService#createOrder ===

Callee Tree (calls):
com.hhg.benchmark.service.OrderService#createOrder
+-- com.hhg.benchmark.service.CustomerService#findById
|   \-- com.hhg.benchmark.repository.CustomerRepository#findById
\-- ...

Caller Tree (called by):
com.hhg.benchmark.service.OrderService#createOrder
\-- com.hhg.benchmark.controller.OrderController#createOrder
```

#### Field mode — `#fieldName`

```bash
./gradlew run --args="--compiled build/classes/java/test --print-hierarchy com.hhg.benchmark.entity.Product#price"
```

```
=== Field: Product#price ===

Read by (1):
  Product#getPrice

Written by (2):
  Product#<init>
  Product#setPrice
```

If the name resolves as both a method and a field, both sections are printed.

## Daemon mode

The daemon scans the configured classpath + sources once, holds the indexes in memory, and exposes them through two surfaces simultaneously: a TCP loopback CLI (same JAR, query subcommands) and an MCP stdio server.

### Starting and stopping

```bash
java -jar java-class-call-scanning.jar daemon start \
    --classpath <dir|jar|war> [--classpath ...] \
    [--src <srcDir>] [--src ...] \
    [--include <glob>] [--exclude <glob>] \
    [--idle-timeout <minutes>] \
    [--foreground] \
    [--no-watch] \
    [--watch-debounce-ms <N>] \
    [--restart-drain-ms <N>] \
    [--log-file <path>] \
    [--log-max-size-mb <N>] \
    [--log-max-files <N>]

java -jar java-class-call-scanning.jar daemon stop --project <id-or-path>
```

`--classpath` is required and repeatable. `--src` is optional and repeatable. `--include` / `--exclude` filter the scan. `--idle-timeout <minutes>` (≥ 1) triggers auto-shutdown after that many idle minutes; without it, the daemon runs until stopped. `--foreground` keeps the daemon attached to the launching process (required by MCP clients that spawn the daemon as a subprocess).

The `--no-watch`, `--watch-debounce-ms`, `--restart-drain-ms`, `--log-file`, `--log-max-size-mb`, and `--log-max-files` flags control the auto-watch + incremental-rebuild subsystem — see [Auto-watch + incremental rebuild](#auto-watch--incremental-rebuild) below.

On startup the daemon writes a **discovery file** containing the PID and the loopback port:

- Linux / other Unix: `$XDG_CACHE_HOME/java-class-call-scanning/<project-hash>.json`, falling back to `~/.cache/java-class-call-scanning/<project-hash>.json` when `XDG_CACHE_HOME` is unset.
- macOS: `~/Library/Caches/java-class-call-scanning/<project-hash>.json`.
- Windows: `%LOCALAPPDATA%\java-class-call-scanning\<project-hash>.json`.

The per-project hash is derived from the canonical classpath + source roots, so two daemons launched from different scopes do not collide.

`daemon stop` reads the discovery file and sends a clean shutdown request. `SIGTERM` is also handled gracefully.

### TCP loopback CLI — query subcommands

Once a daemon is running, query it with the same JAR. Each query subcommand takes `--project <id-or-path>` (either the per-project hash from the discovery filename, or any path that resolves to the same scope as the daemon's classpath) and operation-specific flags.

| Subcommand              | Flags                                                  | Purpose                                                                                 |
|-------------------------|--------------------------------------------------------|-----------------------------------------------------------------------------------------|
| `refresh-index`         | `--project`                                            | Re-scan the daemon's original launch scope and atomically swap the index.               |
| `find-callers`          | `--project`, `--method`, `[--depth]`                   | BFS over the call graph from a method FQN, returning callers (default depth unbounded). |
| `find-callees`          | `--project`, `--method`, `[--depth]`                   | BFS over the call graph from a method FQN, returning callees (default depth 3).         |
| `methods-in-class`      | `--project`, `--class`                                 | List methods declared on a dotted class FQN.                                            |
| `methods-at-line`       | `--project`, `--source-file`, `--line`                 | Methods whose `LineNumberTable` covers a given `(source_file, line)`.                   |
| `find-field-readers`    | `--project`, `--field`                                 | Methods that READ a given field FQN (JVM-descriptor form).                              |
| `find-field-writers`    | `--project`, `--field`                                 | Methods that WRITE a given field FQN (JVM-descriptor form).                             |
| `impact-of-diff`        | `--project`, `--diff <file>` \| `--diff-stdin`         | Parse a unified diff and emit the `impactedTrees` payload.                              |
| `tests-for-diff`        | `--project`, `--diff <file>` \| `--diff-stdin`         | Flat list of impacted tests `{fqn, type, displayName, root_change}` for a unified diff. |
| `watcher-status`        | `--project`                                            | Returns the current auto-watch state and the single source of truth for "when was the index last touched". |

**FQN format:**

- Method FQNs use JVM-descriptor form: `pkg.Cls#name(Ldesc;)Ret` (e.g. `org.example.Foo#bar(Ljava/lang/String;)V`).
- Field FQNs use: `pkg.Cls#name:Ldesc;` (e.g. `org.example.Foo#count:I`).
- `<init>` and `<clinit>` are queryable by their reserved names.

**Examples:**

```bash
# Start a daemon scoped to a project
java -jar java-class-call-scanning.jar daemon start \
    --classpath build/classes/java/main \
    --classpath build/classes/java/test \
    --src src/main/java --src src/test/java

# Find callers of a specific method
java -jar java-class-call-scanning.jar find-callers \
    --project /absolute/path/to/project \
    --method 'org.example.Service#doWork()V'

# Tests impacted by the current diff (piped)
git diff main...HEAD | java -jar java-class-call-scanning.jar tests-for-diff \
    --project /absolute/path/to/project --diff-stdin

# Stop the daemon
java -jar java-class-call-scanning.jar daemon stop --project /absolute/path/to/project
```

All query subcommands return JSON on stdout. Errors return `{"error": "...", "kind": "<bad-request|not-found|...>"}` with a non-zero exit code.

### MCP stdio server

The daemon also speaks MCP on its stdio. The server identifies itself as `java-class-call-scanning` and advertises the ten tools listed above (same names, same payload shapes).

To connect: configure an MCP-aware client (e.g. an LLM agent) to launch the daemon as a subprocess with `--foreground`, so the process stays attached to the client for the duration of the session. Most MCP client configs accept a command + args; point it at the fat JAR with `daemon start --foreground --classpath ... --src ...` and the ten tools become callable through the client's standard tool-use interface.

### Auto-watch + incremental rebuild

When `daemon start` runs (without `--no-watch`), the daemon watches its classpath roots, source roots, and the nearest-ancestor build/dependency-configuration files (`build.gradle`, `build.gradle.kts`, `settings.gradle`, `settings.gradle.kts`, `gradle/libs.versions.toml`, `pom.xml`). FS events are coalesced into a configurable debounce window and applied incrementally — only the classes whose contributing files changed are re-scanned, then merged into a draft snapshot and swapped under the existing `AtomicReference<IndexSnapshot>` discipline. Concurrent reads (TCP queries and MCP tool calls) never block during a rebuild.

The watcher is built on `java.nio.file.WatchService` only — no new runtime dependency. On macOS, `WatchService` is backed by polling kqueue and adds noticeable latency (multi-second) between an FS write and the rebuild trigger. This is the documented trade-off for the no-new-dep approach.

**Flags (all optional, all on `daemon start`):**

| Flag | Default | Description |
|---|---|---|
| `--no-watch` | (watcher on) | Disable the watcher entirely. `watcher-status` then returns `{"enabled": false, ...}`. |
| `--watch-debounce-ms <N>` | `1000` | Debounce window in milliseconds. A burst of N FS events within this window produces exactly one rebuild log. |
| `--restart-drain-ms <N>` | `2000` | Grace period for in-flight TCP requests during a graceful self-restart; after the deadline, remaining handlers are force-closed. |
| `--log-file <path>` | `<cache-dir>/<project-hash>.log` | Override the structured rebuild log path. |
| `--log-max-size-mb <N>` | `10` | Per-file rotation size threshold. |
| `--log-max-files <N>` | `5` | Number of rotated files retained (`.log.1` … `.log.N`); the oldest is pruned. |

**Event classification & precedence (per debounce window):**

1. **Build-config file changed** → graceful in-process self-restart (see below).
2. **`.class` or `.jar`/`.war` under a classpath root changed** → incremental rebuild.
3. **`.java` only changed** (no matching bytecode event in the same window) → one `skipped` log line with `outcome.reason: "source-only-no-bytecode-change"`; the index is **not** mutated. The daemon does not invoke the build itself; this is a breadcrumb for users wondering why their query result didn't update.
4. **Empty / unrecognised** → no-op.

A `.java` save that the IDE auto-compiles into a `.class` within the same window produces **one** rebuild log (case 2), never a skipped line.

**Incremental-rebuild log format** — one JSON object per line, written to **both** stderr (jsonl) and the rotating log file. Schema:

```json
{
  "event": "watch.rebuild",
  "schema_version": 1,
  "trigger.watcher": "classpath",
  "trigger.paths": ["/abs/.../Foo.class"],
  "trigger.event_count": 3,
  "scope.classes": ["com.acme.Foo"],
  "scope.archives": [],
  "outcome": "success",
  "elapsed_ms": 42,
  "index.methods_before": 1234,
  "index.methods_after": 1240,
  "index.edges_before": 5678,
  "index.edges_after": 5701,
  "timestamp": "2026-05-16T09:03:41.448Z"
}
```

On `outcome` ∈ {`failure`, `skipped`}, an extra `"outcome.reason"` key carries the free-text. `trigger.watcher` values: `classpath` / `source` / `build-config` / `manual` (the last is emitted by `refresh-index`).

**Failure handling.** If an incremental rebuild throws (e.g., malformed bytecode caught mid-compile), exactly one retry is attempted on the same debounce scheduler. If the retry fails, a `failure` log line is emitted with `outcome.reason: "<exception class>: <message>"`; the prior snapshot remains active; the daemon does not crash.

**Edges that don't get cleaned up.** When a class `X` is re-scanned, edges where `X` is the *caller* are dropped and replaced. Edges where `X` is only the *callee* (incoming edges from classes not currently being re-scanned) are retained — this is the standard incremental-build trade-off and produces transient "ghost" edges if a callee method is renamed/removed. Users who care can run `refresh-index` for a full rescan.

**Build-config change → graceful in-process self-restart.** When a watched build/dependency-configuration file changes, after the debounce window the daemon:

1. Emits `watch.self_restart phase: "starting"` with the triggering path.
2. Stops accepting new TCP connections; drains in-flight TCP requests for up to `--restart-drain-ms` (default 2000 ms); force-closes anything remaining.
3. Stops the watcher; removes the discovery file. The project's `FileLock` stays held (still one daemon per project).
4. Re-runs the initial scan against the original launch scope.
5. Re-binds TCP on a **fresh OS-assigned ephemeral port** and rewrites the discovery file (same `<project-hash>` since the canonical classpath + source roots are unchanged) with the new PID/port and **the restart wall-clock time** in `started_at_epoch_millis`.
6. Restarts the watcher.
7. Emits `watch.self_restart phase: "completed"` with total `elapsed_ms`.

**The MCP stdio surface is preserved across self-restart.** Stdio MCP is a single-process contract: closing `System.in`/`System.out` signals end-of-process to the client. Instead, the daemon holds a single `McpSyncServer` for the lifetime of the JVM and atomically swaps the underlying `Operations` reference (`setOperations(...)`). The MCP client's session, tool list, and JSON schemas are identical before and after the restart; no protocol renegotiation occurs.

**TCP clients must re-read the discovery file on connect failure**, since the TCP port changes on restart. UC01's contract already required this fall-back; it continues to apply.

**On any self-restart failure** (e.g., the new initial scan throws because `build.gradle` is malformed), the daemon emits `watch.self_restart phase: "failed"` with `outcome.reason: "<exception class>: <message>"`, releases the project lock, and exits non-zero. **No retry, no zombie state, no fall-back to the prior snapshot.**

**`watcher-status` payload (UC04 AC #14):**

```json
{
  "enabled": true,
  "watched_classpath_roots": ["<abs>", "..."],
  "watched_source_roots": ["<abs>", "..."],
  "watched_build_config_files": ["<abs>", "..."],
  "debounce_ms": 1000,
  "last_rebuild": {
    "timestamp": "2026-05-16T09:03:41.448Z",
    "trigger_watcher": "classpath",
    "outcome": "success",
    "elapsed_ms": 42,
    "scope_class_count": 1,
    "scope_archive_count": 0
  },
  "last_error": null,
  "log_file": "/abs/path/to/<hash>.log",
  "log_schema_version": 1
}
```

A successful rebuild sets `last_error = null` (`refresh-index` does the same), so `watcher-status` is the single source of truth for "when was the index last touched". The structured log line shape (flat sibling keys with literal `.`) and the status-payload shape (nested objects with snake_case inner keys) are deliberately different — the former is jsonl-tail-friendly, the latter is API-typed-client-friendly.

**Stderr capture file.** When the daemon is launched in background mode, its child JVM's stderr is captured to `<cache-dir>/<project-hash>.stderr.log` (renamed from the pre-UC04 `<project-hash>.log` to free the `.log` slot for the structured rotating rebuild log).

## Project layout

```
src/
├── main/java/com/hhg/callgraph/
│   ├── BuildVersion.java           # version constant embedded at build time
│   ├── CallGraphBuilder.java       # one-shot CLI entry point
│   ├── cli/                        # daemon + query subcommand dispatch
│   │   ├── DaemonCli.java
│   │   └── SubcommandParser.java
│   ├── model/                      # CallGraph, MethodReference, FieldReference, SourceIndex, TestIndex, ...
│   ├── scanner/                    # ClassFileScanner, ScanResult
│   │   └── test/                   # JUnit5TestDetector, SpockTestDetector, TestMethodDetector, TestSelector
│   ├── diff/                       # GitDiffParser, ImpactAnalyzer, ImpactResult, DiffEntry
│   ├── output/                     # CallTreePrinter (console + JSON)
│   └── daemon/                     # long-lived per-project process
│       ├── Daemon.java             # lifecycle, scope hashing, idle watchdog wiring
│       ├── Discovery.java          # per-OS cache dir + discovery-file read/write
│       ├── ScopeConfig.java        # canonical classpath + sources fingerprint
│       ├── IndexSnapshot.java      # atomic swap of CallGraph/SourceIndex/etc.
│       ├── RebuildCoordinator.java # refresh-index orchestration
│       ├── ScanMeta.java
│       ├── Daemonization.java      # background-launch helper
│       ├── IdleWatchdog.java
│       ├── Fqn.java                # FQN parsing for method/field references
│       ├── op/                     # Operations (one method per query), OperationResult, TestTypeMapping
│       ├── tcp/                    # TcpServer, ConnectionHandler, JsonProtocol
│       └── mcp/McpStdioServer.java # MCP server exposing the nine operations as tools
└── test/java/com/hhg/
    ├── callgraph/                  # unit & integration tests for the tool itself
    ├── main/targets/               # minimal 3-class call-chain fixture (TargetClass1/2/3)
    └── benchmark/                  # realistic Spring Boot fixture (10 entities/services/controllers)
```

### Key classes

| Class | Package | Role |
|-------|---------|------|
| `MethodReference` | `model` | Immutable method identity (className + methodName + descriptor) |
| `FieldReference` | `model` | Immutable field identity (className + fieldName + descriptor) |
| `CallGraph` | `model` | Directed graph, dual adjacency maps, cycle-safe BFS traversal |
| `FieldAccessIndex` | `model` | Bidirectional read/write index keyed by `FieldReference` |
| `SourceLocation` | `model` | File + line range from bytecode `LineNumberTable` |
| `SourceIndex` | `model` | `MethodReference → SourceLocation`, with `findMethodsAt()` |
| `TestIndex` | `model` | `MethodReference → TestDescriptor`, tracks which methods are tests |
| `TestDescriptor` | `model` | Record: method + testType + displayName |
| `ScanResult` | `scanner` | Record: `(CallGraph, SourceIndex, FieldAccessIndex, TestIndex)` |
| `ClassFileScanner` | `scanner` | Scans directories, JARs, and WARs (incl. nested `WEB-INF/lib/*.jar`); populates all four indexes |
| `JUnit5TestDetector` | `scanner.test` | Detects `@Test`, `@ParameterizedTest`, `@TestFactory`, `@RepeatedTest`, `@TestTemplate` |
| `SpockTestDetector` | `scanner.test` | Detects `@FeatureMetadata` (Spock framework) |
| `GitDiffParser` | `diff` | Parses unified diff → `List<DiffEntry>`. Handles UTF-8, UTF-16LE/BE, and BOM-prefixed files |
| `ImpactAnalyzer` | `diff` | Produces `ImpactResult` from `ScanResult` + diffs; accepts optional sources root |
| `ImpactResult` | `diff` | `directlyChanged` + `transitiveCallers` (disjoint sets) |
| `CallTreePrinter` | `output` | Console pipe-tree printer + JSON tree builder (callee/caller/impact modes), cycle-safe with `isTest` annotation |
| `CallGraphBuilder` | (root) | One-shot CLI entry point; delegates to `DaemonCli` when the first arg is a daemon subcommand token |
| `DaemonCli` | `cli` | Dispatches `daemon start` / `daemon stop` and all nine query subcommands |
| `Daemon` | `daemon` | In-process lifecycle: scan, index-snapshot swap, TCP + MCP server startup, idle shutdown |
| `Discovery` | `daemon` | Per-OS cache directory resolution and discovery-file read/write |
| `Operations` | `daemon.op` | The nine query operations, each producing a JSON-serializable `OperationResult` |
| `TcpServer` / `ConnectionHandler` / `JsonProtocol` | `daemon.tcp` | Loopback TCP transport for the query subcommands |
| `McpStdioServer` | `daemon.mcp` | MCP stdio transport exposing the nine operations as tools |

### Runtime dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| ASM | 9.8 | Bytecode reading (`ClassReader`, `ClassNode`, `MethodNode`, `MethodInsnNode`) |
| Gson | 2.11.0 | JSON serialization with pretty-printing |
| MCP Java SDK | (bundled) | MCP server primitives for the stdio surface |

## Known limitations

- **Reflection-based calls are not tracked.** Dynamic dispatch via `Method.invoke()`, `MethodHandle.invokeExact()`, proxy classes, and similar runtime-resolved targets do not appear in the call graph.
- **No virtual-dispatch resolution.** Calls are recorded at the declared call site only — there is no Class Hierarchy Analysis (CHA), Rapid Type Analysis (RTA), or points-to analysis to enumerate concrete implementations a virtual call could reach. `interface.method()` records an edge to the interface method, not to every concrete override.
- **No source-level (AST) analysis.** The tool reads bytecode only. Anything erased by `javac` (lambda capture details, generic type parameters, source-only annotations) is unavailable.
- **Test detection covers JUnit 5 and Spock only.** JUnit 4 (`@org.junit.Test`), TestNG, Cucumber, and other frameworks are not currently flagged as tests.
- **Single-threaded scan and analysis.** ASM visits and graph walks run on the launching thread. Large multi-module codebases (e.g. Spring Framework-scale) scan in seconds-to-low-minutes on a developer laptop, but there is no parallelism inside the scanner today.

## License

Apache License 2.0 — see [`LICENSE`](LICENSE).

# Java Call Graph Scanner

A bytecode analysis tool that builds a method-level call graph and field access index from compiled Java `.class` files. Its primary goal is **selective test execution**: given a Git diff, identify which methods changed, trace all transitive callers, and determine which tests need to run.

---

## Goal

1. Scan compiled `.class` files to build a method-level call hierarchy and a field read/write index
2. Parse a Git diff to identify which source lines changed
3. Map changed lines back to method bodies using bytecode debug info
4. Walk the call graph backward to find every caller that is transitively affected
5. *(Future)* Map the affected set to test classes to produce a minimal test run list

---

## Current capabilities

### Call graph

Every method invocation (`INVOKEVIRTUAL`, `INVOKESPECIAL`, `INVOKESTATIC`, `INVOKEINTERFACE`) is recorded as a directed edge `caller → callee`. The graph supports:

- Direct callee/caller lookup
- Full transitive traversal in both directions (cycle-safe BFS)

### Field access index

Every field access instruction is recorded as a read (`GETFIELD`/`GETSTATIC`) or a write (`PUTFIELD`/`PUTSTATIC`), indexed by the accessing method. You can look up all readers, all writers, or both for any field.

### Source location metadata

Bytecode debug information (`LineNumberTable`) is used to record the line range of every method body. This bridges Git diff line numbers to `MethodReference` keys so impact analysis works without parsing source files.

### Git diff impact analysis

Given a unified diff (file or stdin), the tool:

1. Parses added/changed lines per file
2. Finds methods whose line range contains any changed line
3. Collects all transitive callers of those methods
4. Returns two disjoint sets: **directly changed** and **transitive callers**

### Interactive hierarchy explorer (`--print-hierarchy`)

A debug/validation mode that lets you inspect the call graph and field index interactively.

---

## Building

Requires Java 21+ and Gradle (wrapper included).

```bash
./gradlew build        # compile + package → build/libs/JavaClassCallScanning.jar
./gradlew test         # run the test suite
./gradlew clean build  # full rebuild
```

The tool's compiled classes land in `build/classes/java/main/`.
Test fixtures (including the benchmark app) land in `build/classes/java/test/`.

---

## Running

### Using the packaged JAR (recommended)

After `./gradlew build`, the self-contained fat JAR is at `build/libs/JavaClassCallScanning.jar`.
Copy it anywhere and run with `java -jar`:

```bash
java -jar JavaClassCallScanning.jar --compiled <dir|jar|war> [--sources <srcDir>]
                                    [--diff <diffFile> | --diff-stdin | --print-hierarchy <ref>]
```

Example:

```bash
java -jar JavaClassCallScanning.jar --compiled /path/to/your/project/build/classes/java/main
java -jar JavaClassCallScanning.jar --compiled /path/to/app.war --print-hierarchy com.example.OrderService#createOrder
java -jar JavaClassCallScanning.jar --compiled /path/to/app.war --sources src/main/java --diff-stdin < changes.diff
```

### Using Gradle (development / in-repo)

All flags are named. The only required flag is `--compiled`, which accepts a directory, JAR, or WAR.

```
./gradlew run --args="--compiled <dir|jar|war> [--sources <srcDir>]
                      [--diff <diffFile> | --diff-stdin | --print-hierarchy <ref>]"
```

### Default — dump all edges

```bash
# Scan the bundled benchmark fixtures (compiled to test output)
./gradlew run --args="--compiled build/classes/java/test"

# Scan your own project's compiled output
./gradlew run --args="--compiled /path/to/your/project/build/classes/java/main"

# Scan a WAR file
./gradlew run --args="--compiled /path/to/app.war"

# Scan a JAR
./gradlew run --args="--compiled /path/to/library.jar"
```

Prints method count, edge count, source index size, then every `caller → callee` edge.

### `--diff <diffFile>` — impact analysis from a file

```bash
./gradlew run --args="--compiled build/classes/java/test --diff path/to/changes.diff"

# With --sources for exact path matching (eliminates same-simple-name ambiguity)
./gradlew run --args="--compiled build/classes/java/test --sources src/test/java --diff path/to/changes.diff"
```

### `--diff-stdin` — impact analysis from stdin

```bash
git diff HEAD~1 | ./gradlew -q run --args="--compiled build/classes/java/test --sources src/main/java --diff-stdin"
```

Both diff modes output:

```
=== Directly Changed Methods (N) ===
com/example/Service#doWork (...)V
...

=== Transitive Callers (M) ===
com/example/Controller#handleRequest (...)V
...
```

### `--print-hierarchy <ref>` — explore the graph interactively

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
com/hhg/benchmark/service/OrderService#createOrder (...)...
  com/hhg/benchmark/service/CustomerService#findById ...
    com/hhg/benchmark/repository/CustomerRepository#findById ...
  ...

Caller Tree (called by):
com/hhg/benchmark/service/OrderService#createOrder (...)...
  com/hhg/benchmark/controller/OrderController#createOrder ...
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

---

## Project layout

```
src/
├── main/java/com/hhg/callgraph/   ← the tool itself
│   ├── model/                     # CallGraph, MethodReference, FieldReference, ...
│   ├── scanner/                   # ClassFileScanner, ScanResult
│   ├── diff/                      # GitDiffParser, ImpactAnalyzer, ImpactResult
│   ├── output/                    # CallTreePrinter
│   └── CallGraphBuilder.java      # main entry point + CLI
└── test/java/com/hhg/
    ├── callgraph/                 ← unit & integration tests
    ├── main/targets/              ← minimal 3-class call-chain fixture (TargetClass1/2/3)
    └── benchmark/                 ← realistic Spring Boot fixture (10 entities/services/controllers)
```

### Tool classes (`com.hhg.callgraph`)

| Class | Package | Role |
|-------|---------|------|
| `MethodReference` | `model` | Immutable method identity (className + methodName + descriptor) |
| `FieldReference` | `model` | Immutable field identity (className + fieldName + descriptor) |
| `CallGraph` | `model` | Directed graph, dual adjacency maps, cycle-safe BFS traversal |
| `FieldAccessIndex` | `model` | Bidirectional read/write index keyed by `FieldReference` |
| `SourceLocation` | `model` | File + line range from bytecode `LineNumberTable` |
| `SourceIndex` | `model` | `MethodReference → SourceLocation`, with `findMethodsAt()` |
| `ScanResult` | `scanner` | Record: `(CallGraph, SourceIndex, FieldAccessIndex)` |
| `ClassFileScanner` | `scanner` | Scans directories, JARs, and WARs (incl. nested `WEB-INF/lib/*.jar`); populates all three indexes |
| `GitDiffParser` | `diff` | Parses unified diff → `List<DiffEntry>` |
| `ImpactAnalyzer` | `diff` | Produces `ImpactResult` from `ScanResult` + diffs; accepts optional sources root for exact diff path matching |
| `ImpactResult` | `diff` | `directlyChanged` + `transitiveCallers` (disjoint sets) |
| `CallTreePrinter` | `output` | Recursive tree printer with cycle guard |
| `CallGraphBuilder` | (root) | Main entry point + CLI dispatch |

---

## Out of scope

- **Reflection-based calls** — dynamic dispatch via `Method.invoke()` is not tracked
- **Runtime polymorphism** — virtual dispatch is recorded at the declared call site only; no CHA/RTA
- **Test-to-method mapping** — identifying which test class covers which production method is planned but not yet implemented

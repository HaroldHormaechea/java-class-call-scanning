# Java Call Graph Scanner

A bytecode analysis tool that builds a method-level call graph and field access index from compiled Java `.class` files. Its primary goal is **selective test execution**: given a Git diff, identify which methods changed, trace all transitive callers, and determine which tests need to run.

---

## Goal

1. Scan compiled `.class` files to build a method-level call hierarchy and a field read/write index
2. Parse a Git diff to identify which source lines changed
3. Map changed lines back to method bodies using bytecode debug info
4. Walk the call graph backward to find every caller that is transitively affected
5. Identify which callers are test methods (JUnit 5, Spock) and output them for selective test execution

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

### Test method detection

The scanner detects test methods via bytecode annotations:

- **JUnit 5** — `@Test`, `@ParameterizedTest`, `@TestFactory`, `@RepeatedTest`, `@TestTemplate`
- **Spock** — `@FeatureMetadata`

Detected tests are indexed and surfaced in JSON output with `isTest`, `testType`, and `testDisplayName` fields.

### Git diff impact analysis

Given a unified diff (file or stdin), the tool:

1. Parses added/changed lines per file
2. Finds methods whose line range contains any changed line
3. Builds full caller trees (unlimited depth, cycle-safe) rooted at each changed method
4. Marks every node that is a test method

In **console** mode, outputs two flat lists: directly changed methods and transitive callers.

In **JSON** mode, outputs `impactedTrees` — full caller hierarchy trees rooted at each directly changed method, with `isTest` markers on test nodes. This is the primary output for selective test execution.

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
java -jar JavaClassCallScanning.jar --compiled <dir|jar|war> [--compiled <dir|jar|war> ...]
                                    [--sources <srcDir>]
                                    [--diff <diffFile> | --diff-stdin | --print-hierarchy <ref>]
                                    [--export-format console|json]
```

`--compiled` can be specified multiple times to scan several directories/archives into a single graph. This is useful for scanning both production and test classes together:

```bash
java -jar JavaClassCallScanning.jar \
  --compiled target/classes \
  --compiled target/test-classes \
  --sources src/main/java \
  --diff changes.patch \
  --export-format json
```

### Using Gradle (development / in-repo)

```
./gradlew run --args="--compiled <dir|jar|war> [--compiled <dir|jar|war> ...]
                      [--sources <srcDir>]
                      [--diff <diffFile> | --diff-stdin | --print-hierarchy <ref>]
                      [--export-format console|json]"
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

Produces `impactedTrees` — full caller hierarchy trees rooted at each directly changed method, with unlimited depth and cycle detection:

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

Nodes that form a cycle are marked with `"cycle": true` and have no children.

#### Windows / PowerShell notes

PowerShell's `>` operator writes files as UTF-16LE, which differs from the UTF-8 that Git normally produces. The tool handles this automatically, but you can also use any of these approaches:

```powershell
# Option 1: Pipe directly (recommended — avoids encoding issues entirely)
git diff HEAD~1..HEAD | java -jar JavaClassCallScanning.jar --compiled build\classes\java\main --sources src\main\java --diff-stdin

# Option 2: Save to file with PowerShell (works — tool detects UTF-16LE)
git diff HEAD~1..HEAD > changes.patch
java -jar JavaClassCallScanning.jar --compiled build\classes\java\main --diff changes.patch

# Option 3: Force UTF-8 output
git diff HEAD~1..HEAD | Out-File -Encoding utf8 changes.patch

# Option 4: Use cmd for redirection (writes plain ASCII/UTF-8)
cmd /c "git diff HEAD~1..HEAD > changes.patch"
```

#### Multi-module projects (e.g., Spring Framework)

For multi-module Gradle/Maven projects, you can point `--compiled` at the specific module or pass multiple paths:

```powershell
# Compile a single module
cd C:\path\to\spring-framework
.\gradlew :spring-context:compileJava

# Scan one module
git diff HEAD~10..HEAD | java -jar JavaClassCallScanning.jar --compiled spring-context\build\classes\java\main --sources spring-context\src\main\java --diff-stdin

# Scan multiple modules together
java -jar JavaClassCallScanning.jar --compiled spring-core\build\classes\java\main --compiled spring-context\build\classes\java\main --diff changes.patch
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

---

## Project layout

```
src/
├── main/java/com/hhg/callgraph/   ← the tool itself
│   ├── model/                     # CallGraph, MethodReference, FieldReference, SourceIndex, TestIndex, ...
│   ├── scanner/                   # ClassFileScanner, ScanResult
│   │   └── test/                  # JUnit5TestDetector, SpockTestDetector, TestMethodDetector
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
| `TestIndex` | `model` | `MethodReference → TestDescriptor`, tracks which methods are tests |
| `TestDescriptor` | `model` | Record: method + testType + displayName |
| `ScanResult` | `scanner` | Record: `(CallGraph, SourceIndex, FieldAccessIndex, TestIndex)` |
| `ClassFileScanner` | `scanner` | Scans directories, JARs, and WARs (incl. nested `WEB-INF/lib/*.jar`); populates all four indexes. Supports scanning multiple paths via `scanPaths()`. |
| `JUnit5TestDetector` | `scanner.test` | Detects `@Test`, `@ParameterizedTest`, `@TestFactory`, `@RepeatedTest`, `@TestTemplate` |
| `SpockTestDetector` | `scanner.test` | Detects `@FeatureMetadata` (Spock framework) |
| `GitDiffParser` | `diff` | Parses unified diff → `List<DiffEntry>`. Handles UTF-8, UTF-16LE/BE, and BOM-prefixed files. |
| `ImpactAnalyzer` | `diff` | Produces `ImpactResult` from `ScanResult` + diffs; accepts optional sources root. Handles relative-to-absolute path matching for multi-module projects. |
| `ImpactResult` | `diff` | `directlyChanged` + `transitiveCallers` (disjoint sets) |
| `CallTreePrinter` | `output` | Console pipe-tree printer + JSON tree builder (callee/caller/impact modes). Cycle-safe with `isTest` annotation on nodes. |
| `CallGraphBuilder` | (root) | Main entry point + CLI dispatch. Uses Gson for pretty-printed JSON output. |

### Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| ASM | 9.8 | Bytecode reading (ClassReader, ClassNode, MethodNode, MethodInsnNode) |
| Gson | 2.11.0 | JSON serialization with pretty-printing |

---

## Out of scope

- **Reflection-based calls** — dynamic dispatch via `Method.invoke()` is not tracked
- **Runtime polymorphism** — virtual dispatch is recorded at the declared call site only; no CHA/RTA

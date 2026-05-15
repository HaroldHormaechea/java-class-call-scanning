## What this is

`java-class-call-scanning` is a bytecode-level Java call-graph and diff-impact analyzer. It reads compiled `.class` files, JARs, and WARs (including nested `WEB-INF/lib/*.jar`) with ASM, builds a method-level call graph plus a field read/write index, and uses bytecode `LineNumberTable` data to map a Git unified diff onto the methods it actually touches. It then walks the graph backward to find every transitive caller and flags the callers that are JUnit 5 or Spock test methods.

Primary use cases:

- **Selective test execution in CI.** Feed a pull-request diff to the tool and run only the test classes/methods it reports as impacted, rather than the full suite.
- **Interactive exploration.** The `--print-hierarchy` mode lets you inspect callers, callees, and field readers/writers of any class, method, or field on a built graph — useful when investigating an unfamiliar codebase or auditing the blast radius of a planned change.
- **Programmatic queries via the daemon.** A long-lived per-project daemon keeps the indexes in memory and answers nine query operations (`refresh-index`, `find-callers`, `find-callees`, `methods-in-class`, `methods-at-line`, `find-field-readers`, `find-field-writers`, `impact-of-diff`, `tests-for-diff`) over either a TCP loopback CLI or an MCP stdio server. The MCP surface lets an MCP-aware client (e.g., an LLM agent) drive the same nine operations as tools.

The tool reads only — it never executes the analyzed code, never instruments it, and never makes network calls.

## How to use

### CLI (one-shot pipeline)

Run the fat JAR directly:

```bash
java -jar java-class-call-scanning.jar --compiled <dir|jar|war> [--compiled <dir|jar|war> ...] \
                                       [--sources <srcDir>] \
                                       [--diff <diffFile> | --diff-stdin | --print-hierarchy <ref>] \
                                       [--export-format console|json]
```

- `--compiled` — directory of `.class` files, a JAR, or a WAR. Repeat the flag to scan multiple inputs into a single graph (typical: production classes + test classes together).
- `--sources <srcDir>` — optional source root. Improves diff-to-method matching when the same simple class name exists in multiple packages.
- `--diff <file>` / `--diff-stdin` — supply a unified Git diff to compute impact. Either flag activates impact analysis; mutually exclusive with `--print-hierarchy`.
- `--print-hierarchy <ref>` — interactive explorer; `<ref>` is a dotted fully-qualified name with an optional `#memberName` suffix.
- `--export-format console|json` — output format. JSON is the machine-readable form for CI consumption (full caller hierarchy trees, `isTest` markers, cycle-safe).

See the project [`README.md`](README.md) §"Running" for worked examples (single-module, multi-module, WAR, JAR, stdin pipelines, PowerShell encoding notes).

### MCP server (daemon, stdio transport)

Launch a daemon scoped to a target project. The daemon scans once, holds the indexes in memory, and exposes both a TCP loopback CLI and an MCP stdio server for the lifetime of the process:

```bash
java -jar java-class-call-scanning.jar daemon start \
    --classpath <dir|jar|war> [--classpath ...] \
    --src <srcDir> [--src ...] \
    [--include <glob>] [--exclude <glob>] \
    [--idle-timeout <minutes>] \
    [--foreground]
```

On startup the daemon writes a discovery file under `~/.cache/java-class-call-scanning/<project-hash>.json` containing the PID and the loopback port; the per-project hash is derived from the canonical classpath + source roots.

The MCP server identifies itself as `java-class-call-scanning` and advertises these nine tools:

| Tool                  | Purpose                                                                                 |
|-----------------------|-----------------------------------------------------------------------------------------|
| `refresh-index`       | Re-scan the daemon's original launch scope and atomically swap the index.               |
| `find-callers`        | BFS over the call graph from a method FQN, returning callers (default depth unbounded). |
| `find-callees`        | BFS over the call graph from a method FQN, returning callees (default depth 3).         |
| `methods-in-class`    | List methods declared on a dotted class FQN.                                            |
| `methods-at-line`     | Methods whose `LineNumberTable` covers a given `(source_file, line)`.                   |
| `find-field-readers`  | Methods that READ a given field FQN (JVM-descriptor form).                              |
| `find-field-writers`  | Methods that WRITE a given field FQN (JVM-descriptor form).                             |
| `impact-of-diff`      | Parse a unified diff and emit the `impactedTrees` payload.                              |
| `tests-for-diff`      | Flat list of impacted tests `{fqn, type, displayName, root_change}` for a unified diff. |

Method FQNs use JVM-descriptor form (`pkg.Cls#name(Ldesc;)Ret`); field FQNs use `pkg.Cls#name:Ldesc;`. `<init>` and `<clinit>` are queryable by their reserved names.

To connect an MCP client, spawn the daemon as a subprocess and speak MCP over its stdio. Most MCP-aware clients accept a launch command in their server config; configure it to invoke the `daemon start` command above (typically with `--foreground` so the process stays attached to the client). Once connected, the nine tools listed above become callable through the client's standard tool-use interface.

To stop the daemon: `java -jar java-class-call-scanning.jar daemon stop --project <id-or-path>` (reads the discovery file and sends a clean shutdown request). `SIGTERM` is handled gracefully; `--idle-timeout` triggers auto-shutdown after the configured idle minutes (opt-in, off by default).

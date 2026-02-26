package com.hhg.callgraph;

import com.hhg.callgraph.diff.DiffEntry;
import com.hhg.callgraph.diff.GitDiffParser;
import com.hhg.callgraph.diff.ImpactAnalyzer;
import com.hhg.callgraph.diff.ImpactResult;
import com.hhg.callgraph.model.CallGraph;
import com.hhg.callgraph.model.FieldAccessIndex;
import com.hhg.callgraph.model.FieldReference;
import com.hhg.callgraph.model.MethodReference;
import com.hhg.callgraph.output.CallTreePrinter;
import com.hhg.callgraph.scanner.ClassFileScanner;
import com.hhg.callgraph.scanner.ScanResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class CallGraphBuilder {

    public static ScanResult fromClassesDir(Path dirOrArchive) throws IOException {
        return new ClassFileScanner().scanPath(dirOrArchive);
    }

    // -----------------------------------------------------------------------
    // CLI args
    // -----------------------------------------------------------------------

    record CliArgs(
            Path compiled,       // required
            Path sources,        // optional
            String mode,         // "summary" | "diff" | "diff-stdin" | "print-hierarchy"
            Path modeArg,        // diff file path (mode == "diff")
            String modeArgStr,   // FQN ref     (mode == "print-hierarchy")
            String exportFormat  // "console" | "json"  (default "console")
    ) {}

    /** Parses named flags. Returns null if required flags are missing or a flag is unrecognised. */
    static CliArgs parseArgs(String[] args) {
        Path compiled = null;
        Path sources = null;
        String mode = "summary";
        Path modeArg = null;
        String modeArgStr = null;
        String exportFormat = "console";

        int i = 0;
        while (i < args.length) {
            switch (args[i]) {
                case "--compiled" -> {
                    if (++i >= args.length) return null;
                    compiled = Path.of(args[i]);
                }
                case "--sources" -> {
                    if (++i >= args.length) return null;
                    sources = Path.of(args[i]);
                }
                case "--diff" -> {
                    if (++i >= args.length) return null;
                    mode = "diff";
                    modeArg = Path.of(args[i]);
                }
                case "--diff-stdin" -> mode = "diff-stdin";
                case "--print-hierarchy" -> {
                    if (++i >= args.length) return null;
                    mode = "print-hierarchy";
                    modeArgStr = args[i];
                }
                case "--export-format" -> {
                    if (++i >= args.length) return null;
                    String fmt = args[i];
                    if (!fmt.equals("console") && !fmt.equals("json")) {
                        System.err.println("Unknown --export-format value: " + fmt + " (must be console or json)");
                        return null;
                    }
                    exportFormat = fmt;
                }
                default -> {
                    System.err.println("Unknown flag: " + args[i]);
                    return null;
                }
            }
            i++;
        }

        if (compiled == null) {
            System.err.println("Missing required flag: --compiled");
            return null;
        }
        return new CliArgs(compiled, sources, mode, modeArg, modeArgStr, exportFormat);
    }

    public static void main(String[] args) throws IOException {
        CliArgs cli = parseArgs(args);
        if (cli == null) {
            printUsage();
            System.exit(1);
            return;
        }

        ScanResult result = fromClassesDir(cli.compiled());
        CallGraph graph = result.callGraph();
        boolean json = "json".equals(cli.exportFormat());

        switch (cli.mode()) {
            case "diff" -> {
                List<DiffEntry> diffs = new GitDiffParser().parseFile(cli.modeArg());
                printImpact(result, diffs, cli.sources(), json);
            }
            case "diff-stdin" -> {
                String stdinContent = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
                List<DiffEntry> diffs = new GitDiffParser().parse(stdinContent);
                printImpact(result, diffs, cli.sources(), json);
            }
            case "print-hierarchy" -> printHierarchy(result, cli.modeArgStr(), json);
            default -> {
                if (json) {
                    printSummaryJson(graph, result);
                } else {
                    printSummaryConsole(graph, result);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Summary (default mode)
    // -----------------------------------------------------------------------

    private static void printSummaryConsole(CallGraph graph, ScanResult result) {
        System.out.println("Methods: " + graph.methodCount());
        System.out.println("Edges:   " + graph.edgeCount());
        System.out.println("Source index entries: " + result.sourceIndex().size());
        for (MethodReference method : graph.getAllMethods()) {
            for (MethodReference callee : graph.getCalleesOf(method)) {
                System.out.println(method.toDisplayString() + " -> " + callee.toDisplayString());
            }
        }
    }

    private static void printSummaryJson(CallGraph graph, ScanResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"methodCount\":").append(graph.methodCount()).append(",");
        sb.append("\"edgeCount\":").append(graph.edgeCount()).append(",");
        sb.append("\"sourceIndexEntries\":").append(result.sourceIndex().size()).append(",");
        sb.append("\"edges\":[");
        boolean first = true;
        for (MethodReference method : graph.getAllMethods()) {
            for (MethodReference callee : graph.getCalleesOf(method)) {
                if (!first) sb.append(",");
                sb.append("{\"caller\":").append(jstr(method.toDisplayString()))
                  .append(",\"callee\":").append(jstr(callee.toDisplayString())).append("}");
                first = false;
            }
        }
        sb.append("]}");
        System.out.println(sb);
    }

    // -----------------------------------------------------------------------
    // --print-hierarchy
    // -----------------------------------------------------------------------

    private static void printHierarchy(ScanResult result, String fqnRef, boolean json) {
        int hashIdx = fqnRef.indexOf('#');
        if (hashIdx < 0) {
            String internalClass = fqnRef.replace('.', '/');
            printClassMode(result.callGraph(), internalClass, json);
        } else {
            String classPart     = fqnRef.substring(0, hashIdx);
            String memberName    = fqnRef.substring(hashIdx + 1);
            String internalClass = classPart.replace('.', '/');

            boolean foundMethod = printMethodMode(result.callGraph(), internalClass, memberName, json);
            boolean foundField  = printFieldMode(result.fieldAccessIndex(), internalClass, memberName, json);

            if (!foundMethod && !foundField) {
                System.err.println("Not found: " + fqnRef);
            }
        }
    }

    private static void printClassMode(CallGraph graph, String internalClass, boolean json) {
        List<MethodReference> methods = graph.getAllMethods().stream()
                .filter(m -> m.getClassName().equals(internalClass))
                .sorted(Comparator.comparing(MethodReference::getMethodName))
                .toList();

        if (methods.isEmpty()) {
            System.err.println("No methods found for class: " + internalClass);
            return;
        }

        if (json) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"class\":").append(jstr(internalClass.replace('/', '.'))).append(",\"methods\":[");
            for (int i = 0; i < methods.size(); i++) {
                MethodReference m = methods.get(i);
                if (i > 0) sb.append(",");
                sb.append("{\"name\":").append(jstr(m.getMethodName()))
                  .append(",\"descriptor\":").append(jstr(m.getDescriptor()))
                  .append(",\"callees\":").append(graph.getCalleesOf(m).size())
                  .append(",\"callers\":").append(graph.getCallersOf(m).size())
                  .append("}");
            }
            sb.append("]}");
            System.out.println(sb);
        } else {
            System.out.println("=== Class: " + simpleName(internalClass) + " ===");
            System.out.println("Methods (" + methods.size() + "):");
            for (MethodReference method : methods) {
                int callees = graph.getCalleesOf(method).size();
                int callers = graph.getCallersOf(method).size();
                System.out.printf("  %-30s %d %s, called by %d%n",
                        method.getMethodName(),
                        callees, callees == 1 ? "callee" : "callees",
                        callers);
            }
        }
    }

    private static boolean printMethodMode(CallGraph graph, String internalClass,
                                           String memberName, boolean json) {
        List<MethodReference> matches = graph.getAllMethods().stream()
                .filter(m -> m.getClassName().equals(internalClass) && m.getMethodName().equals(memberName))
                .toList();

        if (matches.isEmpty()) return false;

        String displayName = simpleName(internalClass) + "#" + memberName;
        CallTreePrinter printer = new CallTreePrinter(graph, System.out);

        if (json) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < matches.size(); i++) {
                if (i > 0) sb.append(",");
                MethodReference match = matches.get(i);
                sb.append("{\"method\":").append(jstr(match.toDisplayString()))
                  .append(",\"calleeTree\":").append(printer.buildCalleeTreeJson(match, 5))
                  .append(",\"callerTree\":").append(printer.buildCallerTreeJson(match, 5))
                  .append("}");
            }
            sb.append("]");
            System.out.println(sb);
        } else {
            System.out.println("=== Method: " + displayName + " ===");
            for (MethodReference match : matches) {
                System.out.println("\nCallee Tree (calls):");
                printer.printCalleeTree(match, 5);
                System.out.println("\nCaller Tree (called by):");
                printer.printCallerTree(match, 5);
            }
        }
        return true;
    }

    private static boolean printFieldMode(FieldAccessIndex fieldAccessIndex,
                                          String internalClass, String memberName, boolean json) {
        Set<FieldReference> fields = fieldAccessIndex.findByName(internalClass, memberName);
        if (fields.isEmpty()) return false;

        if (json) {
            StringBuilder sb = new StringBuilder("[");
            boolean firstField = true;
            for (FieldReference field : fields) {
                if (!firstField) sb.append(",");
                firstField = false;
                sb.append("{\"field\":").append(jstr(field.toDisplayString()));
                sb.append(",\"readBy\":[");
                sb.append(fieldAccessIndex.getReaders(field).stream()
                        .sorted(Comparator.comparing(MethodReference::toDisplayString))
                        .map(m -> jstr(m.toDisplayString()))
                        .collect(Collectors.joining(",")));
                sb.append("],\"writtenBy\":[");
                sb.append(fieldAccessIndex.getWriters(field).stream()
                        .sorted(Comparator.comparing(MethodReference::toDisplayString))
                        .map(m -> jstr(m.toDisplayString()))
                        .collect(Collectors.joining(",")));
                sb.append("]}");
            }
            sb.append("]");
            System.out.println(sb);
        } else {
            for (FieldReference field : fields) {
                String displayName = simpleName(field.className()) + "#" + field.fieldName();
                System.out.println("=== Field: " + displayName + " ===");

                Set<MethodReference> readers = fieldAccessIndex.getReaders(field);
                System.out.println("\nRead by (" + readers.size() + "):");
                readers.stream()
                        .sorted(Comparator.comparing(m -> simpleName(m.getClassName()) + "#" + m.getMethodName()))
                        .forEach(m -> System.out.println("  " + simpleName(m.getClassName()) + "#" + m.getMethodName()));

                Set<MethodReference> writers = fieldAccessIndex.getWriters(field);
                System.out.println("\nWritten by (" + writers.size() + "):");
                writers.stream()
                        .sorted(Comparator.comparing(m -> simpleName(m.getClassName()) + "#" + m.getMethodName()))
                        .forEach(m -> System.out.println("  " + simpleName(m.getClassName()) + "#" + m.getMethodName()));
            }
        }
        return true;
    }

    private static String simpleName(String internalClass) {
        int lastSlash = internalClass.lastIndexOf('/');
        return lastSlash >= 0 ? internalClass.substring(lastSlash + 1) : internalClass;
    }

    // -----------------------------------------------------------------------
    // --diff
    // -----------------------------------------------------------------------

    private static void printImpact(ScanResult result, List<DiffEntry> diffs,
                                    Path sourcesRoot, boolean json) {
        ImpactAnalyzer analyzer = new ImpactAnalyzer(result, sourcesRoot);
        ImpactResult impact = analyzer.analyze(diffs);

        Set<MethodReference> direct = impact.directlyChanged();
        Set<MethodReference> transitive = impact.transitiveCallers();

        if (json) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"directlyChanged\":[");
            sb.append(direct.stream()
                    .sorted(Comparator.comparing(MethodReference::toDisplayString))
                    .map(m -> jstr(m.toDisplayString()))
                    .collect(Collectors.joining(",")));
            sb.append("],\"transitiveCallers\":[");
            sb.append(transitive.stream()
                    .sorted(Comparator.comparing(MethodReference::toDisplayString))
                    .map(m -> jstr(m.toDisplayString()))
                    .collect(Collectors.joining(",")));
            sb.append("]}");
            System.out.println(sb);
        } else {
            System.out.println("=== Directly Changed Methods (" + direct.size() + ") ===");
            direct.stream()
                    .sorted(Comparator.comparing(MethodReference::toDisplayString))
                    .forEach(m -> System.out.println(m.toDisplayString()));

            System.out.println("=== Transitive Callers (" + transitive.size() + ") ===");
            transitive.stream()
                    .sorted(Comparator.comparing(MethodReference::toDisplayString))
                    .forEach(m -> System.out.println(m.toDisplayString()));
        }
    }

    // -----------------------------------------------------------------------
    // Usage
    // -----------------------------------------------------------------------

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  CallGraphBuilder --compiled <path>                                   (dump all edges)");
        System.err.println("  CallGraphBuilder --compiled <path> --diff <diffFile>                  (impact analysis)");
        System.err.println("  CallGraphBuilder --compiled <path> --diff-stdin                       (impact from stdin)");
        System.err.println("  CallGraphBuilder --compiled <path> --print-hierarchy <ref>            (explore graph)");
        System.err.println();
        System.err.println("Options:");
        System.err.println("  --compiled <path>         Path to compiled classes (directory, .war, or .jar)  [required]");
        System.err.println("  --sources <path>          Source root (e.g. src/main/java); enables exact diff path matching [optional]");
        System.err.println("  --export-format <fmt>     Output format: console (default) or json              [optional]");
        System.err.println();
        System.err.println("<ref> is a dotted class name, optionally with #memberName.");
    }

    // -----------------------------------------------------------------------
    // JSON helpers
    // -----------------------------------------------------------------------

    /** Wraps a string as a JSON string literal, escaping backslashes and double-quotes. */
    private static String jstr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

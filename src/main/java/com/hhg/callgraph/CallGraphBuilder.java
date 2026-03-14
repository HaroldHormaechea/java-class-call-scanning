package com.hhg.callgraph;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hhg.callgraph.diff.DiffEntry;
import com.hhg.callgraph.diff.GitDiffParser;
import com.hhg.callgraph.diff.ImpactAnalyzer;
import com.hhg.callgraph.diff.ImpactResult;
import com.hhg.callgraph.model.CallGraph;
import com.hhg.callgraph.model.FieldAccessIndex;
import com.hhg.callgraph.model.FieldReference;
import com.hhg.callgraph.model.MethodReference;
import com.hhg.callgraph.model.TestIndex;
import com.hhg.callgraph.output.CallTreePrinter;
import com.hhg.callgraph.scanner.ClassFileScanner;
import com.hhg.callgraph.scanner.ScanResult;
import com.hhg.callgraph.scanner.test.JUnit5TestDetector;
import com.hhg.callgraph.scanner.test.SpockTestDetector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class CallGraphBuilder {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static ScanResult fromClassesDir(Path dirOrArchive) throws IOException {
        return fromClassesDirs(List.of(dirOrArchive));
    }

    public static ScanResult fromClassesDirs(List<Path> paths) throws IOException {
        return new ClassFileScanner(List.of(
                new JUnit5TestDetector(),
                new SpockTestDetector()
        )).scanPaths(paths);
    }

    // -----------------------------------------------------------------------
    // CLI args
    // -----------------------------------------------------------------------

    record CliArgs(
            List<Path> compiled, // required, one or more paths
            Path sources,        // optional
            String mode,         // "summary" | "diff" | "diff-stdin" | "print-hierarchy"
            Path modeArg,        // diff file path (mode == "diff")
            String modeArgStr,   // FQN ref     (mode == "print-hierarchy")
            String exportFormat  // "console" | "json"  (default "console")
    ) {}

    /** Parses named flags. Returns null if required flags are missing or a flag is unrecognised. */
    static CliArgs parseArgs(String[] args) {
        List<Path> compiled = new java.util.ArrayList<>();
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
                    compiled.add(Path.of(args[i]));
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

        if (compiled.isEmpty()) {
            System.err.println("Missing required flag: --compiled");
            return null;
        }
        return new CliArgs(List.copyOf(compiled), sources, mode, modeArg, modeArgStr, exportFormat);
    }

    public static void main(String[] args) throws IOException {
        CliArgs cli = parseArgs(args);
        if (cli == null) {
            printUsage();
            System.exit(1);
            return;
        }

        ScanResult result = fromClassesDirs(cli.compiled());
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
                    printSummaryJson(result);
                } else {
                    printSummaryConsole(result);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Summary (default mode)
    // -----------------------------------------------------------------------

    private static void printSummaryConsole(ScanResult result) {
        CallGraph graph = result.callGraph();
        System.out.println("Methods: " + graph.methodCount());
        System.out.println("Edges:   " + graph.edgeCount());
        System.out.println("Source index entries: " + result.sourceIndex().size());
        for (MethodReference method : graph.getAllMethods()) {
            for (MethodReference callee : graph.getCalleesOf(method)) {
                System.out.println(method.toDisplayString() + " -> " + callee.toDisplayString());
            }
        }
    }

    private static void printSummaryJson(ScanResult result) {
        CallGraph graph = result.callGraph();
        TestIndex testIndex = result.testIndex();

        JsonObject root = new JsonObject();
        root.addProperty("methodCount", graph.methodCount());
        root.addProperty("edgeCount", graph.edgeCount());
        root.addProperty("sourceIndexEntries", result.sourceIndex().size());
        root.addProperty("testMethodCount", testIndex.size());

        JsonArray edges = new JsonArray();
        for (MethodReference method : graph.getAllMethods()) {
            for (MethodReference callee : graph.getCalleesOf(method)) {
                JsonObject edge = new JsonObject();
                edge.addProperty("caller", method.toDisplayString());
                edge.addProperty("callee", callee.toDisplayString());
                if (testIndex.isTestMethod(method)) {
                    edge.addProperty("callerIsTest", true);
                }
                if (testIndex.isTestMethod(callee)) {
                    edge.addProperty("calleeIsTest", true);
                }
                edges.add(edge);
            }
        }
        root.add("edges", edges);

        System.out.println(GSON.toJson(root));
    }

    // -----------------------------------------------------------------------
    // --print-hierarchy
    // -----------------------------------------------------------------------

    private static void printHierarchy(ScanResult result, String fqnRef, boolean json) {
        int hashIdx = fqnRef.indexOf('#');
        if (hashIdx < 0) {
            String internalClass = fqnRef.replace('.', '/');
            printClassMode(result, internalClass, json);
        } else {
            String classPart     = fqnRef.substring(0, hashIdx);
            String memberName    = fqnRef.substring(hashIdx + 1);
            String internalClass = classPart.replace('.', '/');

            boolean foundMethod = printMethodMode(result, internalClass, memberName, json);
            boolean foundField  = printFieldMode(result.fieldAccessIndex(), internalClass, memberName, json);

            if (!foundMethod && !foundField) {
                System.err.println("Not found: " + fqnRef);
            }
        }
    }

    private static void printClassMode(ScanResult result, String internalClass, boolean json) {
        CallGraph graph = result.callGraph();
        TestIndex testIndex = result.testIndex();

        List<MethodReference> methods = graph.getAllMethods().stream()
                .filter(m -> m.getClassName().equals(internalClass))
                .sorted(Comparator.comparing(MethodReference::getMethodName))
                .toList();

        if (methods.isEmpty()) {
            System.err.println("No methods found for class: " + internalClass);
            return;
        }

        if (json) {
            JsonObject root = new JsonObject();
            root.addProperty("class", internalClass.replace('/', '.'));

            JsonArray methodsArray = new JsonArray();
            for (MethodReference m : methods) {
                JsonObject methodObj = new JsonObject();
                methodObj.addProperty("name", m.getMethodName());
                methodObj.addProperty("descriptor", m.getDescriptor());
                methodObj.addProperty("callees", graph.getCalleesOf(m).size());
                methodObj.addProperty("callers", graph.getCallersOf(m).size());
                if (testIndex.isTestMethod(m)) {
                    methodObj.addProperty("isTest", true);
                    testIndex.getDescriptor(m).ifPresent(desc -> {
                        methodObj.addProperty("testType", desc.testType());
                        methodObj.addProperty("testDisplayName", desc.displayName());
                    });
                }
                methodsArray.add(methodObj);
            }
            root.add("methods", methodsArray);

            System.out.println(GSON.toJson(root));
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

    private static boolean printMethodMode(ScanResult result, String internalClass,
                                           String memberName, boolean json) {
        CallGraph graph = result.callGraph();
        TestIndex testIndex = result.testIndex();

        List<MethodReference> matches = graph.getAllMethods().stream()
                .filter(m -> m.getClassName().equals(internalClass) && m.getMethodName().equals(memberName))
                .toList();

        if (matches.isEmpty()) return false;

        String displayName = simpleName(internalClass) + "#" + memberName;
        CallTreePrinter printer = new CallTreePrinter(graph, System.out, testIndex);

        if (json) {
            JsonArray results = new JsonArray();
            for (MethodReference match : matches) {
                JsonObject obj = new JsonObject();
                obj.addProperty("method", match.toDisplayString());
                if (testIndex.isTestMethod(match)) {
                    obj.addProperty("isTest", true);
                    testIndex.getDescriptor(match).ifPresent(desc -> {
                        obj.addProperty("testType", desc.testType());
                        obj.addProperty("testDisplayName", desc.displayName());
                    });
                }
                obj.add("calleeTree", printer.buildCalleeTreeJson(match, 5));
                obj.add("callerTree", printer.buildCallerTreeJson(match, 5));
                results.add(obj);
            }
            System.out.println(GSON.toJson(results));
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
            JsonArray results = new JsonArray();
            for (FieldReference field : fields) {
                JsonObject obj = new JsonObject();
                obj.addProperty("field", field.toDisplayString());

                JsonArray readBy = new JsonArray();
                fieldAccessIndex.getReaders(field).stream()
                        .sorted(Comparator.comparing(MethodReference::toDisplayString))
                        .forEach(m -> readBy.add(m.toDisplayString()));
                obj.add("readBy", readBy);

                JsonArray writtenBy = new JsonArray();
                fieldAccessIndex.getWriters(field).stream()
                        .sorted(Comparator.comparing(MethodReference::toDisplayString))
                        .forEach(m -> writtenBy.add(m.toDisplayString()));
                obj.add("writtenBy", writtenBy);

                results.add(obj);
            }
            System.out.println(GSON.toJson(results));
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
        TestIndex testIndex = result.testIndex();

        Set<MethodReference> direct = impact.directlyChanged();
        Set<MethodReference> transitive = impact.transitiveCallers();

        if (json) {
            CallTreePrinter printer = new CallTreePrinter(
                    result.callGraph(), System.out, testIndex);

            JsonObject root = new JsonObject();
            JsonArray impactedTrees = new JsonArray();

            direct.stream()
                    .sorted(Comparator.comparing(MethodReference::toDisplayString))
                    .forEach(m -> impactedTrees.add(printer.buildImpactCallerTree(m)));

            root.add("impactedTrees", impactedTrees);

            System.out.println(GSON.toJson(root));
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
}

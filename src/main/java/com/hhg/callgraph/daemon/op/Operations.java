package com.hhg.callgraph.daemon.op;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hhg.callgraph.daemon.Fqn;
import com.hhg.callgraph.daemon.IndexSnapshot;
import com.hhg.callgraph.diff.DiffEntry;
import com.hhg.callgraph.diff.GitDiffParser;
import com.hhg.callgraph.diff.ImpactAnalyzer;
import com.hhg.callgraph.diff.ImpactResult;
import com.hhg.callgraph.model.CallGraph;
import com.hhg.callgraph.model.FieldAccessIndex;
import com.hhg.callgraph.model.FieldReference;
import com.hhg.callgraph.model.MethodReference;
import com.hhg.callgraph.model.SourceIndex;
import com.hhg.callgraph.model.SourceLocation;
import com.hhg.callgraph.model.TestDescriptor;
import com.hhg.callgraph.model.TestIndex;
import com.hhg.callgraph.output.CallTreePrinter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Façade dispatching the nine user-facing operations + {@code refresh-index} +
 * the two internal ops ({@code ping}, {@code shutdown}) by name. All public methods
 * are pure functions of {@link IndexSnapshot} + arguments; the only side-effecting
 * op is {@code refresh-index}, which is routed back to the daemon via the supplied
 * {@link Refresher} callback. No I/O happens inside this class.
 *
 * <p>FQNs returned to clients always go through {@link Fqn#methodToFqn(MethodReference)} /
 * {@link Fqn#fieldToFqn(FieldReference)}.
 */
public final class Operations {

    /** What to do when a client asks for a fresh re-scan. */
    public interface Refresher {
        /** Triggers a re-scan of the original launch scope, swapping the index atomically.
         *  Returns a {@code result} JSON suitable for the {@code refresh-index} response
         *  ({@code {"status":"ok","methods":N,"edges":M,"scan_ms":T}}). */
        JsonObject rescan();
    }

    /** Source of the {@code watcher-status} payload (UC04 AC #14). */
    public interface WatcherStatusProvider {
        JsonObject watcherStatus();
    }

    public static final List<String> USER_FACING_OPS = List.of(
            "refresh-index",
            "find-callers", "find-callees",
            "methods-in-class", "methods-at-line",
            "find-field-readers", "find-field-writers",
            "impact-of-diff", "tests-for-diff",
            "watcher-status"
    );

    private final Supplier<IndexSnapshot> snapshotSupplier;
    private final Refresher refresher;
    private final WatcherStatusProvider watcherStatusProvider;   // nullable
    private final BooleanSupplier restartInProgress;             // nullable

    public Operations(Supplier<IndexSnapshot> snapshotSupplier, Refresher refresher) {
        this(snapshotSupplier, refresher, null, null);
    }

    public Operations(Supplier<IndexSnapshot> snapshotSupplier,
                      Refresher refresher,
                      WatcherStatusProvider watcherStatusProvider,
                      BooleanSupplier restartInProgress) {
        this.snapshotSupplier = snapshotSupplier;
        this.refresher = refresher;
        this.watcherStatusProvider = watcherStatusProvider;
        this.restartInProgress = restartInProgress;
    }

    /** Dispatches by op name. Returns Failure with {@code unknown-op} for unknown names. */
    public OperationResult dispatch(String op, JsonObject args) {
        if (op == null) return OperationResult.error("bad-request", "missing 'op' field");
        // Short-circuit non-status ops while a self-restart is mid-flight (UC04 §6).
        if (restartInProgress != null && restartInProgress.getAsBoolean()) {
            switch (op) {
                case "ping", "shutdown", "watcher-status" -> { /* allow through */ }
                default -> {
                    return OperationResult.error("restart-in-progress",
                            "daemon is restarting; retry shortly");
                }
            }
        }
        try {
            return switch (op) {
                case "refresh-index"      -> refreshIndex();
                case "find-callers"       -> findCallers(args);
                case "find-callees"       -> findCallees(args);
                case "methods-in-class"   -> methodsInClass(args);
                case "methods-at-line"    -> methodsAtLine(args);
                case "find-field-readers" -> findFieldReaders(args);
                case "find-field-writers" -> findFieldWriters(args);
                case "impact-of-diff"     -> impactOfDiff(args);
                case "tests-for-diff"     -> testsForDiff(args);
                case "watcher-status"     -> watcherStatus();
                default                   -> OperationResult.error("unknown-op",
                        "unknown op: " + op);
            };
        } catch (Fqn.BadFqnException e) {
            return OperationResult.error("bad-fqn", e.getMessage());
        } catch (BadArgumentException e) {
            return OperationResult.error("bad-request", e.getMessage());
        }
    }

    private OperationResult watcherStatus() {
        if (watcherStatusProvider == null) {
            return OperationResult.error("watcher-unavailable",
                    "this daemon was not configured with a watcher-status provider");
        }
        return OperationResult.ok(watcherStatusProvider.watcherStatus());
    }

    // -----------------------------------------------------------------------
    // refresh-index
    // -----------------------------------------------------------------------

    public OperationResult refreshIndex() {
        if (refresher == null) {
            return OperationResult.error("refresh-unavailable",
                    "this daemon was not configured with a refresher");
        }
        return OperationResult.ok(refresher.rescan());
    }

    // -----------------------------------------------------------------------
    // find-callers / find-callees
    // -----------------------------------------------------------------------

    private OperationResult findCallers(JsonObject args) {
        return findTree(args, /*callers=*/true, /*defaultDepth=*/-1);
    }

    private OperationResult findCallees(JsonObject args) {
        return findTree(args, /*callers=*/false, /*defaultDepth=*/3);
    }

    private OperationResult findTree(JsonObject args, boolean callers, int defaultDepth) {
        String methodFqn = requireString(args, "method_fqn");
        int depth = optionalInt(args, "depth", defaultDepth);

        MethodReference root = Fqn.parseMethod(methodFqn);
        IndexSnapshot snap = snapshotSupplier.get();
        CallGraph graph = snap.scan().callGraph();

        if (!graph.getAllMethods().contains(root)) {
            return OperationResult.error("unknown-method",
                    "method not in index: " + methodFqn);
        }

        Set<MethodReference> path = new HashSet<>();
        path.add(root);
        JsonObject tree = treeNode(graph, root, path, 0, depth, callers, snap.scan().testIndex());

        JsonObject result = new JsonObject();
        result.add("tree", tree);
        return OperationResult.ok(result);
    }

    private static JsonObject treeNode(CallGraph graph, MethodReference node,
                                       Set<MethodReference> path, int depth, int maxDepth,
                                       boolean callers, TestIndex testIndex) {
        JsonObject obj = new JsonObject();
        obj.addProperty("method", Fqn.methodToFqn(node));
        annotateTest(obj, node, testIndex);

        if (maxDepth >= 0 && depth >= maxDepth) {
            obj.addProperty("truncated", true);
            obj.addProperty("reason", "depth");
            obj.remove("children");
            return obj;
        }

        Set<MethodReference> neighbours = callers
                ? graph.getCallersOf(node) : graph.getCalleesOf(node);
        // Filter out <classref> sentinels from user-visible output.
        List<MethodReference> children = neighbours.stream()
                .filter(m -> !"<classref>".equals(m.getMethodName()))
                .sorted(Comparator.comparing(Fqn::methodToFqn))
                .toList();

        if (children.isEmpty()) {
            return obj;
        }

        JsonArray arr = new JsonArray();
        for (MethodReference child : children) {
            if (path.contains(child)) {
                JsonObject cycleNode = new JsonObject();
                cycleNode.addProperty("method", Fqn.methodToFqn(child));
                cycleNode.addProperty("truncated", true);
                cycleNode.addProperty("reason", "cycle");
                arr.add(cycleNode);
            } else {
                path.add(child);
                arr.add(treeNode(graph, child, path, depth + 1, maxDepth, callers, testIndex));
                path.remove(child);
            }
        }
        obj.add("children", arr);
        return obj;
    }

    // -----------------------------------------------------------------------
    // methods-in-class
    // -----------------------------------------------------------------------

    private OperationResult methodsInClass(JsonObject args) {
        String dotted = requireString(args, "class_fqn");
        String internal = Fqn.parseDottedClass(dotted);

        IndexSnapshot snap = snapshotSupplier.get();
        CallGraph graph = snap.scan().callGraph();
        SourceIndex sourceIndex = snap.scan().sourceIndex();
        TestIndex testIndex = snap.scan().testIndex();

        // Collect candidate methods from the call graph.
        Set<MethodReference> candidates = new HashSet<>();
        for (MethodReference m : graph.getAllMethods()) {
            if (internal.equals(m.getClassName())
                    && !"<classref>".equals(m.getMethodName())) {
                candidates.add(m);
            }
        }
        // Also include methods that have only a source-index entry (no graph edges).
        // SourceIndex doesn't expose a getAll, so we iterate the graph union of method keys
        // and additionally scan via the call-graph's all-methods view above. To capture
        // graph-less methods (rare: methods with no calls in or out that are still scanned),
        // we'd need a public iterator on SourceIndex; for now graph union is the
        // authoritative source as in the legacy --print-hierarchy class path.
        // (If a regression appears here, expose SourceIndex.methods() and union below.)

        if (candidates.isEmpty()) {
            return OperationResult.error("unknown-class",
                    "no methods for class: " + dotted);
        }

        List<MethodReference> sorted = candidates.stream()
                .sorted(Comparator.comparing(MethodReference::getMethodName)
                        .thenComparing(MethodReference::getDescriptor))
                .toList();

        JsonArray arr = new JsonArray();
        for (MethodReference m : sorted) {
            JsonObject mo = new JsonObject();
            mo.addProperty("name", m.getMethodName());
            mo.addProperty("descriptor", m.getDescriptor());

            Optional<SourceLocation> loc = sourceIndex.getLocation(m);
            if (loc.isPresent() && !loc.get().isUnknown()) {
                mo.addProperty("line_start", loc.get().startLine());
                mo.addProperty("line_end",   loc.get().endLine());
            } else {
                mo.add("line_start", null);
                mo.add("line_end",   null);
            }
            mo.addProperty("is_test", testIndex.isTestMethod(m));
            arr.add(mo);
        }

        JsonObject result = new JsonObject();
        result.add("methods", arr);
        return OperationResult.ok(result);
    }

    // -----------------------------------------------------------------------
    // methods-at-line
    // -----------------------------------------------------------------------

    private OperationResult methodsAtLine(JsonObject args) {
        String sourceFile = requireString(args, "source_file");
        int line = requireInt(args, "line");

        IndexSnapshot snap = snapshotSupplier.get();
        SourceIndex sourceIndex = snap.scan().sourceIndex();

        String normalised = sourceFile.replace('\\', '/');
        Set<MethodReference> matches = sourceIndex.findMethodsAt(normalised, line);

        // Sort deterministically by FQN.
        List<String> fqns = new ArrayList<>(matches.size());
        for (MethodReference m : matches) {
            if ("<classref>".equals(m.getMethodName())) continue;
            fqns.add(Fqn.methodToFqn(m));
        }
        Collections.sort(fqns);

        JsonArray arr = new JsonArray();
        for (String s : fqns) arr.add(s);

        JsonObject result = new JsonObject();
        result.add("methods", arr);
        return OperationResult.ok(result);
    }

    // -----------------------------------------------------------------------
    // find-field-readers / find-field-writers
    // -----------------------------------------------------------------------

    private OperationResult findFieldReaders(JsonObject args) {
        return findFieldAccess(args, true);
    }

    private OperationResult findFieldWriters(JsonObject args) {
        return findFieldAccess(args, false);
    }

    private OperationResult findFieldAccess(JsonObject args, boolean readers) {
        String fieldFqn = requireString(args, "field_fqn");
        FieldReference fr = Fqn.parseField(fieldFqn);

        IndexSnapshot snap = snapshotSupplier.get();
        FieldAccessIndex fai = snap.scan().fieldAccessIndex();

        if (!fai.getAllFields().contains(fr)) {
            return OperationResult.error("unknown-field",
                    "field not in index: " + fieldFqn);
        }

        Set<MethodReference> accessors = readers ? fai.getReaders(fr) : fai.getWriters(fr);
        TreeSet<String> fqns = new TreeSet<>();
        for (MethodReference m : accessors) {
            if ("<classref>".equals(m.getMethodName())) continue;
            fqns.add(Fqn.methodToFqn(m));
        }

        JsonArray arr = new JsonArray();
        for (String s : fqns) arr.add(s);

        JsonObject result = new JsonObject();
        result.add("methods", arr);
        return OperationResult.ok(result);
    }

    // -----------------------------------------------------------------------
    // impact-of-diff
    // -----------------------------------------------------------------------

    private OperationResult impactOfDiff(JsonObject args) {
        String diffText = requireString(args, "diff_text");
        IndexSnapshot snap = snapshotSupplier.get();

        List<DiffEntry> diffs = new GitDiffParser().parse(diffText);
        ImpactAnalyzer analyzer = new ImpactAnalyzer(snap.scan(), snap.scope().sourceRoots());
        ImpactResult impact = analyzer.analyze(diffs);

        CallTreePrinter printer = new CallTreePrinter(
                snap.scan().callGraph(), System.err, snap.scan().testIndex());

        JsonArray impactedTrees = new JsonArray();
        impact.directlyChanged().stream()
                .sorted(Comparator.comparing(MethodReference::toDisplayString))
                .forEach(m -> impactedTrees.add(printer.buildImpactCallerTree(m)));

        JsonObject result = new JsonObject();
        result.add("impactedTrees", impactedTrees);
        return OperationResult.ok(result);
    }

    // -----------------------------------------------------------------------
    // tests-for-diff
    // -----------------------------------------------------------------------

    private OperationResult testsForDiff(JsonObject args) {
        String diffText = requireString(args, "diff_text");
        IndexSnapshot snap = snapshotSupplier.get();

        List<DiffEntry> diffs = new GitDiffParser().parse(diffText);
        ImpactAnalyzer analyzer = new ImpactAnalyzer(snap.scan(), snap.scope().sourceRoots());
        ImpactResult impact = analyzer.analyze(diffs);

        TestIndex testIndex = snap.scan().testIndex();
        CallGraph graph = snap.scan().callGraph();

        // For each direct change, attribute every reachable test back to that root_change.
        JsonArray tests = new JsonArray();
        // Sort directs for deterministic emission.
        List<MethodReference> directs = impact.directlyChanged().stream()
                .sorted(Comparator.comparing(Fqn::methodToFqn))
                .toList();

        for (MethodReference direct : directs) {
            Set<MethodReference> reachable = new HashSet<>(graph.getAllTransitiveCallers(direct));
            reachable.add(direct);

            List<MethodReference> sortedReachable = reachable.stream()
                    .sorted(Comparator.comparing(Fqn::methodToFqn))
                    .toList();

            for (MethodReference caller : sortedReachable) {
                Optional<TestDescriptor> desc = testIndex.getDescriptor(caller);
                if (desc.isEmpty()) continue;
                JsonObject row = new JsonObject();
                row.addProperty("fqn", Fqn.methodToFqn(caller));
                row.addProperty("type", TestTypeMapping.wireType(desc.get().testType()));
                row.addProperty("displayName", desc.get().displayName());
                row.addProperty("root_change", Fqn.methodToFqn(direct));
                tests.add(row);
            }
        }

        JsonObject result = new JsonObject();
        result.add("tests", tests);
        return OperationResult.ok(result);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static void annotateTest(JsonObject obj, MethodReference m, TestIndex testIndex) {
        if (testIndex.isTestMethod(m)) {
            obj.addProperty("isTest", true);
            testIndex.getDescriptor(m).ifPresent(desc -> {
                obj.addProperty("testType", desc.testType());
                obj.addProperty("testDisplayName", desc.displayName());
            });
        }
    }

    private static String requireString(JsonObject args, String key) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
            throw new BadArgumentException("missing argument: " + key);
        }
        JsonElement el = args.get(key);
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
            throw new BadArgumentException("argument '" + key + "' must be a string");
        }
        return el.getAsString();
    }

    private static int requireInt(JsonObject args, String key) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
            throw new BadArgumentException("missing argument: " + key);
        }
        JsonElement el = args.get(key);
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            throw new BadArgumentException("argument '" + key + "' must be an integer");
        }
        return el.getAsInt();
    }

    private static int optionalInt(JsonObject args, String key, int defaultValue) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) return defaultValue;
        JsonElement el = args.get(key);
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            throw new BadArgumentException("argument '" + key + "' must be an integer");
        }
        return el.getAsInt();
    }

    /** Signals a structurally bad request (missing / wrong-typed arg). */
    public static final class BadArgumentException extends RuntimeException {
        public BadArgumentException(String msg) { super(msg); }
    }
}

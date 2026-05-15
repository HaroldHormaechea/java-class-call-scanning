package com.hhg.callgraph.daemon.op;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hhg.callgraph.daemon.IndexSnapshot;
import com.hhg.callgraph.daemon.ScanMeta;
import com.hhg.callgraph.daemon.ScopeConfig;
import com.hhg.callgraph.diff.DiffEntry;
import com.hhg.callgraph.diff.GitDiffParser;
import com.hhg.callgraph.diff.ImpactAnalyzer;
import com.hhg.callgraph.diff.ImpactResult;
import com.hhg.callgraph.model.MethodReference;
import com.hhg.callgraph.output.CallTreePrinter;
import com.hhg.callgraph.scanner.ClassFileScanner;
import com.hhg.callgraph.scanner.ScanResult;
import com.hhg.callgraph.scanner.test.JUnit5TestDetector;
import com.hhg.callgraph.scanner.test.SpockTestDetector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * AC #17 — {@code impact-of-diff} emits JSON byte-identical (modulo whitespace) to the
 * legacy one-shot CLI pipeline's {@code impactedTrees} block for the same diff and
 * project scope.
 *
 * <p>Strategy: the production pipeline ({@code CallGraphBuilder.printImpact} when
 * called with {@code --export-format json}) writes:
 *
 * <pre>{@code  { "impactedTrees": [ <printer.buildImpactCallerTree(m) for each direct change in sorted order> ] }
 * }</pre>
 *
 * <p>The {@code impact-of-diff} op in {@link Operations} computes its payload through
 * the same code path. This test produces both JSON documents in-test using the same
 * inputs and asserts byte-equality after pretty-printing.
 *
 * <p>Whitespace tolerance: we re-serialise both sides through the same
 * {@link Gson#toJson(Object) Gson pretty-printer} so trivial whitespace drift cannot
 * fail the assertion. Anything beyond whitespace (key order, value drift, missing
 * fields, extra fields) is a real regression.
 *
 * <p>The "golden" is checked-in implicitly: the legacy code path
 * ({@link CallTreePrinter#buildImpactCallerTree}) is the authoritative output. Any
 * future refactor that changes the daemon's {@code impact-of-diff} formatting without
 * also updating the legacy printer will fail this test loudly.
 */
@DisplayName("impact-of-diff (AC #17) — byte-identical to legacy pipeline")
class ImpactOfDiffGoldenTest {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DIFF;

    static {
        // Synthetic diff that mutates the TargetClass2.methodInClass2 body — a single
        // line that we know participates in the existing TC1→TC2→TC3→println chain
        // (see CallGraphIntegrationTest). This guarantees deterministic impacted-tree
        // content.
        DIFF = """
                --- a/com/hhg/main/targets/TargetClass2.java
                +++ b/com/hhg/main/targets/TargetClass2.java
                @@ -5,3 +5,3 @@
                     public void methodInClass2() {
                -        tc3.methodInClass3();
                +        tc3.methodInClass3(); // touched
                     }
                """;
    }

    private static ScanResult scan;
    private static AtomicReference<IndexSnapshot> ref;
    private static Operations ops;

    @BeforeAll
    static void buildIndex() throws IOException {
        Path targetsDir = Path.of(System.getProperty("user.dir"))
                .resolve("build/classes/java/test/com/hhg/main/targets");
        if (!Files.exists(targetsDir)) {
            fail("Target classes not compiled — run `./gradlew compileTestJava` first. "
                    + "Expected: " + targetsDir);
        }
        scan = new ClassFileScanner(List.of(
                new JUnit5TestDetector(),
                new SpockTestDetector()
        )).scan(targetsDir);
        ScopeConfig scope = ScopeConfig.of(List.of(targetsDir), List.of(),
                List.of(), List.of());
        ScanMeta meta = new ScanMeta(scan.callGraph().methodCount(),
                scan.callGraph().edgeCount(), 1L, System.currentTimeMillis());
        ref = new AtomicReference<>(new IndexSnapshot(scan, scope, meta));
        ops = new Operations(ref::get, () -> new JsonObject());
    }

    @Test
    @DisplayName("daemon impact-of-diff JSON equals legacy pipeline JSON (whitespace-normalised)")
    void byteIdenticalToLegacy() {
        // Daemon-side output.
        JsonObject args = new JsonObject();
        args.addProperty("diff_text", DIFF);
        OperationResult result = ops.dispatch("impact-of-diff", args);
        if (!(result instanceof OperationResult.Success success)) {
            fail("impact-of-diff failed: " + result);
            return;
        }
        String daemonJson = GSON.toJson(success.result());

        // Legacy-side output — reproduce CallGraphBuilder.printImpact(json=true).
        List<DiffEntry> diffs = new GitDiffParser().parse(DIFF);
        ImpactAnalyzer analyzer = new ImpactAnalyzer(scan, (List<Path>) List.<Path>of());
        ImpactResult impact = analyzer.analyze(diffs);
        CallTreePrinter printer = new CallTreePrinter(
                scan.callGraph(), System.err, scan.testIndex());
        JsonObject legacy = new JsonObject();
        JsonArray impactedTrees = new JsonArray();
        impact.directlyChanged().stream()
                .sorted(Comparator.comparing(MethodReference::toDisplayString))
                .forEach(m -> impactedTrees.add(printer.buildImpactCallerTree(m)));
        legacy.add("impactedTrees", impactedTrees);
        String legacyJson = GSON.toJson(legacy);

        assertEquals(legacyJson, daemonJson,
                "AC #17 — impact-of-diff JSON must be byte-identical (modulo whitespace) "
                        + "to the legacy pipeline output. If you intentionally changed the "
                        + "format, update CallTreePrinter.buildImpactCallerTree so both sides "
                        + "stay in lockstep.");
    }

    @Test
    @DisplayName("synthetic diff identifies TargetClass2.methodInClass2 as directly changed")
    void diffProducesExpectedDirectChange() {
        // Sanity guard so byteIdenticalToLegacy is meaningful — confirm the synthetic
        // diff actually maps to a method in the index.
        List<DiffEntry> diffs = new GitDiffParser().parse(DIFF);
        ImpactAnalyzer analyzer = new ImpactAnalyzer(scan, (List<Path>) List.<Path>of());
        ImpactResult impact = analyzer.analyze(diffs);
        boolean hit = impact.directlyChanged().stream().anyMatch(m ->
                m.getClassName().equals("com/hhg/main/targets/TargetClass2")
                        && m.getMethodName().equals("methodInClass2"));
        if (!hit) {
            // Diagnostic fail message helps if the fixture moves.
            fail("synthetic diff failed to map to TargetClass2.methodInClass2 — "
                    + "did the fixture move? directlyChanged=" + impact.directlyChanged());
        }
    }
}

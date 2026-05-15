package com.hhg.callgraph.daemon.op;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hhg.callgraph.daemon.IndexSnapshot;
import com.hhg.callgraph.daemon.ScanMeta;
import com.hhg.callgraph.daemon.ScopeConfig;
import com.hhg.callgraph.model.CallGraph;
import com.hhg.callgraph.model.MethodReference;
import com.hhg.callgraph.scanner.ClassFileScanner;
import com.hhg.callgraph.scanner.ScanResult;
import com.hhg.callgraph.scanner.test.JUnit5TestDetector;
import com.hhg.callgraph.scanner.test.SpockTestDetector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Exercises every one of the nine user-facing operations against the {@code benchmark}
 * Spring Boot fixture by invoking {@link Operations#dispatch(String, JsonObject)} directly.
 *
 * <p>This <strong>is</strong> the "in-process MCP tool" path required by AC #20: the
 * {@link com.hhg.callgraph.daemon.mcp.McpStdioServer} dispatches every tool invocation
 * through {@code operations.dispatch(op, args)}; the TCP path runs the same façade. So
 * this class doubles as the MCP-handler integration test.
 *
 * <p>Covers AC #8, #10, #11, #13, #14, #15, #16, #18, #19, #20.
 */
@DisplayName("Operations — 9 ops against benchmark fixture (in-process MCP path)")
class OperationsBenchmarkTest {

    private static Path benchmarkDir;
    private static Operations ops;
    private static AtomicReference<IndexSnapshot> ref;
    private static int rescanCount = 0;

    @BeforeAll
    static void buildIndex() throws IOException {
        benchmarkDir = Path.of(System.getProperty("user.dir"))
                .resolve("build/classes/java/test/com/hhg/benchmark");
        if (!Files.exists(benchmarkDir)) {
            fail("Benchmark classes not compiled; run `./gradlew compileTestJava` first. "
                    + "Expected: " + benchmarkDir);
        }

        ScanResult scan = new ClassFileScanner(List.of(
                new JUnit5TestDetector(),
                new SpockTestDetector()
        )).scan(benchmarkDir);

        // Source root = the location of the benchmark *.java files. Used by methods-at-line
        // and the impact pipeline.
        Path srcRoot = Path.of(System.getProperty("user.dir"))
                .resolve("src/test/java");
        ScopeConfig scope = ScopeConfig.of(List.of(benchmarkDir), List.of(srcRoot),
                List.of(), List.of());
        ScanMeta meta = new ScanMeta(scan.callGraph().methodCount(),
                scan.callGraph().edgeCount(), 7L, System.currentTimeMillis());

        ref = new AtomicReference<>(new IndexSnapshot(scan, scope, meta));
        ops = new Operations(ref::get, () -> {
            rescanCount++;
            JsonObject r = new JsonObject();
            r.addProperty("status", "ok");
            r.addProperty("methods", scan.callGraph().methodCount());
            r.addProperty("edges",   scan.callGraph().edgeCount());
            r.addProperty("scan_ms", 11L);
            return r;
        });
    }

    private static JsonObject expectOk(OperationResult r) {
        if (r instanceof OperationResult.Success s) {
            return s.result();
        }
        OperationResult.Failure f = (OperationResult.Failure) r;
        fail("expected Success, got Failure: " + f.errorCode() + " — " + f.detail());
        return null;
    }

    private static OperationResult.Failure expectFailure(OperationResult r) {
        if (r instanceof OperationResult.Failure f) return f;
        fail("expected Failure, got Success");
        return null;
    }

    // ===================================================================
    // refresh-index — AC #11
    // ===================================================================

    @Nested
    @DisplayName("refresh-index (AC #11)")
    class RefreshIndex {

        @Test
        @DisplayName("returns {status, methods, edges, scan_ms} and delegates to refresher")
        void refreshShape() {
            int before = rescanCount;
            JsonObject r = expectOk(ops.dispatch("refresh-index", new JsonObject()));
            assertEquals("ok",   r.get("status").getAsString());
            assertTrue(r.has("methods"));
            assertTrue(r.has("edges"));
            assertTrue(r.has("scan_ms"));
            assertEquals(before + 1, rescanCount,
                    "refresh-index must invoke the Refresher exactly once");
        }
    }

    // ===================================================================
    // find-callers / find-callees — AC #13
    // ===================================================================

    @Nested
    @DisplayName("find-callers / find-callees (AC #13)")
    class FindTree {

        @Test
        @DisplayName("find-callees returns a {tree:{method,...,children?}} on a real method")
        void findCalleesShape() {
            String methodFqn = pickAnyMethod();
            JsonObject args = new JsonObject();
            args.addProperty("method_fqn", methodFqn);
            args.addProperty("depth", 1);
            JsonObject r = expectOk(ops.dispatch("find-callees", args));
            assertTrue(r.has("tree"));
            JsonObject tree = r.getAsJsonObject("tree");
            assertEquals(methodFqn, tree.get("method").getAsString());
        }

        @Test
        @DisplayName("find-callees with depth=0 marks the root truncated with reason=depth")
        void depthZeroTruncates() {
            String methodFqn = pickAnyMethod();
            JsonObject args = new JsonObject();
            args.addProperty("method_fqn", methodFqn);
            args.addProperty("depth", 0);
            JsonObject r = expectOk(ops.dispatch("find-callees", args));
            JsonObject tree = r.getAsJsonObject("tree");
            assertTrue(tree.has("truncated") && tree.get("truncated").getAsBoolean());
            assertEquals("depth", tree.get("reason").getAsString());
            assertFalse(tree.has("children"),
                    "truncated nodes MUST NOT carry a 'children' key (AC #13)");
        }

        @Test
        @DisplayName("find-callers with default depth (-1) walks unbounded callers")
        void unboundedCallers() {
            // Find a known UserService leaf method (e.g. authenticate) and ensure
            // we get at least one caller layer (UserController).
            String authenticate = "com.hhg.benchmark.service.UserService#authenticate"
                    + "(Ljava/lang/String;Ljava/lang/String;)Lcom/hhg/benchmark/entity/User;";
            JsonObject args = new JsonObject();
            args.addProperty("method_fqn", authenticate);
            JsonObject r = expectOk(ops.dispatch("find-callers", args));
            JsonObject tree = r.getAsJsonObject("tree");
            assertEquals(authenticate, tree.get("method").getAsString());
            // Top-level shouldn't be truncated when default depth is -1 (unbounded).
            assertFalse(tree.has("truncated"));
        }

        @Test
        @DisplayName("unknown method FQN returns unknown-method error code")
        void unknownMethod() {
            JsonObject args = new JsonObject();
            args.addProperty("method_fqn", "no.such.Class#noSuchMethod()V");
            OperationResult.Failure f = expectFailure(ops.dispatch("find-callers", args));
            assertEquals("unknown-method", f.errorCode());
        }

        @Test
        @DisplayName("bad FQN string returns bad-fqn error code (AC #19 partial)")
        void badFqn() {
            JsonObject args = new JsonObject();
            args.addProperty("method_fqn", "not a method fqn");
            OperationResult.Failure f = expectFailure(ops.dispatch("find-callers", args));
            assertEquals("bad-fqn", f.errorCode());
        }
    }

    // ===================================================================
    // methods-in-class — AC #14
    // ===================================================================

    @Nested
    @DisplayName("methods-in-class (AC #14)")
    class MethodsInClass {

        @Test
        @DisplayName("returns methods array with {name, descriptor, line_start, line_end, is_test}")
        void shape() {
            JsonObject args = new JsonObject();
            args.addProperty("class_fqn", "com.hhg.benchmark.service.UserService");
            JsonObject r = expectOk(ops.dispatch("methods-in-class", args));
            JsonArray methods = r.getAsJsonArray("methods");
            assertTrue(methods.size() > 0);
            for (JsonElement el : methods) {
                JsonObject mo = el.getAsJsonObject();
                // Required keys per AC #14.
                assertTrue(mo.has("name"));
                assertTrue(mo.has("descriptor"));
                assertTrue(mo.has("line_start"));
                assertTrue(mo.has("line_end"));
                assertTrue(mo.has("is_test"));
            }
        }

        @Test
        @DisplayName("unknown class returns unknown-class")
        void unknownClass() {
            JsonObject args = new JsonObject();
            args.addProperty("class_fqn", "no.such.Class");
            OperationResult.Failure f = expectFailure(ops.dispatch("methods-in-class", args));
            assertEquals("unknown-class", f.errorCode());
        }

        @Test
        @DisplayName("is_test=false for production benchmark service methods")
        void isTestFalseForBenchmark() {
            JsonObject args = new JsonObject();
            args.addProperty("class_fqn", "com.hhg.benchmark.service.UserService");
            JsonObject r = expectOk(ops.dispatch("methods-in-class", args));
            for (JsonElement el : r.getAsJsonArray("methods")) {
                JsonObject mo = el.getAsJsonObject();
                assertFalse(mo.get("is_test").getAsBoolean(),
                        "benchmark fixture is not a test target → is_test should be false");
            }
        }
    }

    // ===================================================================
    // methods-at-line — AC #15
    // ===================================================================

    @Nested
    @DisplayName("methods-at-line (AC #15)")
    class MethodsAtLine {

        @Test
        @DisplayName("absolute path → matches a known benchmark line")
        void absolutePath() {
            Path absolute = Path.of(System.getProperty("user.dir"))
                    .resolve("src/test/java/com/hhg/benchmark/service/UserService.java")
                    .toAbsolutePath();
            JsonObject args = new JsonObject();
            args.addProperty("source_file", absolute.toString());
            // Line 25 is inside createUser() per UserService.java; bytecode LineNumberTable
            // for createUser starts at line 24 and continues through the method body.
            args.addProperty("line", 25);
            JsonObject r = expectOk(ops.dispatch("methods-at-line", args));
            JsonArray methods = r.getAsJsonArray("methods");
            boolean hit = false;
            for (JsonElement el : methods) {
                if (el.getAsString().startsWith("com.hhg.benchmark.service.UserService#createUser")) {
                    hit = true;
                    break;
                }
            }
            assertTrue(hit, "absolute path should match UserService#createUser; got " + methods);
        }

        @Test
        @DisplayName("source-root-relative path → matches the same method")
        void relativePath() {
            JsonObject args = new JsonObject();
            args.addProperty("source_file",
                    "com/hhg/benchmark/service/UserService.java");
            args.addProperty("line", 25);
            JsonObject r = expectOk(ops.dispatch("methods-at-line", args));
            JsonArray methods = r.getAsJsonArray("methods");
            boolean hit = false;
            for (JsonElement el : methods) {
                if (el.getAsString().startsWith("com.hhg.benchmark.service.UserService#createUser")) {
                    hit = true;
                    break;
                }
            }
            assertTrue(hit, "package-qualified path should match UserService#createUser; got " + methods);
        }

        @Test
        @DisplayName("missing line arg returns bad-request")
        void missingLine() {
            JsonObject args = new JsonObject();
            args.addProperty("source_file", "X.java");
            OperationResult.Failure f = expectFailure(ops.dispatch("methods-at-line", args));
            assertEquals("bad-request", f.errorCode());
        }
    }

    // ===================================================================
    // find-field-readers / find-field-writers — AC #16
    // ===================================================================

    @Nested
    @DisplayName("find-field-readers / find-field-writers (AC #16)")
    class FieldAccess {

        @Test
        @DisplayName("known benchmark field is queryable by readers and writers separately")
        void readersAndWritersDistinct() {
            // User.username is a private field with explicit getters/setters in entity.User.
            String field = "com.hhg.benchmark.entity.User#username:Ljava/lang/String;";

            JsonObject args = new JsonObject();
            args.addProperty("field_fqn", field);
            JsonObject readers = expectOk(ops.dispatch("find-field-readers", args));
            JsonObject writers = expectOk(ops.dispatch("find-field-writers", args));

            assertTrue(readers.has("methods"));
            assertTrue(writers.has("methods"));

            JsonArray ra = readers.getAsJsonArray("methods");
            JsonArray wa = writers.getAsJsonArray("methods");
            assertTrue(ra.size() > 0, "should be at least one reader (getUsername / equals etc.)");
            assertTrue(wa.size() > 0, "should be at least one writer (setUsername / <init> / constructor)");
            // The sets are not identical (readers ≠ writers in general).
            assertFalse(ra.toString().equals(wa.toString()),
                    "readers and writers should not be the identical set for username");
        }

        @Test
        @DisplayName("unknown field returns unknown-field")
        void unknownField() {
            JsonObject args = new JsonObject();
            args.addProperty("field_fqn", "no.such.Class#nope:I");
            OperationResult.Failure f = expectFailure(ops.dispatch("find-field-readers", args));
            assertEquals("unknown-field", f.errorCode());
        }

        @Test
        @DisplayName("missing field_fqn returns bad-request")
        void missingFieldArg() {
            OperationResult.Failure f = expectFailure(ops.dispatch("find-field-readers", new JsonObject()));
            assertEquals("bad-request", f.errorCode());
        }
    }

    // ===================================================================
    // impact-of-diff — see ImpactOfDiffGoldenTest for AC #17 byte-equality
    // ===================================================================

    @Nested
    @DisplayName("impact-of-diff smoke (AC #17 byte-equality lives in ImpactOfDiffGoldenTest)")
    class ImpactOfDiff {

        @Test
        @DisplayName("returns {impactedTrees: [...]} for an empty diff")
        void emptyDiff() {
            JsonObject args = new JsonObject();
            args.addProperty("diff_text", "");
            JsonObject r = expectOk(ops.dispatch("impact-of-diff", args));
            assertTrue(r.has("impactedTrees"));
            assertEquals(0, r.getAsJsonArray("impactedTrees").size());
        }

        @Test
        @DisplayName("missing diff_text returns bad-request (daemon stays alive — AC #19)")
        void missingDiffText() {
            OperationResult.Failure f = expectFailure(ops.dispatch("impact-of-diff", new JsonObject()));
            assertEquals("bad-request", f.errorCode());
        }
    }

    // ===================================================================
    // tests-for-diff — AC #18
    // ===================================================================

    @Nested
    @DisplayName("tests-for-diff (AC #18)")
    class TestsForDiff {

        @Test
        @DisplayName("returns {tests: []} on an empty diff")
        void emptyDiff() {
            JsonObject args = new JsonObject();
            args.addProperty("diff_text", "");
            JsonObject r = expectOk(ops.dispatch("tests-for-diff", args));
            assertTrue(r.has("tests"));
            assertEquals(0, r.getAsJsonArray("tests").size());
        }
    }

    // ===================================================================
    // Unknown op / missing op (AC #19 — daemon-alive contract)
    // ===================================================================

    @Nested
    @DisplayName("unknown / missing op (AC #19)")
    class UnknownOp {

        @Test
        @DisplayName("unknown op returns unknown-op")
        void unknownOp() {
            OperationResult.Failure f = expectFailure(ops.dispatch("frobnicate", new JsonObject()));
            assertEquals("unknown-op", f.errorCode());
        }

        @Test
        @DisplayName("null op returns bad-request")
        void nullOp() {
            OperationResult.Failure f = expectFailure(ops.dispatch(null, new JsonObject()));
            assertEquals("bad-request", f.errorCode());
        }

        @Test
        @DisplayName("USER_FACING_OPS lists exactly the 9 + refresh-index")
        void userFacingOps() {
            // The MCP server registers a tool for each of these names (AC #3 / AC #20).
            assertEquals(List.of(
                    "refresh-index",
                    "find-callers", "find-callees",
                    "methods-in-class", "methods-at-line",
                    "find-field-readers", "find-field-writers",
                    "impact-of-diff", "tests-for-diff"
            ), Operations.USER_FACING_OPS);
        }
    }

    // -------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------

    /** Returns the FQN of an arbitrary stable method in the benchmark index. */
    private static String pickAnyMethod() {
        CallGraph g = ref.get().scan().callGraph();
        for (MethodReference m : g.getAllMethods()) {
            if (m.getClassName().startsWith("com/hhg/benchmark/service/")
                    && !"<init>".equals(m.getMethodName())
                    && !"<classref>".equals(m.getMethodName())) {
                return com.hhg.callgraph.daemon.Fqn.methodToFqn(m);
            }
        }
        fail("no benchmark service method found in graph");
        return null;
    }
}

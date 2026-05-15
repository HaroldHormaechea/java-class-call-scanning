package com.hhg.callgraph.daemon.op;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hhg.callgraph.daemon.IndexSnapshot;
import com.hhg.callgraph.daemon.ScanMeta;
import com.hhg.callgraph.daemon.ScopeConfig;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Covers AC #18 — {@code tests-for-diff} returns a flat list of
 * {@code {fqn, type, displayName, root_change}}, with {@code type ∈ {"junit5","spock"}}
 * (lowercase wire form), one row per (test, root_change) pair.
 *
 * <p>The benchmark fixture isn't a test target itself, so we don't have tests calling
 * benchmark methods. We therefore validate the operation's behaviour primarily on (a)
 * the empty-diff baseline and (b) the response shape via a synthetic situation; the
 * end-to-end "real test discovery" path is exercised by the existing
 * {@code CallGraphIntegrationTest} fixture and the legacy pipeline path.
 */
@DisplayName("tests-for-diff (AC #18) — flat (test, root_change) rows")
class TestsForDiffTest {

    private static Operations ops;

    @BeforeAll
    static void setUp() throws IOException {
        Path benchmarkDir = Path.of(System.getProperty("user.dir"))
                .resolve("build/classes/java/test/com/hhg/benchmark");
        if (!Files.exists(benchmarkDir)) {
            fail("benchmark classes missing; run `./gradlew compileTestJava` first");
        }
        ScanResult scan = new ClassFileScanner(List.of(
                new JUnit5TestDetector(),
                new SpockTestDetector()
        )).scan(benchmarkDir);
        ScopeConfig scope = ScopeConfig.of(List.of(benchmarkDir), List.of(),
                List.of(), List.of());
        ScanMeta meta = new ScanMeta(scan.callGraph().methodCount(),
                scan.callGraph().edgeCount(), 0L, System.currentTimeMillis());
        AtomicReference<IndexSnapshot> ref =
                new AtomicReference<>(new IndexSnapshot(scan, scope, meta));
        ops = new Operations(ref::get, () -> new JsonObject());
    }

    @Test
    @DisplayName("empty diff produces empty tests array")
    void emptyDiff() {
        JsonObject args = new JsonObject();
        args.addProperty("diff_text", "");
        OperationResult r = ops.dispatch("tests-for-diff", args);
        assertTrue(r instanceof OperationResult.Success, "expected Success: " + r);
        JsonObject result = ((OperationResult.Success) r).result();
        JsonArray tests = result.getAsJsonArray("tests");
        assertEquals(0, tests.size());
    }

    @Test
    @DisplayName("missing diff_text arg produces bad-request (daemon stays alive)")
    void missingDiffText() {
        OperationResult r = ops.dispatch("tests-for-diff", new JsonObject());
        assertTrue(r instanceof OperationResult.Failure);
        assertEquals("bad-request", ((OperationResult.Failure) r).errorCode());
    }

    @Test
    @DisplayName("row shape — every entry has {fqn, type, displayName, root_change}")
    void rowShapeIfAnyRows() {
        // Drive a synthetic diff that touches every benchmark file; even if no test method
        // is reachable, the operation should still return a well-formed `tests` array.
        // For any rows that DO appear we check the shape contract.
        String diff = ""
                + "--- a/com/hhg/benchmark/service/UserService.java\n"
                + "+++ b/com/hhg/benchmark/service/UserService.java\n"
                + "@@ -22,1 +22,1 @@\n"
                + "-    @Transactional\n"
                + "+    @Transactional // touched\n";
        JsonObject args = new JsonObject();
        args.addProperty("diff_text", diff);
        OperationResult r = ops.dispatch("tests-for-diff", args);
        assertTrue(r instanceof OperationResult.Success, "expected Success: " + r);
        JsonArray tests = ((OperationResult.Success) r).result().getAsJsonArray("tests");

        Set<String> allowedTypes = new HashSet<>(List.of("junit5", "spock"));
        for (JsonElement el : tests) {
            JsonObject row = el.getAsJsonObject();
            assertTrue(row.has("fqn"),        "row missing 'fqn': "        + row);
            assertTrue(row.has("type"),       "row missing 'type': "       + row);
            assertTrue(row.has("displayName"),"row missing 'displayName': "+ row);
            assertTrue(row.has("root_change"),"row missing 'root_change': "+ row);
            assertTrue(allowedTypes.contains(row.get("type").getAsString()),
                    "type must be one of {junit5, spock} — got " + row.get("type"));
            // root_change FQN must be in JVM-descriptor form (must contain '#' and '(').
            String rc = row.get("root_change").getAsString();
            assertTrue(rc.contains("#") && rc.contains("("),
                    "root_change must be JVM-descriptor FQN — got " + rc);
        }
    }
}

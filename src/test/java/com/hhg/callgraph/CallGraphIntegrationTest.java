package com.hhg.callgraph;

import com.hhg.callgraph.model.CallGraph;
import com.hhg.callgraph.model.MethodReference;
import com.hhg.callgraph.scanner.ClassFileScanner;
import com.hhg.callgraph.scanner.ScanResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Integration: Full call chain traversal")
class CallGraphIntegrationTest {

    private static ScanResult scanResult;
    private static CallGraph graph;

    private static final MethodReference TC1_METHOD = new MethodReference(
            "com/hhg/main/targets/TargetClass1", "methodInClass1", "()V");
    private static final MethodReference TC2_METHOD = new MethodReference(
            "com/hhg/main/targets/TargetClass2", "methodInClass2", "()V");
    private static final MethodReference TC3_METHOD = new MethodReference(
            "com/hhg/main/targets/TargetClass3", "methodInClass3", "()V");

    @BeforeAll
    static void buildGraph() throws IOException {
        Path targetsDir = Path.of(System.getProperty("user.dir"))
                .resolve("build/classes/java/test/com/hhg/main/targets");

        if (!Files.exists(targetsDir)) {
            fail("Target classes not compiled. Run './gradlew build' first.");
        }

        scanResult = new ClassFileScanner().scan(targetsDir);
        graph = scanResult.callGraph();
    }

    @Test
    @DisplayName("Transitive callees of TC1.methodInClass1 includes TC2, TC3, and println")
    void transitiveCalleesOfTC1() {
        Set<MethodReference> transitiveCallees = graph.getAllTransitiveCallees(TC1_METHOD);

        assertTrue(transitiveCallees.contains(TC2_METHOD),
                "TC1 transitive callees should include TC2.methodInClass2");

        assertTrue(transitiveCallees.contains(TC3_METHOD),
                "TC1 transitive callees should include TC3.methodInClass3 (via TC2)");

        boolean hasPrintln = transitiveCallees.stream().anyMatch(
                ref -> ref.getMethodName().equals("println"));
        assertTrue(hasPrintln,
                "TC1 transitive callees should include println (via TC2 -> TC3)");
    }

    @Test
    @DisplayName("Transitive callers of TC3.methodInClass3 includes TC2 and TC1")
    void transitiveCallersOfTC3() {
        Set<MethodReference> transitiveCallers = graph.getAllTransitiveCallers(TC3_METHOD);

        assertTrue(transitiveCallers.contains(TC2_METHOD),
                "TC3 transitive callers should include TC2.methodInClass2");

        assertTrue(transitiveCallers.contains(TC1_METHOD),
                "TC3 transitive callers should include TC1.methodInClass1 (via TC2)");
    }

    @Test
    @DisplayName("TC1.methodInClass1 -> TC2.methodInClass2 is a direct call")
    void directCallTC1toTC2() {
        Set<MethodReference> directCallees = graph.getCalleesOf(TC1_METHOD);

        assertTrue(directCallees.contains(TC2_METHOD),
                "TC1.methodInClass1 should directly call TC2.methodInClass2");
    }

    @Test
    @DisplayName("TC2.methodInClass2 -> TC3.methodInClass3 is a direct call")
    void directCallTC2toTC3() {
        Set<MethodReference> directCallees = graph.getCalleesOf(TC2_METHOD);

        assertTrue(directCallees.contains(TC3_METHOD),
                "TC2.methodInClass2 should directly call TC3.methodInClass3");
    }

    @Test
    @DisplayName("TC3.methodInClass3 is not a transitive callee of itself")
    void tc3NotTransitiveCalleeOfItself() {
        Set<MethodReference> transitiveCallees = graph.getAllTransitiveCallees(TC3_METHOD);

        assertFalse(transitiveCallees.contains(TC3_METHOD),
                "TC3.methodInClass3 should not appear in its own transitive callees (no cycle)");
    }

    @Test
    @DisplayName("Full chain connectivity: from TC1 we can reach println transitively")
    void fullChainConnectivity() {
        Set<MethodReference> fromTC1 = graph.getAllTransitiveCallees(TC1_METHOD);
        boolean reachesPrintln = fromTC1.stream().anyMatch(
                ref -> ref.getMethodName().equals("println"));
        assertTrue(reachesPrintln, "TC1 should transitively reach println");

        MethodReference printlnRef = fromTC1.stream()
                .filter(ref -> ref.getMethodName().equals("println"))
                .findFirst()
                .orElse(null);
        assertNotNull(printlnRef, "println not found in transitive callees of TC1");

        Set<MethodReference> callerChain = graph.getAllTransitiveCallers(printlnRef);
        assertTrue(callerChain.contains(TC3_METHOD), "println transitive callers should include TC3");
        assertTrue(callerChain.contains(TC2_METHOD), "println transitive callers should include TC2");
        assertTrue(callerChain.contains(TC1_METHOD), "println transitive callers should include TC1");
    }

    @Test
    @DisplayName("Constructor invocations are captured in the graph")
    void constructorInvocationsPresent() {
        MethodReference tc1Init = new MethodReference(
                "com/hhg/main/targets/TargetClass1", "<init>", "()V");

        Set<MethodReference> initCallees = graph.getCalleesOf(tc1Init);

        boolean callsTC2Init = initCallees.stream().anyMatch(
                ref -> ref.getClassName().equals("com/hhg/main/targets/TargetClass2")
                        && ref.getMethodName().equals("<init>"));

        assertTrue(callsTC2Init,
                "TC1.<init> should call TC2.<init> via field initializer");
    }

    @Test
    @DisplayName("Graph contains both direct and JDK method calls")
    void graphContainsJDKCalls() {
        Set<MethodReference> tc3Callees = graph.getCalleesOf(TC3_METHOD);

        boolean hasJDKCall = tc3Callees.stream().anyMatch(
                ref -> ref.getClassName().startsWith("java/"));

        assertTrue(hasJDKCall,
                "Graph should include JDK method calls (e.g., PrintStream.println)");
    }

    @Test
    @DisplayName("sourceIndex has non-unknown location for TC1, TC2, TC3 public methods")
    void sourceIndexHasKnownLocations() {
        assertFalse(scanResult.sourceIndex().getLocation(TC1_METHOD).map(l -> l.isUnknown()).orElse(true),
                "TC1.methodInClass1 should have known source location");
        assertFalse(scanResult.sourceIndex().getLocation(TC2_METHOD).map(l -> l.isUnknown()).orElse(true),
                "TC2.methodInClass2 should have known source location");
        assertFalse(scanResult.sourceIndex().getLocation(TC3_METHOD).map(l -> l.isUnknown()).orElse(true),
                "TC3.methodInClass3 should have known source location");
    }

    @Test
    @DisplayName("findMethodsAt('TargetClass3.java', 6) returns set containing methodInClass3")
    void findMethodsAtTC3Line6() {
        Set<MethodReference> found = scanResult.sourceIndex().findMethodsAt("TargetClass3.java", 6);

        assertTrue(found.contains(TC3_METHOD),
                "findMethodsAt should find TC3.methodInClass3 at line 6. Found: " + found);
    }
}

package com.hhg.callgraph.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CallGraphTest {

    private CallGraph graph;

    // Reusable method references for tests
    private final MethodReference methodA = new MethodReference("com/example/A", "doA", "()V");
    private final MethodReference methodB = new MethodReference("com/example/B", "doB", "()V");
    private final MethodReference methodC = new MethodReference("com/example/C", "doC", "()V");
    private final MethodReference methodD = new MethodReference("com/example/D", "doD", "()V");
    private final MethodReference methodE = new MethodReference("com/example/E", "doE", "()V");

    @BeforeEach
    void setUp() {
        graph = new CallGraph();
    }

    @Nested
    @DisplayName("Empty graph")
    class EmptyGraph {

        @Test
        @DisplayName("New graph has zero methods and zero edges")
        void emptyGraphCounts() {
            assertEquals(0, graph.methodCount());
            assertEquals(0, graph.edgeCount());
        }

        @Test
        @DisplayName("getAllMethods returns empty set on empty graph")
        void emptyGraphGetAllMethods() {
            assertTrue(graph.getAllMethods().isEmpty());
        }

        @Test
        @DisplayName("getCalleesOf returns empty set for unknown method")
        void calleesOfUnknownMethodReturnsEmptySet() {
            Set<MethodReference> result = graph.getCalleesOf(methodA);
            assertNotNull(result, "Should return empty set, not null");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("getCallersOf returns empty set for unknown method")
        void callersOfUnknownMethodReturnsEmptySet() {
            Set<MethodReference> result = graph.getCallersOf(methodA);
            assertNotNull(result, "Should return empty set, not null");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("getAllTransitiveCallees returns empty set for unknown method")
        void transitiveCalleesOfUnknownMethodReturnsEmptySet() {
            Set<MethodReference> result = graph.getAllTransitiveCallees(methodA);
            assertNotNull(result, "Should return empty set, not null");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("getAllTransitiveCallers returns empty set for unknown method")
        void transitiveCallersOfUnknownMethodReturnsEmptySet() {
            Set<MethodReference> result = graph.getAllTransitiveCallers(methodA);
            assertNotNull(result, "Should return empty set, not null");
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("addCall and direct queries")
    class AddCallAndDirectQueries {

        @Test
        @DisplayName("Single edge: caller has one callee, callee has one caller")
        void singleEdge() {
            graph.addCall(methodA, methodB);

            assertEquals(Set.of(methodB), graph.getCalleesOf(methodA));
            assertEquals(Set.of(methodA), graph.getCallersOf(methodB));
        }

        @Test
        @DisplayName("Single edge registers both methods")
        void singleEdgeRegistersNodes() {
            graph.addCall(methodA, methodB);

            assertEquals(2, graph.methodCount());
            assertEquals(1, graph.edgeCount());
            assertTrue(graph.getAllMethods().containsAll(Set.of(methodA, methodB)));
        }

        @Test
        @DisplayName("Adding same edge twice does not create duplicates")
        void duplicateEdgeIgnored() {
            graph.addCall(methodA, methodB);
            graph.addCall(methodA, methodB);

            assertEquals(1, graph.edgeCount());
            assertEquals(1, graph.getCalleesOf(methodA).size());
        }

        @Test
        @DisplayName("One caller, multiple callees")
        void oneCallerMultipleCallees() {
            graph.addCall(methodA, methodB);
            graph.addCall(methodA, methodC);
            graph.addCall(methodA, methodD);

            assertEquals(Set.of(methodB, methodC, methodD), graph.getCalleesOf(methodA));
            assertEquals(3, graph.edgeCount());
        }

        @Test
        @DisplayName("Multiple callers, one callee")
        void multipleCallersOneCallee() {
            graph.addCall(methodA, methodD);
            graph.addCall(methodB, methodD);
            graph.addCall(methodC, methodD);

            assertEquals(Set.of(methodA, methodB, methodC), graph.getCallersOf(methodD));
            assertEquals(3, graph.edgeCount());
        }

        @Test
        @DisplayName("Method with no outgoing calls has empty callees set")
        void leafMethodHasNoCallees() {
            graph.addCall(methodA, methodB);

            assertTrue(graph.getCalleesOf(methodB).isEmpty());
        }

        @Test
        @DisplayName("Method with no incoming calls has empty callers set")
        void rootMethodHasNoCallers() {
            graph.addCall(methodA, methodB);

            assertTrue(graph.getCallersOf(methodA).isEmpty());
        }

        @Test
        @DisplayName("methodCount counts all unique methods")
        void methodCountAccuracy() {
            graph.addCall(methodA, methodB);
            graph.addCall(methodB, methodC);
            graph.addCall(methodA, methodC);

            assertEquals(3, graph.methodCount());
        }

        @Test
        @DisplayName("edgeCount counts all unique edges")
        void edgeCountAccuracy() {
            graph.addCall(methodA, methodB);
            graph.addCall(methodB, methodC);
            graph.addCall(methodA, methodC);

            assertEquals(3, graph.edgeCount());
        }

        @Test
        @DisplayName("Self-call: method calls itself")
        void selfCall() {
            graph.addCall(methodA, methodA);

            assertEquals(Set.of(methodA), graph.getCalleesOf(methodA));
            assertEquals(Set.of(methodA), graph.getCallersOf(methodA));
            assertEquals(1, graph.methodCount());
            assertEquals(1, graph.edgeCount());
        }
    }

    @Nested
    @DisplayName("Transitive traversal")
    class TransitiveTraversal {

        @Test
        @DisplayName("Linear chain A -> B -> C: transitive callees of A includes B and C")
        void linearChainTransitiveCallees() {
            graph.addCall(methodA, methodB);
            graph.addCall(methodB, methodC);

            Set<MethodReference> transitiveCallees = graph.getAllTransitiveCallees(methodA);

            assertTrue(transitiveCallees.contains(methodB));
            assertTrue(transitiveCallees.contains(methodC));
        }

        @Test
        @DisplayName("Linear chain A -> B -> C: transitive callers of C includes B and A")
        void linearChainTransitiveCallers() {
            graph.addCall(methodA, methodB);
            graph.addCall(methodB, methodC);

            Set<MethodReference> transitiveCallers = graph.getAllTransitiveCallers(methodC);

            assertTrue(transitiveCallers.contains(methodB));
            assertTrue(transitiveCallers.contains(methodA));
        }

        @Test
        @DisplayName("Leaf method has no transitive callees")
        void leafMethodNoTransitiveCallees() {
            graph.addCall(methodA, methodB);

            assertTrue(graph.getAllTransitiveCallees(methodB).isEmpty());
        }

        @Test
        @DisplayName("Root method has no transitive callers")
        void rootMethodNoTransitiveCallers() {
            graph.addCall(methodA, methodB);

            assertTrue(graph.getAllTransitiveCallers(methodA).isEmpty());
        }

        @Test
        @DisplayName("Diamond shape: A -> B, A -> C, B -> D, C -> D")
        void diamondTransitiveCallees() {
            graph.addCall(methodA, methodB);
            graph.addCall(methodA, methodC);
            graph.addCall(methodB, methodD);
            graph.addCall(methodC, methodD);

            Set<MethodReference> transitiveCallees = graph.getAllTransitiveCallees(methodA);

            assertEquals(Set.of(methodB, methodC, methodD), transitiveCallees);
        }

        @Test
        @DisplayName("Diamond shape: transitive callers of D includes A, B, and C")
        void diamondTransitiveCallers() {
            graph.addCall(methodA, methodB);
            graph.addCall(methodA, methodC);
            graph.addCall(methodB, methodD);
            graph.addCall(methodC, methodD);

            Set<MethodReference> transitiveCallers = graph.getAllTransitiveCallers(methodD);

            assertEquals(Set.of(methodA, methodB, methodC), transitiveCallers);
        }

        @Test
        @DisplayName("Long chain: A -> B -> C -> D -> E transitive callees")
        void longChainTransitiveCallees() {
            graph.addCall(methodA, methodB);
            graph.addCall(methodB, methodC);
            graph.addCall(methodC, methodD);
            graph.addCall(methodD, methodE);

            Set<MethodReference> result = graph.getAllTransitiveCallees(methodA);

            assertEquals(Set.of(methodB, methodC, methodD, methodE), result);
        }

        @Test
        @DisplayName("Long chain: A -> B -> C -> D -> E transitive callers of E")
        void longChainTransitiveCallers() {
            graph.addCall(methodA, methodB);
            graph.addCall(methodB, methodC);
            graph.addCall(methodC, methodD);
            graph.addCall(methodD, methodE);

            Set<MethodReference> result = graph.getAllTransitiveCallers(methodE);

            assertEquals(Set.of(methodA, methodB, methodC, methodD), result);
        }
    }

    @Nested
    @DisplayName("Cycle handling")
    class CycleHandling {

        @Test
        @DisplayName("Direct cycle A -> B -> A: transitive callees terminates")
        void directCycleTransitiveCalleesTerminates() {
            graph.addCall(methodA, methodB);
            graph.addCall(methodB, methodA);

            // Must not infinite loop; should return the other node(s) in the cycle
            Set<MethodReference> result = graph.getAllTransitiveCallees(methodA);

            assertTrue(result.contains(methodB));
            // A is reachable via the cycle, so it may or may not be included
            // depending on implementation -- but it MUST terminate
        }

        @Test
        @DisplayName("Direct cycle A -> B -> A: transitive callers terminates")
        void directCycleTransitiveCallersTerminates() {
            graph.addCall(methodA, methodB);
            graph.addCall(methodB, methodA);

            Set<MethodReference> result = graph.getAllTransitiveCallers(methodA);

            assertTrue(result.contains(methodB));
        }

        @Test
        @DisplayName("Indirect cycle A -> B -> C -> A: terminates and returns all nodes")
        void indirectCycleTerminates() {
            graph.addCall(methodA, methodB);
            graph.addCall(methodB, methodC);
            graph.addCall(methodC, methodA);

            Set<MethodReference> calleesOfA = graph.getAllTransitiveCallees(methodA);

            assertTrue(calleesOfA.contains(methodB));
            assertTrue(calleesOfA.contains(methodC));
        }

        @Test
        @DisplayName("Self-loop: A -> A transitive callees terminates and excludes start node")
        void selfLoopTerminates() {
            graph.addCall(methodA, methodA);

            // Must terminate; the implementation removes the start node from results
            Set<MethodReference> result = graph.getAllTransitiveCallees(methodA);
            assertNotNull(result);
            assertTrue(result.isEmpty(),
                    "Self-loop: transitive callees should be empty since start node is excluded from results");
        }

        @Test
        @DisplayName("Cycle with tail: D -> A -> B -> C -> A, transitive callees of D includes all")
        void cycleWithTail() {
            graph.addCall(methodD, methodA);
            graph.addCall(methodA, methodB);
            graph.addCall(methodB, methodC);
            graph.addCall(methodC, methodA);

            Set<MethodReference> result = graph.getAllTransitiveCallees(methodD);

            assertTrue(result.contains(methodA));
            assertTrue(result.contains(methodB));
            assertTrue(result.contains(methodC));
        }
    }

    @Nested
    @DisplayName("getAllMethods")
    class GetAllMethods {

        @Test
        @DisplayName("Returns all unique methods from both sides of edges")
        void returnsAllMethods() {
            graph.addCall(methodA, methodB);
            graph.addCall(methodC, methodD);

            Set<MethodReference> all = graph.getAllMethods();

            assertEquals(4, all.size());
            assertTrue(all.containsAll(Set.of(methodA, methodB, methodC, methodD)));
        }

        @Test
        @DisplayName("Method appearing as both caller and callee is counted once")
        void noDuplicateNodes() {
            graph.addCall(methodA, methodB);
            graph.addCall(methodB, methodC);

            assertEquals(3, graph.methodCount());
        }
    }
}

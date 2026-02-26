package com.hhg.callgraph.output;

import com.hhg.callgraph.model.CallGraph;
import com.hhg.callgraph.model.MethodReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CallTreePrinter")
class CallTreePrinterTest {

    private CallGraph graph;
    private ByteArrayOutputStream outputBuffer;
    private PrintStream printStream;
    private CallTreePrinter printer;

    private final MethodReference methodA = new MethodReference("com/example/A", "doA", "()V");
    private final MethodReference methodB = new MethodReference("com/example/B", "doB", "()V");
    private final MethodReference methodC = new MethodReference("com/example/C", "doC", "()V");
    private final MethodReference methodD = new MethodReference("com/example/D", "doD", "()V");

    @BeforeEach
    void setUp() {
        graph = new CallGraph();
        outputBuffer = new ByteArrayOutputStream();
        printStream = new PrintStream(outputBuffer);
        printer = new CallTreePrinter(graph, printStream);
    }

    private String getOutput() {
        printStream.flush();
        return outputBuffer.toString();
    }

    @Nested
    @DisplayName("printCalleeTree")
    class PrintCalleeTree {

        @Test
        @DisplayName("Root with no callees prints just the root line")
        void rootWithNoCallees() {
            graph.addCall(methodA, methodB);

            printer.printCalleeTree(methodB, 5);

            String output = getOutput();
            String[] lines = output.trim().split("\\R");
            assertEquals(1, lines.length, "Should have exactly one line for root with no callees");
            assertTrue(lines[0].contains("doB"), "Root line should contain method name");
        }

        @Test
        @DisplayName("Linear chain A -> B -> C -> D prints all four nodes at depth 5")
        void linearChainPrintsAllNodes() {
            graph.addCall(methodA, methodB);
            graph.addCall(methodB, methodC);
            graph.addCall(methodC, methodD);

            printer.printCalleeTree(methodA, 5);

            String output = getOutput();
            String[] lines = output.trim().split("\\R");
            assertEquals(4, lines.length, "Should have 4 lines for chain A->B->C->D");
            assertTrue(lines[0].contains("doA"), "First line is root A");
            assertTrue(lines[1].contains("doB"), "Second line is B");
            assertTrue(lines[2].contains("doC"), "Third line is C");
            assertTrue(lines[3].contains("doD"), "Fourth line is D");
        }

        @Test
        @DisplayName("Depth limit 1 stops after root's direct callees")
        void depthLimit1() {
            graph.addCall(methodA, methodB);
            graph.addCall(methodB, methodC);
            graph.addCall(methodC, methodD);

            printer.printCalleeTree(methodA, 1);

            String output = getOutput();
            String[] lines = output.trim().split("\\R");
            assertEquals(2, lines.length, "Depth 1: root + one level of callees");
            assertTrue(lines[0].contains("doA"), "First line is root");
            assertTrue(lines[1].contains("doB"), "Second line is direct callee B");
        }

        @Test
        @DisplayName("Single child at each level uses └── connector with increasing indent")
        void singleChildConnectors() {
            graph.addCall(methodA, methodB);
            graph.addCall(methodB, methodC);

            printer.printCalleeTree(methodA, 5);

            String output = getOutput();
            String[] lines = output.trim().split("\\R");
            assertEquals(3, lines.length);

            assertFalse(lines[0].startsWith(" "), "Root has no connector");
            assertTrue(lines[1].startsWith("\\-- "), "Only child at depth 1 uses \\--");
            assertTrue(lines[2].startsWith("    \\-- "), "Only child at depth 2 uses \\-- with 4-space indent");
        }

        @Test
        @DisplayName("Multiple children: ├── for non-last entries, └── for last")
        void multipleChildrenConnectors() {
            // A calls both B and C; B sorts before C alphabetically
            graph.addCall(methodA, methodB);
            graph.addCall(methodA, methodC);

            printer.printCalleeTree(methodA, 5);

            String[] lines = getOutput().trim().split("\\R");
            assertEquals(3, lines.length);
            assertTrue(lines[1].startsWith("+-- "), "Non-last child uses +--");
            assertTrue(lines[2].startsWith("\\-- "), "Last child uses \\--");
        }

        @Test
        @DisplayName("Grandchild indent respects whether its parent was the last child")
        void grandchildIndentUnderNonLastParent() {
            // A -> B, A -> C; B -> D  (B is non-last, so D should be prefixed with │   )
            graph.addCall(methodA, methodB);
            graph.addCall(methodA, methodC);
            graph.addCall(methodB, methodD);

            printer.printCalleeTree(methodA, 5);

            String[] lines = getOutput().trim().split("\\R");
            // Expected order: A, B (├──), D (│   └──), C (└──)
            assertEquals(4, lines.length);
            assertTrue(lines[1].startsWith("+-- ") && lines[1].contains("doB"), "B uses +--");
            assertTrue(lines[2].startsWith("|   \\-- ") && lines[2].contains("doD"), "D under B gets |   prefix");
            assertTrue(lines[3].startsWith("\\-- ") && lines[3].contains("doC"), "C (last) uses \\--");
        }
    }

    @Nested
    @DisplayName("printCallerTree")
    class PrintCallerTree {

        @Test
        @DisplayName("Root with no callers prints just the root line")
        void rootWithNoCallers() {
            graph.addCall(methodA, methodB);

            printer.printCallerTree(methodA, 5);

            String output = getOutput();
            String[] lines = output.trim().split("\\R");
            assertEquals(1, lines.length, "Should have exactly one line for root with no callers");
            assertTrue(lines[0].contains("doA"), "Root line should contain method name");
        }

        @Test
        @DisplayName("Caller chain: printing callers of D where A->B->C->D")
        void callerChain() {
            graph.addCall(methodA, methodB);
            graph.addCall(methodB, methodC);
            graph.addCall(methodC, methodD);

            printer.printCallerTree(methodD, 5);

            String output = getOutput();
            String[] lines = output.trim().split("\\R");
            assertEquals(4, lines.length, "Should print D, then C, B, A as callers");
            assertTrue(lines[0].contains("doD"), "Root is D");
        }
    }

    @Nested
    @DisplayName("Cycle safety")
    class CycleSafety {

        @Test
        @DisplayName("A -> B -> A cycle does not loop forever")
        void cycleSafe() {
            graph.addCall(methodA, methodB);
            graph.addCall(methodB, methodA);

            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
                printer.printCalleeTree(methodA, 10);
            }, "Cycle A -> B -> A should not cause infinite loop");

            String output = getOutput();
            assertFalse(output.isEmpty(), "Should produce some output for cycle");
        }

        @Test
        @DisplayName("Self-loop A -> A does not loop forever")
        void selfLoopSafe() {
            graph.addCall(methodA, methodA);

            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
                printer.printCalleeTree(methodA, 10);
            }, "Self-loop A -> A should not cause infinite loop");
        }
    }
}

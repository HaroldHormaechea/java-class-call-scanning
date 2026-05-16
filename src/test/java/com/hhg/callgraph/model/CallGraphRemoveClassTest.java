package com.hhg.callgraph.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * UC04 incremental-rebuild support — {@link CallGraph#removeClass(String)} is the
 * primitive the {@link com.hhg.callgraph.daemon.watch.IncrementalRebuilder} uses to
 * subtract a class's contributions from a draft snapshot before merging the freshly
 * re-scanned bytecode back in.
 *
 * <p>Contract under test:
 * <ul>
 *   <li>Every <em>outgoing</em> edge whose caller class matches is removed.</li>
 *   <li>Edges where only the callee class matches are intentionally retained — the
 *       documented incremental-build trade-off; they get cleaned up the next time the
 *       caller class itself is re-scanned.</li>
 *   <li>{@code null} input is a no-op (never throws).</li>
 *   <li>An unknown class name is a no-op.</li>
 *   <li>{@link CallGraph#copy()} produces an independent graph — mutating the clone
 *       does not affect the original. Required by the incremental rebuilder's
 *       draft-snapshot pattern.</li>
 * </ul>
 */
@DisplayName("CallGraph.removeClass — UC04 incremental-rebuild primitive")
class CallGraphRemoveClassTest {

    private final MethodReference a1 = new MethodReference("com/example/A", "doA1", "()V");
    private final MethodReference a2 = new MethodReference("com/example/A", "doA2", "()V");
    private final MethodReference b1 = new MethodReference("com/example/B", "doB1", "()V");
    private final MethodReference c1 = new MethodReference("com/example/C", "doC1", "()V");

    @Test
    @DisplayName("removes every outgoing edge whose caller class matches")
    void outgoingEdgesPurged() {
        CallGraph g = new CallGraph();
        g.addCall(a1, b1);
        g.addCall(a2, c1);
        g.addCall(b1, c1);

        g.removeClass("com/example/A");

        assertFalse(g.getAllMethods().contains(a1),
                "a1 should no longer have any edges (and therefore no longer be a node)");
        assertFalse(g.getAllMethods().contains(a2),
                "a2 should no longer have any edges (and therefore no longer be a node)");
        assertTrue(g.getAllMethods().contains(b1));
        assertTrue(g.getAllMethods().contains(c1));
        assertEquals(Set.of(c1), g.getCalleesOf(b1));
        assertEquals(Set.of(), g.getCallersOf(b1),
                "b1's prior caller (a1) is gone, leaving b1 with no callers");
    }

    @Test
    @DisplayName("keeps edges where only the callee class matches (documented trade-off)")
    void incomingEdgesPreserved() {
        // B calls A — we remove class A. B's call to A is retained per CallGraph's
        // Javadoc: cleanup happens the next time B itself is re-scanned.
        CallGraph g = new CallGraph();
        g.addCall(b1, a1);   // b1 -> a1 (callee in class A)
        g.addCall(b1, c1);

        g.removeClass("com/example/A");

        assertEquals(Set.of(a1, c1), g.getCalleesOf(b1),
                "Edge into A's a1 is retained — only outgoing-from-A edges are purged");
    }

    @Test
    @DisplayName("null class name is a no-op and does not throw")
    void nullIsNoOp() {
        CallGraph g = new CallGraph();
        g.addCall(a1, b1);
        assertDoesNotThrow(() -> g.removeClass(null));
        assertEquals(1, g.edgeCount());
    }

    @Test
    @DisplayName("unknown class name is a no-op")
    void unknownClassIsNoOp() {
        CallGraph g = new CallGraph();
        g.addCall(a1, b1);
        g.removeClass("com/nope/Nope");
        assertEquals(1, g.edgeCount());
        assertTrue(g.getAllMethods().contains(a1));
        assertTrue(g.getAllMethods().contains(b1));
    }

    @Test
    @DisplayName("copy() is a deep copy — mutating the clone does not affect the original")
    void copyIsDeep() {
        CallGraph original = new CallGraph();
        original.addCall(a1, b1);
        original.addCall(a2, c1);

        CallGraph clone = original.copy();
        clone.removeClass("com/example/A");

        // Original retains all edges; the clone has them purged.
        assertEquals(2, original.edgeCount(), "original must be untouched after clone mutation");
        assertEquals(0, clone.edgeCount());
    }

    @Test
    @DisplayName("removeClass on an empty graph is a no-op (degenerate base case)")
    void emptyGraphNoOp() {
        CallGraph g = new CallGraph();
        assertDoesNotThrow(() -> g.removeClass("com/example/A"));
        assertEquals(0, g.methodCount());
        assertEquals(0, g.edgeCount());
    }
}

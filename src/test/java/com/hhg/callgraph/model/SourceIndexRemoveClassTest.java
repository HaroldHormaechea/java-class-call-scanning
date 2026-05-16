package com.hhg.callgraph.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UC04 incremental-rebuild support — {@link SourceIndex#removeClass(String)} purges
 * every {@link SourceLocation} whose owning method belongs to the given internal class.
 * The incremental rebuilder uses this to wipe the prior class's contributions before
 * the new bytecode is scanned in.
 */
@DisplayName("SourceIndex.removeClass — UC04 incremental-rebuild primitive")
class SourceIndexRemoveClassTest {

    private final MethodReference a1 =
            new MethodReference("com/example/A", "doA1", "()V");
    private final MethodReference a2 =
            new MethodReference("com/example/A", "doA2", "()V");
    private final MethodReference b1 =
            new MethodReference("com/example/B", "doB1", "()V");

    @Test
    @DisplayName("removes every method-location entry whose method belongs to the class")
    void purgesMatchingMethods() {
        SourceIndex si = new SourceIndex();
        si.add(a1, new SourceLocation("A.java", 10, 20));
        si.add(a2, new SourceLocation("A.java", 22, 30));
        si.add(b1, new SourceLocation("B.java", 5, 15));

        si.removeClass("com/example/A");

        assertTrue(si.getLocation(a1).isEmpty(), "a1 location should be removed");
        assertTrue(si.getLocation(a2).isEmpty(), "a2 location should be removed");
        assertTrue(si.getLocation(b1).isPresent(), "b1 location should be retained");
        assertEquals(1, si.size());
    }

    @Test
    @DisplayName("null class name is a no-op")
    void nullIsNoOp() {
        SourceIndex si = new SourceIndex();
        si.add(a1, new SourceLocation("A.java", 1, 2));
        assertDoesNotThrow(() -> si.removeClass(null));
        assertEquals(1, si.size());
    }

    @Test
    @DisplayName("unknown class is a no-op")
    void unknownClassIsNoOp() {
        SourceIndex si = new SourceIndex();
        si.add(a1, new SourceLocation("A.java", 1, 2));
        si.removeClass("com/nope/Nope");
        assertEquals(1, si.size());
    }

    @Test
    @DisplayName("copy() is a deep copy — mutating the clone does not affect the original")
    void copyIsDeep() {
        SourceIndex original = new SourceIndex();
        original.add(a1, new SourceLocation("A.java", 10, 20));
        original.add(b1, new SourceLocation("B.java", 5, 15));

        SourceIndex clone = original.copy();
        clone.removeClass("com/example/A");

        assertEquals(2, original.size(), "original must be untouched after clone mutation");
        assertEquals(1, clone.size());
        assertTrue(original.getLocation(a1).isPresent());
        assertFalse(clone.getLocation(a1).isPresent());
    }
}

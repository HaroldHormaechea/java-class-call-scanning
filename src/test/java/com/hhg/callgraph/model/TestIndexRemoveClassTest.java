package com.hhg.callgraph.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UC04 incremental-rebuild support — {@link TestIndex#removeClass(String)} purges every
 * registered test method whose owner class matches. Used by the incremental rebuilder
 * to drop a class's prior test contributions before its newly-scanned bytecode is
 * merged back in.
 */
@DisplayName("TestIndex.removeClass — UC04 incremental-rebuild primitive")
class TestIndexRemoveClassTest {

    private final MethodReference testA1 =
            new MethodReference("com/example/ATest", "shouldFoo", "()V");
    private final MethodReference testA2 =
            new MethodReference("com/example/ATest", "shouldBar", "()V");
    private final MethodReference testB =
            new MethodReference("com/example/BTest", "shouldBaz", "()V");

    @Test
    @DisplayName("removes every test whose method belongs to the class")
    void removesMatching() {
        TestIndex ti = new TestIndex();
        ti.add(new TestDescriptor(testA1, "JUnit5", "shouldFoo"));
        ti.add(new TestDescriptor(testA2, "JUnit5", "shouldBar"));
        ti.add(new TestDescriptor(testB, "JUnit5", "shouldBaz"));

        ti.removeClass("com/example/ATest");

        assertFalse(ti.isTestMethod(testA1));
        assertFalse(ti.isTestMethod(testA2));
        assertTrue(ti.isTestMethod(testB), "BTest entry must be retained");
        assertEquals(1, ti.size());
    }

    @Test
    @DisplayName("null class name is a no-op")
    void nullIsNoOp() {
        TestIndex ti = new TestIndex();
        ti.add(new TestDescriptor(testA1, "JUnit5", "shouldFoo"));
        assertDoesNotThrow(() -> ti.removeClass(null));
        assertEquals(1, ti.size());
    }

    @Test
    @DisplayName("unknown class is a no-op")
    void unknownClassIsNoOp() {
        TestIndex ti = new TestIndex();
        ti.add(new TestDescriptor(testA1, "JUnit5", "shouldFoo"));
        ti.removeClass("com/nope/Nope");
        assertEquals(1, ti.size());
    }

    @Test
    @DisplayName("copy() is a deep copy — mutating the clone does not affect the original")
    void copyIsDeep() {
        TestIndex original = new TestIndex();
        original.add(new TestDescriptor(testA1, "JUnit5", "shouldFoo"));
        original.add(new TestDescriptor(testB, "JUnit5", "shouldBaz"));

        TestIndex clone = original.copy();
        clone.removeClass("com/example/ATest");

        assertEquals(2, original.size(), "original must be untouched after clone mutation");
        assertEquals(1, clone.size());
        assertTrue(original.isTestMethod(testA1));
        assertFalse(clone.isTestMethod(testA1));
    }
}

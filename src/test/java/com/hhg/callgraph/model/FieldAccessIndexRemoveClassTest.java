package com.hhg.callgraph.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UC04 incremental-rebuild support — {@link FieldAccessIndex#removeClass(String)} drops:
 * <ol>
 *   <li>Every field whose owner class matches.</li>
 *   <li>From the surviving fields, every accessor method that lives in the removed class.</li>
 * </ol>
 *
 * <p>Field-set entries that become empty after step 2 are pruned to keep
 * {@code getAllFields()} consistent.
 */
@DisplayName("FieldAccessIndex.removeClass — UC04 incremental-rebuild primitive")
class FieldAccessIndexRemoveClassTest {

    private final FieldReference fieldOnA =
            new FieldReference("com/example/A", "x", "I");
    private final FieldReference fieldOnB =
            new FieldReference("com/example/B", "y", "I");

    private final MethodReference accessorInA =
            new MethodReference("com/example/A", "getX", "()I");
    private final MethodReference accessorInB =
            new MethodReference("com/example/B", "readBoth", "()V");
    private final MethodReference accessorInC =
            new MethodReference("com/example/C", "readBoth", "()V");

    @Test
    @DisplayName("removes every field owned by the class (both readers and writers maps)")
    void fieldsOwnedByClassGo() {
        FieldAccessIndex fai = new FieldAccessIndex();
        // fieldOnA is touched only from class B — after removeClass("A") the field
        // disappears (owner-class match) but B's contribution to fieldOnB stays.
        fai.addRead(accessorInB, fieldOnA);
        fai.addWrite(accessorInB, fieldOnA);
        // fieldOnB has a reader in B (survives) and one in C (survives).
        fai.addRead(accessorInB, fieldOnB);
        fai.addRead(accessorInC, fieldOnB);

        fai.removeClass("com/example/A");

        assertFalse(fai.getAllFields().contains(fieldOnA),
                "fields owned by class A must be removed");
        assertTrue(fai.getAllFields().contains(fieldOnB),
                "fields owned by class B should be retained when at least one accessor remains");
    }

    @Test
    @DisplayName("strips accessors that live in the removed class from surviving fields")
    void accessorsInRemovedClassGo() {
        FieldAccessIndex fai = new FieldAccessIndex();
        // Three readers on fieldOnB: one in A (purge target), one in B (keep), one in C (keep).
        fai.addRead(accessorInA, fieldOnB);
        fai.addRead(accessorInB, fieldOnB);
        fai.addRead(accessorInC, fieldOnB);
        // One writer on fieldOnB, in A (purge target).
        fai.addWrite(accessorInA, fieldOnB);

        fai.removeClass("com/example/A");

        Set<MethodReference> readers = fai.getReaders(fieldOnB);
        assertEquals(Set.of(accessorInB, accessorInC), readers,
                "the reader in class A should be stripped; B and C survive");
        assertTrue(fai.getWriters(fieldOnB).isEmpty(),
                "the only writer (in A) was stripped, leaving the writer set empty");
    }

    @Test
    @DisplayName("now-empty field entries are pruned from getAllFields()")
    void emptyEntriesPruned() {
        FieldAccessIndex fai = new FieldAccessIndex();
        // Only accessor of fieldOnB is in class A; removing A leaves the field with
        // zero accessors and should drop it.
        fai.addRead(accessorInA, fieldOnB);

        fai.removeClass("com/example/A");

        assertFalse(fai.getAllFields().contains(fieldOnB),
                "field with no remaining accessors must not be visible in getAllFields()");
    }

    @Test
    @DisplayName("null class name is a no-op and does not throw")
    void nullIsNoOp() {
        FieldAccessIndex fai = new FieldAccessIndex();
        fai.addRead(accessorInA, fieldOnB);
        assertDoesNotThrow(() -> fai.removeClass(null));
        assertEquals(1, fai.getAllFields().size());
    }

    @Test
    @DisplayName("copy() is a deep copy — mutating the clone does not affect the original")
    void copyIsDeep() {
        FieldAccessIndex original = new FieldAccessIndex();
        original.addRead(accessorInB, fieldOnA);
        original.addWrite(accessorInA, fieldOnB);

        FieldAccessIndex clone = original.copy();
        clone.removeClass("com/example/A");

        assertTrue(original.getAllFields().contains(fieldOnA),
                "original must still know about fieldOnA after clone mutation");
        assertFalse(clone.getAllFields().contains(fieldOnA));
    }
}

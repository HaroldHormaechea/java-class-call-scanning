package com.hhg.callgraph.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FieldReference")
class FieldReferenceTest {

    private static final String CLASS = "com/hhg/entity/Product";
    private static final String FIELD = "price";
    private static final String DESC  = "Ljava/math/BigDecimal;";

    @Test
    @DisplayName("equals and hashCode are consistent for identical values")
    void equalsAndHashCodeConsistent() {
        FieldReference a = new FieldReference(CLASS, FIELD, DESC);
        FieldReference b = new FieldReference(CLASS, FIELD, DESC);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("not equal when className differs")
    void notEqualWhenClassNameDiffers() {
        FieldReference a = new FieldReference(CLASS, FIELD, DESC);
        FieldReference b = new FieldReference("com/hhg/entity/Order", FIELD, DESC);
        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("not equal when fieldName differs")
    void notEqualWhenFieldNameDiffers() {
        FieldReference a = new FieldReference(CLASS, FIELD, DESC);
        FieldReference b = new FieldReference(CLASS, "name", DESC);
        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("not equal when descriptor differs")
    void notEqualWhenDescriptorDiffers() {
        FieldReference a = new FieldReference(CLASS, FIELD, DESC);
        FieldReference b = new FieldReference(CLASS, FIELD, "Ljava/lang/String;");
        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("toString returns className#fieldName descriptor")
    void toStringFormat() {
        FieldReference ref = new FieldReference(CLASS, FIELD, DESC);
        assertEquals("com/hhg/entity/Product#price Ljava/math/BigDecimal;", ref.toString());
    }

    @Test
    @DisplayName("record accessors return correct component values")
    void recordAccessors() {
        FieldReference ref = new FieldReference(CLASS, FIELD, DESC);
        assertEquals(CLASS, ref.className());
        assertEquals(FIELD, ref.fieldName());
        assertEquals(DESC,  ref.descriptor());
    }
}

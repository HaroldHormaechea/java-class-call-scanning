package com.hhg.callgraph.daemon.op;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers AC #18 — the wire-form mapping is exactly {@code "junit5"} / {@code "spock"}.
 * Internal code uses the legacy {@code "JUnit5"} / {@code "Spock"} strings.
 */
@DisplayName("TestTypeMapping — internal → wire form")
class TestTypeMappingTest {

    @Test
    @DisplayName("JUnit5 → junit5")
    void junit5Mapped() {
        assertEquals("junit5", TestTypeMapping.wireType("JUnit5"));
    }

    @Test
    @DisplayName("Spock → spock")
    void spockMapped() {
        assertEquals("spock", TestTypeMapping.wireType("Spock"));
    }

    @Test
    @DisplayName("unknown internal value throws — never silently passes through")
    void unknownThrows() {
        assertThrows(IllegalStateException.class, () -> TestTypeMapping.wireType("TestNG"));
        assertThrows(IllegalStateException.class, () -> TestTypeMapping.wireType("junit5"));
    }
}

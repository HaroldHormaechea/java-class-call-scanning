package com.hhg.callgraph.diff;

import com.hhg.callgraph.model.MethodReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ImpactResult")
class ImpactResultTest {

    private final MethodReference methodA = new MethodReference("com/example/A", "doA", "()V");
    private final MethodReference methodB = new MethodReference("com/example/B", "doB", "()V");
    private final MethodReference methodC = new MethodReference("com/example/C", "doC", "()V");

    @Test
    @DisplayName("allImpacted() returns union of directlyChanged and transitiveCallers")
    void allImpactedReturnsUnion() {
        ImpactResult result = new ImpactResult(Set.of(methodA), Set.of(methodB, methodC));

        Set<MethodReference> all = result.allImpacted();

        assertEquals(3, all.size());
        assertTrue(all.contains(methodA));
        assertTrue(all.contains(methodB));
        assertTrue(all.contains(methodC));
    }

    @Test
    @DisplayName("allImpacted() with empty sets returns empty set")
    void allImpactedEmptySets() {
        ImpactResult result = new ImpactResult(Set.of(), Set.of());

        assertTrue(result.allImpacted().isEmpty());
    }

    @Test
    @DisplayName("allImpacted() deduplicates when sets overlap")
    void allImpactedDeduplicatesOverlap() {
        ImpactResult result = new ImpactResult(Set.of(methodA, methodB), Set.of(methodB, methodC));

        Set<MethodReference> all = result.allImpacted();

        assertEquals(3, all.size(), "Duplicate methodB should appear only once");
        assertTrue(all.contains(methodA));
        assertTrue(all.contains(methodB));
        assertTrue(all.contains(methodC));
    }

    @Test
    @DisplayName("directlyChanged() returns the provided set")
    void directlyChangedReturnsProvidedSet() {
        ImpactResult result = new ImpactResult(Set.of(methodA), Set.of(methodB));

        assertEquals(Set.of(methodA), result.directlyChanged());
    }

    @Test
    @DisplayName("transitiveCallers() returns the provided set")
    void transitiveCallersReturnsProvidedSet() {
        ImpactResult result = new ImpactResult(Set.of(methodA), Set.of(methodB));

        assertEquals(Set.of(methodB), result.transitiveCallers());
    }

    @Test
    @DisplayName("Result sets are unmodifiable")
    void resultSetsAreUnmodifiable() {
        ImpactResult result = new ImpactResult(Set.of(methodA), Set.of(methodB));

        assertThrows(UnsupportedOperationException.class,
                () -> result.directlyChanged().add(methodC));
        assertThrows(UnsupportedOperationException.class,
                () -> result.transitiveCallers().add(methodC));
        assertThrows(UnsupportedOperationException.class,
                () -> result.allImpacted().add(methodC));
    }
}

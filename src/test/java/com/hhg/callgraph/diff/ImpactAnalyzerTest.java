package com.hhg.callgraph.diff;

import com.hhg.callgraph.model.CallGraph;
import com.hhg.callgraph.model.FieldAccessIndex;
import com.hhg.callgraph.model.MethodReference;
import com.hhg.callgraph.model.SourceIndex;
import com.hhg.callgraph.model.SourceLocation;
import com.hhg.callgraph.scanner.ScanResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ImpactAnalyzer")
class ImpactAnalyzerTest {

    private static final MethodReference TC1_METHOD = new MethodReference(
            "com/hhg/main/targets/TargetClass1", "methodInClass1", "()V");
    private static final MethodReference TC2_METHOD = new MethodReference(
            "com/hhg/main/targets/TargetClass2", "methodInClass2", "()V");
    private static final MethodReference TC3_METHOD = new MethodReference(
            "com/hhg/main/targets/TargetClass3", "methodInClass3", "()V");

    private CallGraph graph;
    private SourceIndex sourceIndex;
    private ImpactAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        graph = new CallGraph();
        sourceIndex = new SourceIndex();

        // Build TC1 -> TC2 -> TC3 call chain
        graph.addCall(TC1_METHOD, TC2_METHOD);
        graph.addCall(TC2_METHOD, TC3_METHOD);

        // Assign source locations
        sourceIndex.add(TC1_METHOD, new SourceLocation("TargetClass1.java", 7, 9));
        sourceIndex.add(TC2_METHOD, new SourceLocation("TargetClass2.java", 5, 7));
        sourceIndex.add(TC3_METHOD, new SourceLocation("TargetClass3.java", 5, 7));

        ScanResult scanResult = new ScanResult(graph, sourceIndex, new FieldAccessIndex());
        analyzer = new ImpactAnalyzer(scanResult);
    }

    @Nested
    @DisplayName("Empty diff")
    class EmptyDiff {

        @Test
        @DisplayName("Empty diff list produces empty ImpactResult")
        void emptyDiffProducesEmptyResult() {
            ImpactResult result = analyzer.analyze(List.of());

            assertTrue(result.directlyChanged().isEmpty(), "No directly changed methods");
            assertTrue(result.transitiveCallers().isEmpty(), "No transitive callers");
            assertTrue(result.allImpacted().isEmpty(), "No impacted methods at all");
        }
    }

    @Nested
    @DisplayName("Direct impact detection")
    class DirectImpact {

        @Test
        @DisplayName("Diff touching line inside TC3 method body marks TC3 as directly changed")
        void diffTouchingMethodBodyMarksDirectlyChanged() {
            DiffEntry entry = new DiffEntry("TargetClass3.java", Set.of(6));

            ImpactResult result = analyzer.analyze(List.of(entry));

            assertTrue(result.directlyChanged().contains(TC3_METHOD),
                    "TC3.methodInClass3 should be directly changed (line 6 is in range 5-7)");
        }

        @Test
        @DisplayName("Diff touching TC2 method marks TC2 as directly changed")
        void diffTouchingTC2() {
            DiffEntry entry = new DiffEntry("TargetClass2.java", Set.of(6));

            ImpactResult result = analyzer.analyze(List.of(entry));

            assertTrue(result.directlyChanged().contains(TC2_METHOD),
                    "TC2.methodInClass2 should be directly changed");
        }

        @Test
        @DisplayName("Diff touching line outside all method ranges produces no directly changed")
        void diffOutsideMethodRangeNotDetected() {
            DiffEntry entry = new DiffEntry("TargetClass3.java", Set.of(100));

            ImpactResult result = analyzer.analyze(List.of(entry));

            assertTrue(result.directlyChanged().isEmpty(),
                    "Line 100 is outside all method ranges");
        }
    }

    @Nested
    @DisplayName("Transitive caller detection")
    class TransitiveCallers {

        @Test
        @DisplayName("Changing TC2 causes TC1 to appear in transitiveCallers")
        void changingTC2MakesTC1TransitiveCaller() {
            DiffEntry entry = new DiffEntry("TargetClass2.java", Set.of(6));

            ImpactResult result = analyzer.analyze(List.of(entry));

            assertTrue(result.transitiveCallers().contains(TC1_METHOD),
                    "TC1 calls TC2, so TC1 should be a transitive caller when TC2 is changed");
        }

        @Test
        @DisplayName("Changing TC3 causes both TC2 and TC1 to appear in transitiveCallers")
        void changingTC3MakesBothTC1andTC2TransitiveCallers() {
            DiffEntry entry = new DiffEntry("TargetClass3.java", Set.of(6));

            ImpactResult result = analyzer.analyze(List.of(entry));

            assertTrue(result.transitiveCallers().contains(TC2_METHOD),
                    "TC2 calls TC3, so TC2 should be a transitive caller");
            assertTrue(result.transitiveCallers().contains(TC1_METHOD),
                    "TC1 calls TC2 which calls TC3, so TC1 should be a transitive caller");
        }
    }

    @Nested
    @DisplayName("Disjoint sets and allImpacted")
    class DisjointSetsAndAllImpacted {

        @Test
        @DisplayName("directlyChanged and transitiveCallers are disjoint")
        void setsAreDisjoint() {
            DiffEntry entry = new DiffEntry("TargetClass3.java", Set.of(6));

            ImpactResult result = analyzer.analyze(List.of(entry));

            for (MethodReference method : result.directlyChanged()) {
                assertFalse(result.transitiveCallers().contains(method),
                        "Method " + method + " should not be in both sets");
            }
        }

        @Test
        @DisplayName("allImpacted() is the union of directlyChanged and transitiveCallers")
        void allImpactedIsUnion() {
            DiffEntry entry = new DiffEntry("TargetClass3.java", Set.of(6));

            ImpactResult result = analyzer.analyze(List.of(entry));

            Set<MethodReference> all = result.allImpacted();
            assertTrue(all.containsAll(result.directlyChanged()));
            assertTrue(all.containsAll(result.transitiveCallers()));
            assertEquals(result.directlyChanged().size() + result.transitiveCallers().size(),
                    all.size(), "allImpacted size should equal sum of both sets (they are disjoint)");
        }
    }

    @Nested
    @DisplayName("--sources root stripping")
    class SourcesRoot {

        @Test
        @DisplayName("Diff path with sources root prefix is matched after stripping the prefix")
        void sourcesRootStrippedBeforeMatching() {
            // Diff provides full path "src/test/java/com/hhg/main/targets/TargetClass3.java"
            // With --sources src/test/java, that strips to "com/hhg/main/targets/TargetClass3.java"
            // which is an exact package-relative match for TC3_METHOD
            ImpactAnalyzer withSources = new ImpactAnalyzer(
                    new ScanResult(graph, sourceIndex, new FieldAccessIndex()),
                    Path.of("src/test/java"));

            DiffEntry entry = new DiffEntry("src/test/java/com/hhg/main/targets/TargetClass3.java", Set.of(6));
            ImpactResult result = withSources.analyze(List.of(entry));

            assertTrue(result.directlyChanged().contains(TC3_METHOD),
                    "TC3 should be directly changed when its full path (with sources root) is in the diff");
        }

        @Test
        @DisplayName("Diff path that does not start with sources root still matches via suffix")
        void diffPathNotUnderSourcesRootFallsBackToSuffixMatch() {
            // Even with --sources configured, a path not starting with that root
            // still resolves via the package-qualified suffix match
            ImpactAnalyzer withSources = new ImpactAnalyzer(
                    new ScanResult(graph, sourceIndex, new FieldAccessIndex()),
                    Path.of("src/main/java"));

            DiffEntry entry = new DiffEntry("src/test/java/com/hhg/main/targets/TargetClass3.java", Set.of(6));
            ImpactResult result = withSources.analyze(List.of(entry));

            assertTrue(result.directlyChanged().contains(TC3_METHOD),
                    "Should still match via suffix even when sources root prefix doesn't apply");
        }
    }

    @Nested
    @DisplayName("Unknown source file")
    class UnknownSourceFile {

        @Test
        @DisplayName("Diff referencing unknown file produces no matches gracefully")
        void unknownFileProducesNoMatches() {
            DiffEntry entry = new DiffEntry("NonExistent.java", Set.of(1, 2, 3));

            ImpactResult result = analyzer.analyze(List.of(entry));

            assertTrue(result.directlyChanged().isEmpty());
            assertTrue(result.transitiveCallers().isEmpty());
        }
    }
}

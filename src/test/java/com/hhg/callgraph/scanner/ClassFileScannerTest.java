package com.hhg.callgraph.scanner;

import com.hhg.callgraph.model.CallGraph;
import com.hhg.callgraph.model.MethodReference;
import com.hhg.callgraph.model.SourceIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ClassFileScannerTest {

    private ClassFileScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new ClassFileScanner();
    }

    /**
     * Test fixtures (TargetClass1/2/3 and the benchmark app) live in src/test/java,
     * so their compiled .class files are under build/classes/java/test.
     */
    private static Path getTestClassesDir() {
        return Path.of(System.getProperty("user.dir")).resolve("build/classes/java/test");
    }

    private static Path getTargetsDir() {
        return getTestClassesDir().resolve("com/hhg/main/targets");
    }

    @Nested
    @DisplayName("Scanning compiled target classes")
    class ScanTargetClasses {

        @Test
        @DisplayName("Scanning targets directory produces a non-empty graph")
        void scanTargetsDirProducesGraph() throws IOException {
            Path targetsDir = getTargetsDir();
            if (!Files.exists(targetsDir)) {
                fail("Target classes not compiled. Run './gradlew build' first.");
            }

            ScanResult result = scanner.scan(targetsDir);
            CallGraph graph = result.callGraph();

            assertTrue(graph.methodCount() > 0, "Graph should contain methods");
            assertTrue(graph.edgeCount() > 0, "Graph should contain edges");
        }

        @Test
        @DisplayName("Graph contains TargetClass1.methodInClass1 -> TargetClass2.methodInClass2 edge")
        void containsTC1toTC2Edge() throws IOException {
            Path targetsDir = getTargetsDir();
            if (!Files.exists(targetsDir)) {
                fail("Target classes not compiled. Run './gradlew build' first.");
            }

            ScanResult result = scanner.scan(targetsDir);
            CallGraph graph = result.callGraph();

            MethodReference tc1Method = new MethodReference(
                    "com/hhg/main/targets/TargetClass1", "methodInClass1", "()V");

            Set<MethodReference> callees = graph.getCalleesOf(tc1Method);

            boolean foundTC2Call = callees.stream().anyMatch(
                    ref -> ref.getClassName().equals("com/hhg/main/targets/TargetClass2")
                            && ref.getMethodName().equals("methodInClass2"));

            assertTrue(foundTC2Call,
                    "TargetClass1.methodInClass1 should call TargetClass2.methodInClass2. Found callees: " + callees);
        }

        @Test
        @DisplayName("Graph contains TargetClass2.methodInClass2 -> TargetClass3.methodInClass3 edge")
        void containsTC2toTC3Edge() throws IOException {
            Path targetsDir = getTargetsDir();
            if (!Files.exists(targetsDir)) {
                fail("Target classes not compiled. Run './gradlew build' first.");
            }

            ScanResult result = scanner.scan(targetsDir);
            CallGraph graph = result.callGraph();

            MethodReference tc2Method = new MethodReference(
                    "com/hhg/main/targets/TargetClass2", "methodInClass2", "()V");

            Set<MethodReference> callees = graph.getCalleesOf(tc2Method);

            boolean foundTC3Call = callees.stream().anyMatch(
                    ref -> ref.getClassName().equals("com/hhg/main/targets/TargetClass3")
                            && ref.getMethodName().equals("methodInClass3"));

            assertTrue(foundTC3Call,
                    "TargetClass2.methodInClass2 should call TargetClass3.methodInClass3. Found callees: " + callees);
        }

        @Test
        @DisplayName("Graph contains TargetClass3.methodInClass3 -> PrintStream.println edge")
        void containsTC3toPrintlnEdge() throws IOException {
            Path targetsDir = getTargetsDir();
            if (!Files.exists(targetsDir)) {
                fail("Target classes not compiled. Run './gradlew build' first.");
            }

            ScanResult result = scanner.scan(targetsDir);
            CallGraph graph = result.callGraph();

            MethodReference tc3Method = new MethodReference(
                    "com/hhg/main/targets/TargetClass3", "methodInClass3", "()V");

            Set<MethodReference> callees = graph.getCalleesOf(tc3Method);

            boolean foundPrintln = callees.stream().anyMatch(
                    ref -> ref.getMethodName().equals("println"));

            assertTrue(foundPrintln,
                    "TargetClass3.methodInClass3 should call println. Found callees: " + callees);
        }

        @Test
        @DisplayName("TargetClass1 constructor calls TargetClass2 <init>")
        void tc1ConstructorCallsTC2Init() throws IOException {
            Path targetsDir = getTargetsDir();
            if (!Files.exists(targetsDir)) {
                fail("Target classes not compiled. Run './gradlew build' first.");
            }

            ScanResult result = scanner.scan(targetsDir);
            CallGraph graph = result.callGraph();

            MethodReference tc1Init = new MethodReference(
                    "com/hhg/main/targets/TargetClass1", "<init>", "()V");

            Set<MethodReference> callees = graph.getCalleesOf(tc1Init);

            boolean foundTC2Init = callees.stream().anyMatch(
                    ref -> ref.getClassName().equals("com/hhg/main/targets/TargetClass2")
                            && ref.getMethodName().equals("<init>"));

            assertTrue(foundTC2Init,
                    "TargetClass1.<init> should call TargetClass2.<init>. Found callees: " + callees);
        }
    }

    @Nested
    @DisplayName("Recursive directory scanning")
    class RecursiveScanning {

        @Test
        @DisplayName("Scanning the test classes root finds target classes in subdirectories")
        void recursiveScanFindsNestedClasses() throws IOException {
            Path testClassesDir = getTestClassesDir();
            if (!Files.exists(testClassesDir)) {
                fail("Classes not compiled. Run './gradlew build' first.");
            }

            ScanResult result = scanner.scan(testClassesDir);
            CallGraph graph = result.callGraph();

            boolean foundTargetMethod = graph.getAllMethods().stream().anyMatch(
                    ref -> ref.getClassName().contains("targets/TargetClass"));

            assertTrue(foundTargetMethod,
                    "Recursive scan should find TargetClass methods in subdirectories");
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Empty directory returns empty graph")
        void emptyDirectoryReturnsEmptyGraph(@TempDir Path tempDir) throws IOException {
            ScanResult result = scanner.scan(tempDir);
            CallGraph graph = result.callGraph();

            assertEquals(0, graph.methodCount());
            assertEquals(0, graph.edgeCount());
        }

        @Test
        @DisplayName("Directory with no .class files returns empty graph")
        void nonClassFilesIgnored(@TempDir Path tempDir) throws IOException {
            Files.writeString(tempDir.resolve("readme.txt"), "not a class file");
            Files.writeString(tempDir.resolve("data.json"), "{}");

            ScanResult result = scanner.scan(tempDir);
            CallGraph graph = result.callGraph();

            assertEquals(0, graph.methodCount());
            assertEquals(0, graph.edgeCount());
        }
    }

    @Nested
    @DisplayName("ScanResult SourceIndex")
    class ScanResultSourceIndex {

        @Test
        @DisplayName("sourceIndex is not null")
        void sourceIndexNotNull() throws IOException {
            Path targetsDir = getTargetsDir();
            if (!Files.exists(targetsDir)) {
                fail("Target classes not compiled. Run './gradlew build' first.");
            }

            ScanResult result = scanner.scan(targetsDir);

            assertNotNull(result.sourceIndex());
        }

        @Test
        @DisplayName("sourceIndex.size() >= 3 after scanning targets dir")
        void sourceIndexHasAtLeastThreeMethods() throws IOException {
            Path targetsDir = getTargetsDir();
            if (!Files.exists(targetsDir)) {
                fail("Target classes not compiled. Run './gradlew build' first.");
            }

            ScanResult result = scanner.scan(targetsDir);

            assertTrue(result.sourceIndex().size() >= 3,
                    "Should have at least 3 methods indexed. Got: " + result.sourceIndex().size());
        }

        @Test
        @DisplayName("TC1.methodInClass1 has a non-unknown location")
        void tc1MethodHasKnownLocation() throws IOException {
            Path targetsDir = getTargetsDir();
            if (!Files.exists(targetsDir)) {
                fail("Target classes not compiled. Run './gradlew build' first.");
            }

            ScanResult result = scanner.scan(targetsDir);
            SourceIndex sourceIndex = result.sourceIndex();

            MethodReference tc1Method = new MethodReference(
                    "com/hhg/main/targets/TargetClass1", "methodInClass1", "()V");

            assertTrue(sourceIndex.getLocation(tc1Method).isPresent(), "Location should be present for TC1.methodInClass1");
            assertFalse(sourceIndex.getLocation(tc1Method).get().isUnknown(),
                    "TC1.methodInClass1 should have known line info");
        }

        @Test
        @DisplayName("TC1.methodInClass1 location contains line 8")
        void tc1MethodLocationContainsLine8() throws IOException {
            Path targetsDir = getTargetsDir();
            if (!Files.exists(targetsDir)) {
                fail("Target classes not compiled. Run './gradlew build' first.");
            }

            ScanResult result = scanner.scan(targetsDir);
            SourceIndex sourceIndex = result.sourceIndex();

            MethodReference tc1Method = new MethodReference(
                    "com/hhg/main/targets/TargetClass1", "methodInClass1", "()V");

            assertTrue(sourceIndex.getLocation(tc1Method).map(loc -> loc.containsLine(8)).orElse(false),
                    "TC1.methodInClass1 should contain line 8");
        }

        @Test
        @DisplayName("TC2.methodInClass2 location contains line 6")
        void tc2MethodLocationContainsLine6() throws IOException {
            Path targetsDir = getTargetsDir();
            if (!Files.exists(targetsDir)) {
                fail("Target classes not compiled. Run './gradlew build' first.");
            }

            ScanResult result = scanner.scan(targetsDir);
            SourceIndex sourceIndex = result.sourceIndex();

            MethodReference tc2Method = new MethodReference(
                    "com/hhg/main/targets/TargetClass2", "methodInClass2", "()V");

            assertTrue(sourceIndex.getLocation(tc2Method).map(loc -> loc.containsLine(6)).orElse(false),
                    "TC2.methodInClass2 should contain line 6");
        }

        @Test
        @DisplayName("TC3.methodInClass3 location contains line 6")
        void tc3MethodLocationContainsLine6() throws IOException {
            Path targetsDir = getTargetsDir();
            if (!Files.exists(targetsDir)) {
                fail("Target classes not compiled. Run './gradlew build' first.");
            }

            ScanResult result = scanner.scan(targetsDir);
            SourceIndex sourceIndex = result.sourceIndex();

            MethodReference tc3Method = new MethodReference(
                    "com/hhg/main/targets/TargetClass3", "methodInClass3", "()V");

            assertTrue(sourceIndex.getLocation(tc3Method).map(loc -> loc.containsLine(6)).orElse(false),
                    "TC3.methodInClass3 should contain line 6");
        }

        @Test
        @DisplayName("findMethodsAt('TargetClass1.java', 8) returns set containing methodInClass1")
        void findMethodsAtTC1Line8() throws IOException {
            Path targetsDir = getTargetsDir();
            if (!Files.exists(targetsDir)) {
                fail("Target classes not compiled. Run './gradlew build' first.");
            }

            ScanResult result = scanner.scan(targetsDir);
            SourceIndex sourceIndex = result.sourceIndex();

            MethodReference tc1Method = new MethodReference(
                    "com/hhg/main/targets/TargetClass1", "methodInClass1", "()V");

            Set<MethodReference> found = sourceIndex.findMethodsAt("TargetClass1.java", 8);

            assertTrue(found.contains(tc1Method),
                    "findMethodsAt should return methodInClass1 at line 8. Found: " + found);
        }

        @Test
        @DisplayName("findMethodsAt with full relative path also finds methodInClass1")
        void findMethodsAtFullRelativePath() throws IOException {
            Path targetsDir = getTargetsDir();
            if (!Files.exists(targetsDir)) {
                fail("Target classes not compiled. Run './gradlew build' first.");
            }

            ScanResult result = scanner.scan(targetsDir);
            SourceIndex sourceIndex = result.sourceIndex();

            MethodReference tc1Method = new MethodReference(
                    "com/hhg/main/targets/TargetClass1", "methodInClass1", "()V");

            Set<MethodReference> found = sourceIndex.findMethodsAt(
                    "src/test/java/com/hhg/main/targets/TargetClass1.java", 8);

            assertTrue(found.contains(tc1Method),
                    "findMethodsAt with full path should also return methodInClass1. Found: " + found);
        }

        @Test
        @DisplayName("Empty directory produces ScanResult with empty callGraph and sourceIndex.size() == 0")
        void emptyDirectoryProducesEmptyScanResult(@TempDir Path tempDir) throws IOException {
            ScanResult result = scanner.scan(tempDir);

            assertEquals(0, result.callGraph().methodCount());
            assertEquals(0, result.sourceIndex().size());
        }
    }
}

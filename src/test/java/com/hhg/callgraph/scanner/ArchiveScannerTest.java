package com.hhg.callgraph.scanner;

import com.hhg.callgraph.model.MethodReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ClassFileScanner — archive support")
class ArchiveScannerTest {

    /**
     * Reads the compiled bytes of a TargetClass from the test build output.
     * Requires './gradlew build' to have been run first.
     */
    private static byte[] targetClassBytes(String simpleName) throws IOException {
        Path classFile = Path.of(System.getProperty("user.dir"))
                .resolve("build/classes/java/test/com/hhg/main/targets/" + simpleName + ".class");
        if (!Files.exists(classFile)) {
            throw new IllegalStateException(
                    "Compiled class not found — run './gradlew build' first: " + classFile);
        }
        return Files.readAllBytes(classFile);
    }

    // -----------------------------------------------------------------------
    // Fixtures — built once for all tests
    // -----------------------------------------------------------------------

    private static byte[] tc1Bytes;
    private static byte[] tc2Bytes;
    private static byte[] tc3Bytes;

    @BeforeAll
    static void loadTargetBytes() throws IOException {
        tc1Bytes = targetClassBytes("TargetClass1");
        tc2Bytes = targetClassBytes("TargetClass2");
        tc3Bytes = targetClassBytes("TargetClass3");
    }

    // -----------------------------------------------------------------------
    // Helpers to build test archives in a TempDir
    // -----------------------------------------------------------------------

    /** Creates a plain JAR with all three TargetClass .class files at the root. */
    private static Path buildJar(Path dir) throws IOException {
        Path jar = dir.resolve("test.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            addEntry(zos, "com/hhg/main/targets/TargetClass1.class", tc1Bytes);
            addEntry(zos, "com/hhg/main/targets/TargetClass2.class", tc2Bytes);
            addEntry(zos, "com/hhg/main/targets/TargetClass3.class", tc3Bytes);
        }
        return jar;
    }

    /** Creates a WAR with the three TargetClass .class files under WEB-INF/classes/. */
    private static Path buildWar(Path dir) throws IOException {
        Path war = dir.resolve("test.war");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(war))) {
            addEntry(zos, "WEB-INF/classes/com/hhg/main/targets/TargetClass1.class", tc1Bytes);
            addEntry(zos, "WEB-INF/classes/com/hhg/main/targets/TargetClass2.class", tc2Bytes);
            addEntry(zos, "WEB-INF/classes/com/hhg/main/targets/TargetClass3.class", tc3Bytes);
        }
        return war;
    }

    /**
     * Creates a WAR where the classes are inside a nested WEB-INF/lib/lib.jar
     * (not in WEB-INF/classes/).
     */
    private static Path buildWarWithNestedJar(Path dir) throws IOException {
        // Build the inner JAR in memory
        java.io.ByteArrayOutputStream innerBuf = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream inner = new ZipOutputStream(innerBuf)) {
            addEntry(inner, "com/hhg/main/targets/TargetClass1.class", tc1Bytes);
            addEntry(inner, "com/hhg/main/targets/TargetClass2.class", tc2Bytes);
            addEntry(inner, "com/hhg/main/targets/TargetClass3.class", tc3Bytes);
        }

        Path war = dir.resolve("test-nested.war");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(war))) {
            addEntry(zos, "WEB-INF/lib/lib.jar", innerBuf.toByteArray());
        }
        return war;
    }

    private static void addEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    // -----------------------------------------------------------------------
    // JAR scanning
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("JAR scanning")
    class JarScanning {

        @Test
        @DisplayName("scanPath on a JAR produces a non-empty graph")
        void scanPathJarProducesGraph(@TempDir Path tmp) throws IOException {
            Path jar = buildJar(tmp);
            ScanResult result = new ClassFileScanner().scanPath(jar);
            assertTrue(result.callGraph().methodCount() > 0);
            assertTrue(result.callGraph().edgeCount() > 0);
        }

        @Test
        @DisplayName("JAR scan finds TC1 -> TC2 call edge")
        void jarScanFindsTC1toTC2(@TempDir Path tmp) throws IOException {
            Path jar = buildJar(tmp);
            ScanResult result = new ClassFileScanner().scanPath(jar);

            MethodReference tc1 = new MethodReference(
                    "com/hhg/main/targets/TargetClass1", "methodInClass1", "()V");
            boolean found = result.callGraph().getCalleesOf(tc1).stream()
                    .anyMatch(r -> r.getClassName().equals("com/hhg/main/targets/TargetClass2")
                               && r.getMethodName().equals("methodInClass2"));
            assertTrue(found, "JAR scan should find TC1->TC2 call");
        }

        @Test
        @DisplayName("scanJar produces the same result as scanPath for a .jar file")
        void scanJarMatchesScanPath(@TempDir Path tmp) throws IOException {
            Path jar = buildJar(tmp);
            ScanResult viaPath = new ClassFileScanner().scanPath(jar);
            ScanResult viaJar  = ClassFileScanner.scanJar(jar);
            assertEquals(viaPath.callGraph().methodCount(), viaJar.callGraph().methodCount());
            assertEquals(viaPath.callGraph().edgeCount(),   viaJar.callGraph().edgeCount());
        }
    }

    // -----------------------------------------------------------------------
    // WAR scanning
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("WAR scanning")
    class WarScanning {

        @Test
        @DisplayName("scanPath on a WAR produces a non-empty graph")
        void scanPathWarProducesGraph(@TempDir Path tmp) throws IOException {
            Path war = buildWar(tmp);
            ScanResult result = new ClassFileScanner().scanPath(war);
            assertTrue(result.callGraph().methodCount() > 0);
            assertTrue(result.callGraph().edgeCount() > 0);
        }

        @Test
        @DisplayName("WAR scan finds classes from WEB-INF/classes/")
        void warScanFindsClassesInWebInfClasses(@TempDir Path tmp) throws IOException {
            Path war = buildWar(tmp);
            ScanResult result = new ClassFileScanner().scanPath(war);

            boolean foundTC1 = result.callGraph().getAllMethods().stream()
                    .anyMatch(m -> m.getClassName().equals("com/hhg/main/targets/TargetClass1"));
            assertTrue(foundTC1, "WAR scan should find TargetClass1 from WEB-INF/classes/");
        }

        @Test
        @DisplayName("WAR scan finds TC2 -> TC3 call edge")
        void warScanFindsTC2toTC3(@TempDir Path tmp) throws IOException {
            Path war = buildWar(tmp);
            ScanResult result = new ClassFileScanner().scanPath(war);

            MethodReference tc2 = new MethodReference(
                    "com/hhg/main/targets/TargetClass2", "methodInClass2", "()V");
            boolean found = result.callGraph().getCalleesOf(tc2).stream()
                    .anyMatch(r -> r.getClassName().equals("com/hhg/main/targets/TargetClass3")
                               && r.getMethodName().equals("methodInClass3"));
            assertTrue(found, "WAR scan should find TC2->TC3 call");
        }

        @Test
        @DisplayName("scanWar produces the same result as scanPath for a .war file")
        void scanWarMatchesScanPath(@TempDir Path tmp) throws IOException {
            Path war = buildWar(tmp);
            ScanResult viaPath = new ClassFileScanner().scanPath(war);
            ScanResult viaWar  = ClassFileScanner.scanWar(war);
            assertEquals(viaPath.callGraph().methodCount(), viaWar.callGraph().methodCount());
            assertEquals(viaPath.callGraph().edgeCount(),   viaWar.callGraph().edgeCount());
        }
    }

    // -----------------------------------------------------------------------
    // Nested JAR inside WAR
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Nested JAR inside WAR (WEB-INF/lib/)")
    class NestedJarInWar {

        @Test
        @DisplayName("WAR with nested lib JAR: classes from WEB-INF/lib/*.jar appear in graph")
        void nestedJarClassesFoundInGraph(@TempDir Path tmp) throws IOException {
            Path war = buildWarWithNestedJar(tmp);
            ScanResult result = new ClassFileScanner().scanPath(war);

            boolean foundTC1 = result.callGraph().getAllMethods().stream()
                    .anyMatch(m -> m.getClassName().equals("com/hhg/main/targets/TargetClass1"));
            assertTrue(foundTC1, "Classes from nested WEB-INF/lib JAR should be in the graph");
        }

        @Test
        @DisplayName("WAR with nested lib JAR: call edges from nested JAR classes are captured")
        void nestedJarEdgesCaptured(@TempDir Path tmp) throws IOException {
            Path war = buildWarWithNestedJar(tmp);
            ScanResult result = new ClassFileScanner().scanPath(war);

            MethodReference tc1 = new MethodReference(
                    "com/hhg/main/targets/TargetClass1", "methodInClass1", "()V");
            boolean found = result.callGraph().getCalleesOf(tc1).stream()
                    .anyMatch(r -> r.getMethodName().equals("methodInClass2"));
            assertTrue(found, "Call edges from nested-JAR classes should be captured");
        }
    }

    // -----------------------------------------------------------------------
    // scanPath — error cases
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("scanPath — error cases")
    class ScanPathErrors {

        @Test
        @DisplayName("scanPath on a non-archive file throws IllegalArgumentException")
        void unknownExtensionThrows(@TempDir Path tmp) throws IOException {
            Path txt = tmp.resolve("classes.txt");
            Files.writeString(txt, "not an archive");
            assertThrows(IllegalArgumentException.class,
                    () -> new ClassFileScanner().scanPath(txt));
        }

        @Test
        @DisplayName("scanPath on a directory still works (backward-compat)")
        void scanPathDirectory(@TempDir Path tmp) throws IOException {
            ScanResult result = new ClassFileScanner().scanPath(tmp);
            assertEquals(0, result.callGraph().methodCount());
        }
    }
}

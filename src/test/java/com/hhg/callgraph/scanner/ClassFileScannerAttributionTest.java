package com.hhg.callgraph.scanner;

import com.hhg.callgraph.model.MethodReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UC04 attribution contract — every scanned class is recorded in
 * {@link ScanResult#classOrigins()} so the incremental rebuilder can reverse-map
 * "which classes does this changed path own?" without re-walking the disk.
 *
 * <p>Rules under test (matching the {@link ScanResult} Javadoc):
 * <ul>
 *   <li>Directory roots: the origin is the {@code .class} file itself.</li>
 *   <li>JAR archives: every class shares the archive path as its origin.</li>
 *   <li>WAR archives: every class shares the WAR path as its origin (including
 *       classes contributed by nested {@code WEB-INF/lib/*.jar} entries).</li>
 *   <li>{@link ClassFileScanner#scanClassFiles(java.util.List)} records each input
 *       file as the origin of any class it contains, even when the file lives in a
 *       different directory than other inputs.</li>
 *   <li>{@link ClassFileScanner#scanArchive(java.nio.file.Path)} is equivalent to
 *       {@code scanPath} for the archive's attribution purposes.</li>
 * </ul>
 */
@DisplayName("ClassFileScanner — classOrigins attribution (UC04)")
class ClassFileScannerAttributionTest {

    private static byte[] targetClassBytes(String simpleName) throws IOException {
        Path classFile = Path.of(System.getProperty("user.dir"))
                .resolve("build/classes/java/test/com/hhg/main/targets/" + simpleName + ".class");
        if (!Files.exists(classFile)) {
            throw new IllegalStateException(
                    "Compiled class not found — run './gradlew compileTestJava' first: " + classFile);
        }
        return Files.readAllBytes(classFile);
    }

    @Nested
    @DisplayName("Directory roots — origin is the .class file itself")
    class DirectoryAttribution {

        @Test
        @DisplayName("scan(dir) maps each class to its own .class file path")
        void directoryOriginIsClassFile(@TempDir Path tmp) throws IOException {
            Path pkg = tmp.resolve("com/hhg/main/targets");
            Files.createDirectories(pkg);
            Path tc1File = pkg.resolve("TargetClass1.class");
            Path tc2File = pkg.resolve("TargetClass2.class");
            Files.write(tc1File, targetClassBytes("TargetClass1"));
            Files.write(tc2File, targetClassBytes("TargetClass2"));

            ScanResult result = new ClassFileScanner().scan(tmp);
            Map<String, Path> origins = result.classOrigins();

            assertEquals(tc1File.toAbsolutePath(),
                    origins.get("com/hhg/main/targets/TargetClass1"),
                    "TargetClass1 origin must be its own .class file");
            assertEquals(tc2File.toAbsolutePath(),
                    origins.get("com/hhg/main/targets/TargetClass2"),
                    "TargetClass2 origin must be its own .class file");
        }

        @Test
        @DisplayName("scanClassFiles preserves per-file attribution across heterogeneous inputs")
        void scanClassFilesAttribution(@TempDir Path tmp) throws IOException {
            Path dirA = tmp.resolve("a");
            Path dirB = tmp.resolve("b");
            Files.createDirectories(dirA);
            Files.createDirectories(dirB);
            Path tc1 = dirA.resolve("TargetClass1.class");
            Path tc2 = dirB.resolve("TargetClass2.class");
            Files.write(tc1, targetClassBytes("TargetClass1"));
            Files.write(tc2, targetClassBytes("TargetClass2"));

            ScanResult result = new ClassFileScanner().scanClassFiles(java.util.List.of(tc1, tc2));
            Map<String, Path> origins = result.classOrigins();

            assertEquals(tc1.toAbsolutePath(),
                    origins.get("com/hhg/main/targets/TargetClass1"));
            assertEquals(tc2.toAbsolutePath(),
                    origins.get("com/hhg/main/targets/TargetClass2"));
        }
    }

    @Nested
    @DisplayName("Archive roots — origin is the archive path")
    class ArchiveAttribution {

        @Test
        @DisplayName("JAR: every class shares the archive path as its origin")
        void jarOriginIsArchive(@TempDir Path tmp) throws IOException {
            Path jar = tmp.resolve("targets.jar");
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
                addEntry(zos, "com/hhg/main/targets/TargetClass1.class", targetClassBytes("TargetClass1"));
                addEntry(zos, "com/hhg/main/targets/TargetClass2.class", targetClassBytes("TargetClass2"));
            }

            ScanResult result = new ClassFileScanner().scanPath(jar);
            Map<String, Path> origins = result.classOrigins();

            assertEquals(jar.toAbsolutePath(),
                    origins.get("com/hhg/main/targets/TargetClass1"));
            assertEquals(jar.toAbsolutePath(),
                    origins.get("com/hhg/main/targets/TargetClass2"));
        }

        @Test
        @DisplayName("scanArchive returns the same origins as scanPath")
        void scanArchiveEqualsScanPath(@TempDir Path tmp) throws IOException {
            Path jar = tmp.resolve("targets.jar");
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
                addEntry(zos, "com/hhg/main/targets/TargetClass1.class", targetClassBytes("TargetClass1"));
            }
            Map<String, Path> viaScanPath = new ClassFileScanner().scanPath(jar).classOrigins();
            Map<String, Path> viaScanArchive = new ClassFileScanner().scanArchive(jar).classOrigins();
            assertEquals(viaScanPath, viaScanArchive);
            assertEquals(jar.toAbsolutePath(),
                    viaScanArchive.get("com/hhg/main/targets/TargetClass1"));
        }

        @Test
        @DisplayName("WAR: classes from WEB-INF/classes are attributed to the WAR path")
        void warClassesOriginIsWar(@TempDir Path tmp) throws IOException {
            Path war = tmp.resolve("targets.war");
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(war))) {
                addEntry(zos, "WEB-INF/classes/com/hhg/main/targets/TargetClass1.class",
                        targetClassBytes("TargetClass1"));
            }

            ScanResult result = new ClassFileScanner().scanPath(war);
            assertEquals(war.toAbsolutePath(),
                    result.classOrigins().get("com/hhg/main/targets/TargetClass1"));
        }

        @Test
        @DisplayName("WAR with nested lib JAR: nested classes are still attributed to the WAR")
        void nestedJarOriginIsWar(@TempDir Path tmp) throws IOException {
            java.io.ByteArrayOutputStream innerBuf = new java.io.ByteArrayOutputStream();
            try (ZipOutputStream inner = new ZipOutputStream(innerBuf)) {
                addEntry(inner, "com/hhg/main/targets/TargetClass1.class",
                        targetClassBytes("TargetClass1"));
            }
            Path war = tmp.resolve("targets-nested.war");
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(war))) {
                addEntry(zos, "WEB-INF/lib/lib.jar", innerBuf.toByteArray());
            }

            ScanResult result = new ClassFileScanner().scanPath(war);
            assertEquals(war.toAbsolutePath(),
                    result.classOrigins().get("com/hhg/main/targets/TargetClass1"),
                    "Classes from WEB-INF/lib/*.jar should share the outer WAR's origin");
        }

        @Test
        @DisplayName("All scanned methods have a backing classOrigins entry")
        void everyClassHasOrigin(@TempDir Path tmp) throws IOException {
            Path jar = tmp.resolve("targets.jar");
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
                addEntry(zos, "com/hhg/main/targets/TargetClass1.class", targetClassBytes("TargetClass1"));
                addEntry(zos, "com/hhg/main/targets/TargetClass2.class", targetClassBytes("TargetClass2"));
                addEntry(zos, "com/hhg/main/targets/TargetClass3.class", targetClassBytes("TargetClass3"));
            }

            ScanResult result = new ClassFileScanner().scanPath(jar);
            Map<String, Path> origins = result.classOrigins();
            for (MethodReference m : result.callGraph().getAllMethods()) {
                String cls = m.getClassName();
                if (cls.startsWith("com/hhg/main/targets/")) {
                    assertTrue(origins.containsKey(cls),
                            "every scanned target class must have a classOrigins entry; missing " + cls);
                }
            }
        }
    }

    private static void addEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }
}

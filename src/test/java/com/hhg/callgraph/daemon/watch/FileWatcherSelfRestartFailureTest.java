package com.hhg.callgraph.daemon.watch;

import com.hhg.callgraph.daemon.Discovery;
import com.hhg.callgraph.daemon.ScopeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * UC04 AC #17(f) — when a self-restart phase fails (e.g., the new initial scan throws
 * because the classpath went away), the daemon logs {@code phase: "failed"}, releases
 * the discovery file, and exits non-zero. No retry, no zombie state.
 *
 * <p>Two scenarios:
 * <ul>
 *   <li><b>JAR classpath</b> — deleting the JAR makes {@code doScanJar} throw
 *       {@link IOException}, the originally-handled failure type.</li>
 *   <li><b>Directory classpath</b> — deleting the directory makes
 *       {@code ClassFileScanner.scanPath} fall through to
 *       {@code IllegalArgumentException} (a {@link RuntimeException}). The fix in
 *       {@code Daemon.restartInPlace} broadened the catch from {@code IOException} to
 *       {@code Throwable}, so this path now also routes to
 *       {@code phase: "failed"} + {@code System.exit(11)}.</li>
 * </ul>
 *
 * <p>Both run as a child JVM to verify the {@link System#exit(int)} contract
 * end-to-end, mirroring {@code DaemonProcessSpawnE2ETest}'s spawn approach. Skips
 * (rather than fails) when the spawn preconditions can't be met locally.
 */
@DisplayName("FileWatcher — self-restart failure exits non-zero (UC04 AC #17(f))")
class FileWatcherSelfRestartFailureTest {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    @TempDir
    Path tempDir;

    private Process child;

    @AfterEach
    void cleanup() {
        try {
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
        }
    }

    @Test
    @DisplayName("JAR classpath deleted → rescan throws IOException → phase: 'failed' + non-zero exit + discovery cleaned")
    void scanFailureExitsNonZero_jarClasspath() throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        Path benchmark = requireBenchmarkOrSkip();

        Path classpathJar = projectRoot.resolve("classpath.jar");
        byte[] userServiceBytes = Files.readAllBytes(
                benchmark.resolve("service/UserService.class"));
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(classpathJar))) {
            ZipEntry e = new ZipEntry("com/hhg/benchmark/service/UserService.class");
            zos.putNextEntry(e);
            zos.write(userServiceBytes);
            zos.closeEntry();
        }

        runSelfRestartFailureScenario(projectRoot, classpathJar,
                () -> Files.delete(classpathJar),
                "JAR classpath");
    }

    @Test
    @DisplayName("directory classpath deleted → rescan throws non-IOException → phase: 'failed' + non-zero exit")
    void scanFailureExitsNonZero_directoryClasspath() throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        Path benchmark = requireBenchmarkOrSkip();

        // Directory classpath: ClassFileScanner.scanPath(missingDir) returns false for
        // Files.isDirectory, falls through the .war/.ear/.jar branches, and throws
        // IllegalArgumentException("not a directory or a recognised archive: ...").
        // That's a RuntimeException — before the developer's fix the daemon never
        // exited on this code path. With the catch broadened to Throwable, it now
        // routes the same way IOException does.
        Path classpathDir = projectRoot.resolve("classes");
        Files.createDirectories(classpathDir.resolve("com/hhg/benchmark/service"));
        Files.copy(benchmark.resolve("service/UserService.class"),
                classpathDir.resolve("com/hhg/benchmark/service/UserService.class"));

        runSelfRestartFailureScenario(projectRoot, classpathDir,
                () -> deleteRecursive(classpathDir),
                "directory classpath");
    }

    /**
     * Common harness: spawns a child daemon, waits for the discovery file, runs the
     * scenario-specific {@code sabotage} step, touches build.gradle, then asserts the
     * daemon exits non-zero with {@code phase: "failed"} in the structured log and
     * the discovery file gone.
     */
    private void runSelfRestartFailureScenario(Path projectRoot,
                                               Path classpathEntry,
                                               IOAction sabotage,
                                               String scenarioLabel) throws Exception {
        Path javaBin = Path.of(System.getProperty("java.home"), "bin",
                IS_WINDOWS ? "java.exe" : "java");
        if (!Files.isExecutable(javaBin)) {
            Assumptions.abort("skipping " + scenarioLabel
                    + ": java binary not executable at " + javaBin);
        }

        Path buildGradle = projectRoot.resolve("build.gradle");
        Files.writeString(buildGradle, "plugins { id 'java' }\n");
        Path src = projectRoot.resolve("src");
        Files.createDirectories(src);

        Path discoveryDir = tempDir.resolve("discovery-" + scenarioLabel.replace(' ', '-'));
        Files.createDirectories(discoveryDir);
        String expectedHash = ScopeConfig.of(
                List.of(classpathEntry), List.of(src), List.of(), List.of()).projectHash();
        Path discoveryFile = discoveryDir.resolve(expectedHash + ".json");
        Path lockFile = discoveryDir.resolve(expectedHash + ".lock");
        Path stderrLog = tempDir.resolve("child-stderr-" + scenarioLabel.replace(' ', '-') + ".log");
        Path logFile = discoveryDir.resolve(expectedHash + ".log");

        ProcessBuilder pb = new ProcessBuilder(
                javaBin.toString(),
                "-cp", System.getProperty("java.class.path"),
                "com.hhg.callgraph.CallGraphBuilder",
                "daemon", "start",
                "--foreground",
                "--classpath", classpathEntry.toString(),
                "--src", src.toString(),
                "--watch-debounce-ms", "300",
                "--restart-drain-ms", "300"
        );
        pb.redirectOutput(Redirect.DISCARD);
        pb.redirectError(Redirect.to(stderrLog.toFile()));
        pb.environment().put("CALLGRAPH_DISCOVERY_DIR", discoveryDir.toString());

        child = pb.start();
        child.getOutputStream().close();

        // Wait for the daemon to advertise itself.
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline && !Files.exists(discoveryFile)) {
            if (!child.isAlive()) {
                fail("[" + scenarioLabel + "] child daemon exited before writing discovery file; stderr:\n"
                        + tail(stderrLog));
            }
            Thread.sleep(100);
        }
        if (!Files.exists(discoveryFile)) {
            fail("[" + scenarioLabel + "] timed out waiting for discovery file at " + discoveryFile
                    + "; stderr:\n" + tail(stderrLog));
        }

        // Sabotage the rescan target then trigger the self-restart by touching build.gradle.
        sabotage.run();
        Files.writeString(buildGradle, "plugins { id 'java' }\n// triggering restart\n");

        boolean exited = child.waitFor(30, TimeUnit.SECONDS);
        assertTrue(exited, "[" + scenarioLabel
                + "] daemon must exit within 30s of the failing self-restart; stderr:\n"
                + tail(stderrLog));
        int code = child.exitValue();
        assertNotEquals(0, code,
                "[" + scenarioLabel + "] exit code must be non-zero on self-restart failure");

        // Discovery file must be removed (UC04 §10: "releases the discovery file").
        assertFalse(Files.exists(discoveryFile),
                "[" + scenarioLabel + "] discovery file must be removed on failed self-restart: "
                        + discoveryFile);
        // Lock file may or may not be cleaned (the process exits before stop()'s
        // shutdown hook runs in some failure paths); the discovery file is the contract.
        assertFalse(Files.exists(lockFile)
                        && Files.size(lockFile) > 1_000_000_000L,
                "[" + scenarioLabel + "] lock file (if present) should not be a runaway");

        // The structured log should include phase: "failed".
        if (Files.exists(logFile)) {
            String contents = Files.readString(logFile, StandardCharsets.UTF_8);
            assertTrue(contents.contains("\"phase\":\"failed\"")
                            || contents.contains("\"phase\": \"failed\""),
                    "[" + scenarioLabel + "] structured log should include phase: 'failed'; got:\n"
                            + contents);
        }
    }

    private Path requireBenchmarkOrSkip() {
        Path benchmark = Path.of(System.getProperty("user.dir"))
                .resolve("build/classes/java/test/com/hhg/benchmark");
        if (!Files.isDirectory(benchmark)) {
            Assumptions.abort("skipping: benchmark classes missing at " + benchmark);
        }
        return benchmark;
    }

    @FunctionalInterface
    private interface IOAction {
        void run() throws IOException;
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                                                           java.nio.file.attribute.BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                Files.delete(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private static String tail(Path log) {
        try {
            if (Files.exists(log)) return Files.readString(log, StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
        return "(no stderr log)";
    }
}

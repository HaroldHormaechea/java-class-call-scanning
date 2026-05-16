package com.hhg.callgraph.daemon.watch;

import com.google.gson.JsonObject;
import com.hhg.callgraph.daemon.IndexSnapshot;
import com.hhg.callgraph.daemon.RebuildCoordinator;
import com.hhg.callgraph.daemon.ScanMeta;
import com.hhg.callgraph.daemon.ScopeConfig;
import com.hhg.callgraph.model.MethodReference;
import com.hhg.callgraph.scanner.ClassFileScanner;
import com.hhg.callgraph.scanner.ScanResult;
import com.hhg.callgraph.scanner.test.JUnit5TestDetector;
import com.hhg.callgraph.scanner.test.SpockTestDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.hhg.callgraph.daemon.watch.LogFileAssertions.awaitLogLine;
import static com.hhg.callgraph.daemon.watch.LogFileAssertions.rebuildOutcome;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UC04 AC #17(b) — replacing a JAR under classpath roots rebuilds and drops the
 * classes that were in the old JAR but no longer present in the new JAR.
 *
 * <p>End-to-end through the watcher: the JAR sits next to a directory we register; the
 * watcher classifies the JAR as a {@link WatchEventKind#CLASSPATH_ARCHIVE} event when
 * its file is replaced, then the incremental rebuilder rescans the archive and removes
 * any class that's no longer in it.
 */
@DisplayName("FileWatcher — JAR replacement under classpath roots (UC04 AC #17(b))")
class FileWatcherJarReplacementTest {

    @Test
    @DisplayName("replacing a JAR removes obsolete classes and adds the new ones")
    void jarReplacement(@TempDir Path tmp) throws IOException {
        // Setup: the JAR itself is the classpath entry (single-file watch via parent dir).
        Path source = tmp.resolve("src");
        Files.createDirectories(source);
        Path buildGradle = tmp.resolve("build.gradle");
        Files.writeString(buildGradle, "plugins { id 'java' }\n");
        Path logFile = tmp.resolve("watcher.log");

        // Build the initial JAR with TWO benchmark classes inside.
        Path jar = tmp.resolve("classpath.jar");
        Path benchmarkRoot = Path.of(System.getProperty("user.dir"))
                .resolve("build/classes/java/test/com/hhg/benchmark");
        byte[] userService = Files.readAllBytes(benchmarkRoot.resolve("service/UserService.class"));
        byte[] customerService = Files.readAllBytes(benchmarkRoot.resolve("service/CustomerService.class"));
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            addEntry(zos, "com/hhg/benchmark/service/UserService.class", userService);
            addEntry(zos, "com/hhg/benchmark/service/CustomerService.class", customerService);
        }

        // Bootstrap a fixture-like environment with the JAR (not a dir) as classpath.
        ScopeConfig scope = ScopeConfig.of(List.of(jar), List.of(source), List.of(), List.of());
        ScanResult initial = new ClassFileScanner(List.of(
                new JUnit5TestDetector(), new SpockTestDetector())).scanPath(jar);
        ScanMeta meta = new ScanMeta(initial.callGraph().methodCount(),
                initial.callGraph().edgeCount(), 5L, System.currentTimeMillis());
        AtomicReference<IndexSnapshot> ref = new AtomicReference<>(
                new IndexSnapshot(initial, scope, meta));

        assertTrue(hasClass(ref.get(), "com/hhg/benchmark/service/UserService"),
                "initial index must contain UserService");
        assertTrue(hasClass(ref.get(), "com/hhg/benchmark/service/CustomerService"),
                "initial index must contain CustomerService");

        RotatingLogFile rotatingLog = new RotatingLogFile(logFile, 10_000_000L, 5);
        RebuildLogger logger = new RebuildLogger(rotatingLog);
        WatcherStatusHolder holder = new WatcherStatusHolder(true, 250, logFile, 1);
        WatcherConfig config = new WatcherConfig(true, 250,
                WatcherConfig.DEFAULT_RESTART_DRAIN_MS, logFile, 10_000_000L, 5);
        // build-config events aren't expected in this test (we only touch the JAR);
        // a daemon-less SelfRestart is safe because restart() is never invoked.
        SelfRestart sr = new SelfRestart(null, logger);
        IncrementalRebuilder rebuilder = new IncrementalRebuilder(
                ref, new RebuildCoordinator(), scope);
        FileWatcher watcher = new FileWatcher(scope, config, logger, holder,
                rebuilder, sr, ref::get);
        try {
            watcher.register();
            watcher.start();

            // Replace the JAR atomically: write to .tmp then move-in-place.
            Path replacement = tmp.resolve("classpath.jar.tmp");
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(replacement))) {
                // Drop CustomerService; keep UserService; add a third class.
                byte[] orderService = Files.readAllBytes(benchmarkRoot.resolve("service/OrderService.class"));
                addEntry(zos, "com/hhg/benchmark/service/UserService.class", userService);
                addEntry(zos, "com/hhg/benchmark/service/OrderService.class", orderService);
            }
            Files.move(replacement, jar, StandardCopyOption.REPLACE_EXISTING);

            JsonObject line = awaitLogLine(logFile, rebuildOutcome("success"),
                    Duration.ofSeconds(15));
            assertNotNull(line);

            // After the rebuild, CustomerService is gone and OrderService is in.
            IndexSnapshot fresh = ref.get();
            assertTrue(hasClass(fresh, "com/hhg/benchmark/service/UserService"),
                    "UserService should still be present (still in the new JAR)");
            assertFalse(hasClass(fresh, "com/hhg/benchmark/service/CustomerService"),
                    "CustomerService must be removed — it's no longer in the new JAR");
            assertTrue(hasClass(fresh, "com/hhg/benchmark/service/OrderService"),
                    "OrderService should be present (added in the new JAR)");

            // scope.archives should contain the JAR path.
            boolean archiveListed = false;
            for (var el : line.get("scope.archives").getAsJsonArray()) {
                if (el.getAsString().endsWith("classpath.jar")) {
                    archiveListed = true; break;
                }
            }
            assertTrue(archiveListed, "scope.archives must list the modified JAR; got "
                    + line.get("scope.archives"));
        } finally {
            watcher.stop();
        }
    }

    /**
     * Authoritative "is this class scanned" check uses the {@code classOrigins} map.
     * The call graph deliberately retains incoming edges as callee placeholders
     * (documented {@link com.hhg.callgraph.model.CallGraph#removeClass} trade-off);
     * those don't represent a scanned class — they're ghost edges cleaned up the
     * next time the caller class is rebuilt.
     */
    private static boolean hasClass(IndexSnapshot snap, String internalName) {
        return snap.scan().classOrigins().containsKey(internalName);
    }

    private static void addEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }
}

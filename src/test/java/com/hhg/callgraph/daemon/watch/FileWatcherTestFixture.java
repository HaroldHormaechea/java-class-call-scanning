package com.hhg.callgraph.daemon.watch;

import com.hhg.callgraph.daemon.IndexSnapshot;
import com.hhg.callgraph.daemon.RebuildCoordinator;
import com.hhg.callgraph.daemon.ScanMeta;
import com.hhg.callgraph.daemon.ScopeConfig;
import com.hhg.callgraph.scanner.ClassFileScanner;
import com.hhg.callgraph.scanner.ScanResult;
import com.hhg.callgraph.scanner.test.JUnit5TestDetector;
import com.hhg.callgraph.scanner.test.SpockTestDetector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared scaffolding used by every {@code FileWatcher*Test} in this package.
 *
 * <p>Builds a real {@link FileWatcher} wired up against:
 * <ul>
 *   <li>An on-disk classpath directory (caller-supplied) seeded with a single class file
 *       copied from the benchmark fixture.</li>
 *   <li>An on-disk source root (caller-supplied) for {@code .java} watching.</li>
 *   <li>An on-disk fake build.gradle (caller-supplied) for build-config watching.</li>
 *   <li>A real {@link IncrementalRebuilder} + {@link RebuildCoordinator} so rebuilds
 *       go all the way through to a swapped {@link IndexSnapshot}.</li>
 *   <li>A real {@link RotatingLogFile} + {@link RebuildLogger} so tests can poll the
 *       JSON-lines log via {@link LogFileAssertions}.</li>
 * </ul>
 *
 * <p>The {@link SelfRestart} hook is replaced with a no-op stub by default — tests that
 * actually want to drive the self-restart pipeline construct their own
 * {@link FileWatcherSelfRestartHappyPathTest}-style harness against a {@link com.hhg.callgraph.daemon.Daemon}.
 */
public final class FileWatcherTestFixture implements AutoCloseable {

    public final Path classpathDir;
    public final Path sourceDir;
    public final Path buildGradleFile;
    public final Path logFile;
    public final ScopeConfig scope;
    public final WatcherConfig config;
    public final RebuildLogger logger;
    public final WatcherStatusHolder statusHolder;
    public final AtomicReference<IndexSnapshot> indexRef;
    public final IncrementalRebuilder rebuilder;
    public final SelfRestart selfRestart;
    public final FileWatcher watcher;
    public final RotatingLogFile rotatingLog;

    public final java.util.List<java.util.List<Path>> buildConfigCallbacks = new java.util.ArrayList<>();

    /**
     * Builds the fixture. {@code classpath} must already contain at least one
     * {@code .class} file. {@code sourceDir} and {@code buildGradle} must already exist.
     */
    public FileWatcherTestFixture(Path classpath, Path sourceDir, Path buildGradle,
                                  Path logFile, long debounceMs) throws IOException {
        this.classpathDir = classpath;
        this.sourceDir = sourceDir;
        this.buildGradleFile = buildGradle;
        this.logFile = logFile;

        this.scope = ScopeConfig.of(List.of(classpath), List.of(sourceDir),
                List.of(), List.of());
        ScanResult scan = new ClassFileScanner(List.of(
                new JUnit5TestDetector(), new SpockTestDetector())).scan(classpath);
        ScanMeta meta = new ScanMeta(scan.callGraph().methodCount(),
                scan.callGraph().edgeCount(), 5L, System.currentTimeMillis());
        this.indexRef = new AtomicReference<>(new IndexSnapshot(scan, scope, meta));
        this.config = new WatcherConfig(true, debounceMs,
                WatcherConfig.DEFAULT_RESTART_DRAIN_MS,
                logFile, WatcherConfig.DEFAULT_LOG_MAX_SIZE_BYTES,
                WatcherConfig.DEFAULT_LOG_MAX_FILES);
        this.rotatingLog = new RotatingLogFile(logFile, config.logMaxSizeBytes(),
                config.logMaxFiles());
        this.logger = new RebuildLogger(rotatingLog);
        this.statusHolder = new WatcherStatusHolder(true, debounceMs, logFile, 1);

        RebuildCoordinator coord = new RebuildCoordinator();
        this.rebuilder = new IncrementalRebuilder(indexRef, coord, scope);

        // SelfRestart is final and its restart() invokes daemon.restartInPlace, which
        // would NPE with a null daemon. None of the fixture-based tests modify a
        // build-config file after the watcher starts (they only touch class files,
        // archives, or .java files), so restart() is never invoked. The instance is
        // wired up just so the FileWatcher constructor accepts a non-null value.
        this.selfRestart = new SelfRestart(/*daemon=*/ null, /*logger=*/ logger);

        this.watcher = new FileWatcher(scope, config, logger, statusHolder,
                rebuilder, selfRestart, indexRef::get);
        this.watcher.register();
        this.watcher.start();
    }

    /** Copies a class file from the benchmark fixture into the given destination. */
    public static void seedClass(Path dst, String relPathFromBenchmark) throws IOException {
        Path src = Path.of(System.getProperty("user.dir"))
                .resolve("build/classes/java/test/com/hhg/benchmark/" + relPathFromBenchmark);
        if (!Files.exists(src)) {
            throw new IOException("seed class not found at " + src
                    + " — run `./gradlew compileTestJava` first");
        }
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void close() {
        try {
            if (watcher != null) watcher.stop();
        } catch (Throwable ignored) {}
        try {
            if (rotatingLog != null) rotatingLog.close();
        } catch (Throwable ignored) {}
    }
}

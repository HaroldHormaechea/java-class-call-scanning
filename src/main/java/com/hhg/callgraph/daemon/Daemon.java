package com.hhg.callgraph.daemon;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hhg.callgraph.BuildVersion;
import com.hhg.callgraph.daemon.mcp.McpStdioServer;
import com.hhg.callgraph.daemon.op.OperationResult;
import com.hhg.callgraph.daemon.op.Operations;
import com.hhg.callgraph.daemon.tcp.ConnectionHandler;
import com.hhg.callgraph.daemon.tcp.TcpServer;
import com.hhg.callgraph.daemon.watch.FileWatcher;
import com.hhg.callgraph.daemon.watch.IncrementalRebuilder;
import com.hhg.callgraph.daemon.watch.LaunchArgs;
import com.hhg.callgraph.daemon.watch.RebuildLogger;
import com.hhg.callgraph.daemon.watch.RotatingLogFile;
import com.hhg.callgraph.daemon.watch.SelfRestart;
import com.hhg.callgraph.daemon.watch.WatcherConfig;
import com.hhg.callgraph.daemon.watch.WatcherStatusHolder;
import com.hhg.callgraph.scanner.ClassFileScanner;
import com.hhg.callgraph.scanner.ScanResult;
import com.hhg.callgraph.scanner.test.JUnit5TestDetector;
import com.hhg.callgraph.scanner.test.SpockTestDetector;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime singleton for one daemon process. Owns:
 *
 * <ul>
 *   <li>The {@link IndexSnapshot} atomic reference (read by every query).</li>
 *   <li>The {@link RebuildCoordinator} that serializes rebuilds.</li>
 *   <li>The {@link TcpServer} on loopback.</li>
 *   <li>The {@link McpStdioServer} (Anthropic MCP Java SDK).</li>
 *   <li>Optional {@link IdleWatchdog}.</li>
 *   <li>The {@link FileWatcher} when UC04 auto-watch is enabled.</li>
 *   <li>The JVM shutdown hook that removes the discovery file.</li>
 * </ul>
 */
public final class Daemon implements ConnectionHandler.TcpOps {

    public static final String DAEMON_VERSION = BuildVersion.value();
    public static final int LOG_SCHEMA_VERSION = 1;

    private final ScopeConfig scope;
    private final Discovery discovery;
    private final AtomicReference<IndexSnapshot> indexRef = new AtomicReference<>();
    private final RebuildCoordinator rebuildCoordinator = new RebuildCoordinator();
    private TcpServer tcpServer;
    private final McpStdioServer mcpServer;
    private final IdleWatchdog watchdog;     // nullable
    private Operations operations;
    private final FileLock projectLock;      // held for the daemon's lifetime
    private final FileChannel projectLockChannel;
    private long startedAtEpochMillis;
    private volatile boolean shutdownRequested;

    private final LaunchArgs launchArgs;
    private final WatcherStatusHolder statusHolder;
    private final RebuildLogger rebuildLogger;
    private final RotatingLogFile rotatingLog;     // nullable when watcher disabled
    private FileWatcher fileWatcher;               // nullable when watcher disabled
    private final AtomicBoolean restartInProgress = new AtomicBoolean(false);

    private Daemon(LaunchArgs launchArgs, Discovery discovery,
                   FileLock projectLock, FileChannel projectLockChannel,
                   long startedAtEpochMillis) throws IOException {
        this.launchArgs = launchArgs;
        this.scope = launchArgs.scope();
        this.discovery = discovery;
        this.projectLock = projectLock;
        this.projectLockChannel = projectLockChannel;
        this.startedAtEpochMillis = startedAtEpochMillis;

        WatcherConfig wc = launchArgs.watcherConfig();
        Path logFilePath = wc != null && wc.logFile() != null
                ? wc.logFile() : discovery.logPath(scope.projectHash());
        boolean watcherOn = wc != null && wc.enabled();
        long debounce = wc != null ? wc.debounceMs() : WatcherConfig.DEFAULT_DEBOUNCE_MS;
        long maxSize = wc != null ? wc.logMaxSizeBytes() : WatcherConfig.DEFAULT_LOG_MAX_SIZE_BYTES;
        int maxFiles = wc != null ? wc.logMaxFiles() : WatcherConfig.DEFAULT_LOG_MAX_FILES;

        this.statusHolder = new WatcherStatusHolder(watcherOn, debounce, logFilePath, LOG_SCHEMA_VERSION);
        this.rotatingLog = new RotatingLogFile(logFilePath, maxSize, maxFiles);
        this.rebuildLogger = new RebuildLogger(rotatingLog);

        this.operations = newOperations();
        this.watchdog = launchArgs.idleMinutes() > 0
                ? new IdleWatchdog(launchArgs.idleMinutes(), this::onIdleShutdown)
                : null;

        this.tcpServer = new TcpServer(operations, this, watchdog);
        this.mcpServer = new McpStdioServer(operations, watchdog);
    }

    private Operations newOperations() {
        return new Operations(indexRef::get, this::doRescan,
                statusHolder::snapshot, restartInProgress::get);
    }

    /**
     * Builds and starts a daemon for the given scope. Performs the initial scan,
     * binds TCP, starts MCP, writes the discovery file, registers the shutdown
     * hook, optionally starts the idle watchdog.
     *
     * <p>Compatibility shim — see {@link #startForScope(LaunchArgs, Discovery, boolean)}.
     *
     * @throws Discovery.AlreadyRunningException if a live daemon already owns this hash
     *         and {@code force} is false.
     */
    public static Daemon startForScope(ScopeConfig scope, Discovery discovery,
                                       int idleMinutes, boolean force) throws IOException {
        // Default to watcher-disabled for callers that haven't migrated to LaunchArgs.
        Path logPath = discovery.logPath(scope.projectHash());
        LaunchArgs args = new LaunchArgs(scope, idleMinutes, true,
                WatcherConfig.disabled(logPath));
        return startForScope(args, discovery, force);
    }

    /**
     * Builds and starts a daemon for the given launch arguments. UC04 entry point.
     */
    public static Daemon startForScope(LaunchArgs launchArgs, Discovery discovery,
                                       boolean force) throws IOException {
        ScopeConfig scope = launchArgs.scope();
        discovery.ensureDirectory();

        // One-daemon-per-project: acquire the project lock first.
        Path lockPath = discovery.lockPath(scope.projectHash());
        FileChannel lockChannel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock lock = lockChannel.tryLock();
        if (lock == null) {
            lockChannel.close();
            throw new IOException("another daemon holds the project lock: " + lockPath);
        }

        // Inspect existing discovery file.
        Path discoveryFile = discovery.filePath(scope.projectHash());
        if (Files.exists(discoveryFile)) {
            Discovery.Record existing = Discovery.read(discoveryFile).orElse(null);
            if (existing != null && Discovery.pidAlive(existing.pid())) {
                if (!force) {
                    try { lock.release(); } catch (IOException ignored) {}
                    try { lockChannel.close(); } catch (IOException ignored) {}
                    Discovery.AlreadyRunningException are =
                            new Discovery.AlreadyRunningException(discoveryFile,
                                    existing.pid(), existing.port());
                    throw are;
                }
                sendShutdown(existing.port());
                waitForFileGone(discoveryFile, 5_000);
            } else {
                System.err.println("Removing stale discovery file: " + discoveryFile);
                Files.deleteIfExists(discoveryFile);
            }
        }

        long startedAt = System.currentTimeMillis();
        Daemon daemon = new Daemon(launchArgs, discovery, lock, lockChannel, startedAt);

        // Initial scan.
        IndexSnapshot first = daemon.scanIntoSnapshot();
        daemon.indexRef.set(first);
        daemon.statusHolder.recordRebuild("manual", "success",
                first.meta().scanMillis(),
                first.scan().classOrigins().size(),
                0);
        daemon.statusHolder.clearError();

        // Source-root overlap warning (post-scan; cheap).
        warnIfOverlap(scope.sourceRoots());

        // Wire up FileWatcher BEFORE TCP advertises so AC #1 is honoured.
        if (launchArgs.watcherConfig() != null && launchArgs.watcherConfig().enabled()) {
            IncrementalRebuilder rebuilder = new IncrementalRebuilder(
                    daemon.indexRef, daemon.rebuildCoordinator, scope);
            SelfRestart sr = new SelfRestart(daemon, daemon.rebuildLogger);
            daemon.fileWatcher = new FileWatcher(scope, launchArgs.watcherConfig(),
                    daemon.rebuildLogger, daemon.statusHolder, rebuilder, sr,
                    daemon.indexRef::get);
            try {
                daemon.fileWatcher.register();
            } catch (IOException io) {
                System.err.println("daemon: watcher registration failed: " + io);
            }
        } else {
            daemon.statusHolder.setDisabled();
        }

        // Bind TCP + boot MCP.
        daemon.tcpServer.start();

        // Write discovery file with bound port.
        daemon.discovery.write(scope, ProcessHandle.current().pid(),
                daemon.tcpServer.port(), startedAt);

        if (daemon.watchdog != null) {
            daemon.watchdog.start();
        }

        // Start the watcher's debounce loop now that the surfaces are up.
        if (daemon.fileWatcher != null) {
            daemon.fileWatcher.start();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(daemon::stop, "daemon-shutdown"));

        System.err.println("daemon ready: project_hash=" + scope.projectHash()
                + " pid=" + ProcessHandle.current().pid()
                + " port=" + daemon.tcpServer.port()
                + " methods=" + first.meta().methodCount()
                + " edges=" + first.meta().edgeCount()
                + " scan_ms=" + first.meta().scanMillis()
                + " watcher=" + (daemon.fileWatcher != null ? "on" : "off"));

        return daemon;
    }

    private static void warnIfOverlap(List<Path> roots) {
        for (int i = 0; i < roots.size(); i++) {
            for (int j = i + 1; j < roots.size(); j++) {
                Path a = roots.get(i), b = roots.get(j);
                if (a.startsWith(b) || b.startsWith(a)) {
                    System.err.println("warning: overlapping source roots detected: "
                            + a + " vs " + b);
                }
            }
        }
    }

    /**
     * Performs a fresh scan over the daemon's frozen scope and returns the new snapshot.
     * Caller is responsible for holding the rebuild lock.
     */
    private IndexSnapshot scanIntoSnapshot() throws IOException {
        long start = System.nanoTime();
        ClassFileScanner scanner = new ClassFileScanner(List.of(
                new JUnit5TestDetector(),
                new SpockTestDetector()
        ));
        ScanResult result = scanner.scanPaths(scope.classpath());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        ScanMeta meta = new ScanMeta(
                result.callGraph().methodCount(),
                result.callGraph().edgeCount(),
                elapsedMs,
                System.currentTimeMillis());
        return new IndexSnapshot(result, scope, meta);
    }

    /** Implementation of {@link Operations.Refresher} — called by the refresh-index op. */
    public JsonObject doRescan() {
        rebuildCoordinator.lock();
        try {
            IndexSnapshot fresh = scanIntoSnapshot();
            indexRef.set(fresh);
            // UC04 §5 / §15: a successful manual rebuild records "manual" + clears error.
            statusHolder.recordRebuild("manual", "success",
                    fresh.meta().scanMillis(),
                    fresh.scan().classOrigins().size(),
                    0);
            statusHolder.clearError();
            emitManualRebuildLog(fresh);
            JsonObject out = new JsonObject();
            out.addProperty("status", "ok");
            out.addProperty("methods", fresh.meta().methodCount());
            out.addProperty("edges",   fresh.meta().edgeCount());
            out.addProperty("scan_ms", fresh.meta().scanMillis());
            return out;
        } catch (IOException e) {
            statusHolder.recordError(e);
            throw new RuntimeException("rescan failed: " + e.getMessage(), e);
        } finally {
            rebuildCoordinator.unlock();
        }
    }

    private void emitManualRebuildLog(IndexSnapshot fresh) {
        JsonObject obj = new JsonObject();
        obj.addProperty("event", "watch.rebuild");
        obj.addProperty("schema_version", LOG_SCHEMA_VERSION);
        obj.addProperty("trigger.watcher", "manual");
        obj.add("trigger.paths", new JsonArray());
        obj.addProperty("trigger.event_count", 1);
        obj.add("scope.classes", new JsonArray());
        obj.add("scope.archives", new JsonArray());
        obj.addProperty("outcome", "success");
        obj.addProperty("elapsed_ms", fresh.meta().scanMillis());
        obj.addProperty("index.methods_before", fresh.meta().methodCount());
        obj.addProperty("index.methods_after", fresh.meta().methodCount());
        obj.addProperty("index.edges_before", fresh.meta().edgeCount());
        obj.addProperty("index.edges_after", fresh.meta().edgeCount());
        obj.addProperty("timestamp", java.time.Instant.now().toString());
        if (rebuildLogger != null) rebuildLogger.emit(obj);
    }

    // -----------------------------------------------------------------------
    // Self-restart (UC04 §10)
    // -----------------------------------------------------------------------

    /**
     * Graceful in-process self-restart triggered by a build-config FS event.
     * The MCP stdio server survives; everything else is rebuilt.
     */
    public void restartInPlace(List<Path> triggeringPaths) {
        long start = System.nanoTime();
        restartInProgress.set(true);
        try {
            // 1. Refuse new TCP connections + drain.
            try {
                tcpServer.stopAccepting();
                long drainMs = launchArgs.watcherConfig() != null
                        ? launchArgs.watcherConfig().restartDrainMs()
                        : WatcherConfig.DEFAULT_RESTART_DRAIN_MS;
                tcpServer.shutdownWithMs(drainMs);
            } catch (Throwable t) {
                System.err.println("daemon-restart: tcp drain failed: " + t);
            }
            // 2. Stop the watcher.
            if (fileWatcher != null) {
                try { fileWatcher.stop(); } catch (Throwable ignored) {}
                fileWatcher = null;
            }
            // 3. Remove the discovery file (lock stays held).
            try { discovery.delete(scope.projectHash()); } catch (Throwable ignored) {}

            // 4. Run the fresh scan — failure here is fatal per UC04 §10. We catch
            // Throwable (not just IOException) because the rescan can throw a wider
            // range of types: e.g. ClassFileScanner.scanPath throws IllegalArgumentException
            // when a classpath entry is no longer a recognised dir/jar/war, and ASM may
            // raise RuntimeExceptions on malformed bytecode. AC #10 says "any" restart-phase
            // failure must route to the fail-fast path — narrow catches would let those
            // leak out of restartInPlace and into the debounce-window's Throwable handler,
            // which only logs to stderr and would leave the daemon half-restarted.
            IndexSnapshot fresh;
            try {
                fresh = scanIntoSnapshot();
            } catch (Throwable t) {
                emitSelfRestartLog("failed", 0,
                        t.getClass().getName() + ": " + t.getMessage(), triggeringPaths);
                System.err.println("daemon-restart: fresh scan failed: " + t);
                try { projectLock.release(); } catch (Throwable ignored) {}
                try { projectLockChannel.close(); } catch (Throwable ignored) {}
                System.exit(11);
                return; // unreachable
            }
            indexRef.set(fresh);

            // 5. Rebuild Operations + re-point the MCP server.
            this.operations = newOperations();
            mcpServer.setOperations(operations);
            statusHolder.recordSelfRestart(
                    (System.nanoTime() - start) / 1_000_000L,
                    fresh.scan().classOrigins().size(), 0);
            statusHolder.clearError();

            // 6. New TCP server. Same Throwable-broadening rationale as step 4:
            // TcpServer construction can fail with non-IOException causes (port
            // exhaustion via SecurityException, thread-factory issues, etc.) and AC #10
            // requires uniform fail-fast on "any" restart-phase failure.
            try {
                this.tcpServer = new TcpServer(operations, this, watchdog);
                this.tcpServer.start();
            } catch (Throwable t) {
                emitSelfRestartLog("failed",
                        (System.nanoTime() - start) / 1_000_000L,
                        t.getClass().getName() + ": " + t.getMessage(), triggeringPaths);
                try { projectLock.release(); } catch (Throwable ignored) {}
                try { projectLockChannel.close(); } catch (Throwable ignored) {}
                System.exit(12);
                return;
            }
            this.startedAtEpochMillis = System.currentTimeMillis();
            try {
                discovery.write(scope, ProcessHandle.current().pid(),
                        tcpServer.port(), startedAtEpochMillis);
            } catch (Throwable t) {
                emitSelfRestartLog("failed",
                        (System.nanoTime() - start) / 1_000_000L,
                        t.getClass().getName() + ": " + t.getMessage(), triggeringPaths);
                try { projectLock.release(); } catch (Throwable ignored) {}
                try { projectLockChannel.close(); } catch (Throwable ignored) {}
                System.exit(13);
                return;
            }

            // 7. New watcher.
            if (launchArgs.watcherConfig() != null && launchArgs.watcherConfig().enabled()) {
                IncrementalRebuilder rebuilder = new IncrementalRebuilder(
                        indexRef, rebuildCoordinator, scope);
                SelfRestart sr = new SelfRestart(this, rebuildLogger);
                this.fileWatcher = new FileWatcher(scope, launchArgs.watcherConfig(),
                        rebuildLogger, statusHolder, rebuilder, sr, indexRef::get);
                try {
                    fileWatcher.register();
                    fileWatcher.start();
                } catch (IOException io) {
                    System.err.println("daemon-restart: watcher re-register failed: " + io);
                }
            }

            long elapsed = (System.nanoTime() - start) / 1_000_000L;
            emitSelfRestartLog("completed", elapsed, null, triggeringPaths);
        } finally {
            restartInProgress.set(false);
        }
    }

    private void emitSelfRestartLog(String phase, long elapsedMs, String reason, List<Path> triggeringPaths) {
        JsonObject obj = new JsonObject();
        obj.addProperty("event", "watch.self_restart");
        obj.addProperty("schema_version", LOG_SCHEMA_VERSION);
        obj.addProperty("trigger.watcher", "build-config");
        JsonArray paths = new JsonArray();
        if (triggeringPaths != null) {
            for (Path p : triggeringPaths) paths.add(p.toAbsolutePath().toString());
        }
        obj.add("trigger.paths", paths);
        obj.addProperty("trigger.event_count",
                triggeringPaths == null ? 0 : triggeringPaths.size());
        obj.addProperty("phase", phase);
        obj.addProperty("outcome", "self-restart");
        if (reason != null) obj.addProperty("outcome.reason", reason);
        obj.addProperty("elapsed_ms", elapsedMs);
        IndexSnapshot live = indexRef.get();
        int methods = live == null ? 0 : live.meta().methodCount();
        int edges   = live == null ? 0 : live.meta().edgeCount();
        obj.addProperty("index.methods_before", methods);
        obj.addProperty("index.methods_after", methods);
        obj.addProperty("index.edges_before", edges);
        obj.addProperty("index.edges_after", edges);
        obj.addProperty("timestamp", java.time.Instant.now().toString());
        if (rebuildLogger != null) rebuildLogger.emit(obj);
    }

    // -----------------------------------------------------------------------
    // TcpOps — ping / shutdown
    // -----------------------------------------------------------------------

    @Override
    public JsonObject ping() {
        JsonObject obj = new JsonObject();
        obj.addProperty("project_hash", scope.projectHash());
        obj.addProperty("pid", ProcessHandle.current().pid());
        obj.addProperty("port", tcpServer.port());
        obj.addProperty("daemon_version", DAEMON_VERSION);
        obj.addProperty("methods", indexRef.get().meta().methodCount());
        obj.addProperty("edges",   indexRef.get().meta().edgeCount());
        return obj;
    }

    @Override
    public OperationResult shutdown() {
        JsonObject ack = new JsonObject();
        ack.addProperty("status", "shutting-down");
        new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            stop();
            System.exit(0);
        }, "daemon-shutdown-trigger").start();
        return OperationResult.ok(ack);
    }

    /**
     * Graceful stop: stop accepting TCP, drain handlers, stop MCP, remove discovery file,
     * release lock. Safe to call from a shutdown hook or signal handler.
     */
    public void stop() {
        if (shutdownRequested) return;
        shutdownRequested = true;
        if (fileWatcher != null) {
            try { fileWatcher.stop(); } catch (Throwable ignored) {}
        }
        try {
            tcpServer.shutdown(3);
        } catch (Throwable t) {
            System.err.println("daemon-shutdown: tcp close failed: " + t);
        }
        try {
            mcpServer.close();
        } catch (Throwable t) {
            System.err.println("daemon-shutdown: mcp close failed: " + t);
        }
        if (watchdog != null) {
            try { watchdog.shutdownPoller(); } catch (Throwable ignored) {}
        }
        try {
            discovery.delete(scope.projectHash());
        } catch (Throwable t) {
            System.err.println("daemon-shutdown: discovery delete failed: " + t);
        }
        try {
            projectLock.release();
        } catch (Throwable ignored) {}
        try {
            projectLockChannel.close();
        } catch (Throwable ignored) {}
        try {
            Files.deleteIfExists(discovery.lockPath(scope.projectHash()));
        } catch (Throwable ignored) {}
    }

    private void onIdleShutdown() {
        System.err.println("daemon: idle timeout elapsed — shutting down");
        stop();
        System.exit(0);
    }

    // -----------------------------------------------------------------------
    // Accessors (for tests / Daemonization)
    // -----------------------------------------------------------------------

    public int tcpPort()          { return tcpServer.port(); }
    public String projectHash()   { return scope.projectHash(); }
    public Operations operations(){ return operations; }
    public IndexSnapshot snapshot() { return indexRef.get(); }
    public long startedAtEpochMillis() { return startedAtEpochMillis; }
    public boolean restartInProgress() { return restartInProgress.get(); }
    public WatcherStatusHolder statusHolder() { return statusHolder; }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /** Sends an internal {@code shutdown} request to a peer daemon on the given port. */
    private static void sendShutdown(int port) {
        try (java.net.Socket s = new java.net.Socket(
                java.net.InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(2000);
            String line = "{\"op\":\"shutdown\"}\n";
            s.getOutputStream().write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            try { s.getInputStream().read(); } catch (IOException ignored) {}
        } catch (IOException e) {
            System.err.println("daemon: --force shutdown request failed: " + e);
        }
    }

    private static void waitForFileGone(Path p, long maxMillis) {
        long deadline = System.currentTimeMillis() + maxMillis;
        while (System.currentTimeMillis() < deadline) {
            if (!Files.exists(p)) return;
            try { Thread.sleep(50); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}

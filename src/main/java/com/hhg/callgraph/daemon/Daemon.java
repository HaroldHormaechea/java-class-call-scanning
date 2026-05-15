package com.hhg.callgraph.daemon;

import com.google.gson.JsonObject;
import com.hhg.callgraph.BuildVersion;
import com.hhg.callgraph.daemon.mcp.McpStdioServer;
import com.hhg.callgraph.daemon.op.OperationResult;
import com.hhg.callgraph.daemon.op.Operations;
import com.hhg.callgraph.daemon.tcp.ConnectionHandler;
import com.hhg.callgraph.daemon.tcp.TcpServer;
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
 *   <li>The JVM shutdown hook that removes the discovery file.</li>
 * </ul>
 */
public final class Daemon implements ConnectionHandler.TcpOps {

    public static final String DAEMON_VERSION = BuildVersion.value();

    private final ScopeConfig scope;
    private final Discovery discovery;
    private final AtomicReference<IndexSnapshot> indexRef = new AtomicReference<>();
    private final RebuildCoordinator rebuildCoordinator = new RebuildCoordinator();
    private final TcpServer tcpServer;
    private final McpStdioServer mcpServer;
    private final IdleWatchdog watchdog;     // nullable
    private final Operations operations;
    private final FileLock projectLock;      // held for the daemon's lifetime
    private final FileChannel projectLockChannel;
    private final long startedAtEpochMillis;
    private volatile boolean shutdownRequested;

    private Daemon(ScopeConfig scope, Discovery discovery,
                   FileLock projectLock, FileChannel projectLockChannel,
                   int idleMinutes, long startedAtEpochMillis) throws IOException {
        this.scope = scope;
        this.discovery = discovery;
        this.projectLock = projectLock;
        this.projectLockChannel = projectLockChannel;
        this.startedAtEpochMillis = startedAtEpochMillis;

        this.operations = new Operations(indexRef::get, this::doRescan);
        this.watchdog = idleMinutes > 0
                ? new IdleWatchdog(idleMinutes, this::onIdleShutdown)
                : null;

        this.tcpServer = new TcpServer(operations, this, watchdog);
        this.mcpServer = new McpStdioServer(operations, watchdog);
    }

    /**
     * Builds and starts a daemon for the given scope. Performs the initial scan,
     * binds TCP, starts MCP, writes the discovery file, registers the shutdown
     * hook, optionally starts the idle watchdog.
     *
     * @throws Discovery.AlreadyRunningException if a live daemon already owns this hash
     *         and {@code force} is false.
     */
    public static Daemon startForScope(ScopeConfig scope, Discovery discovery,
                                       int idleMinutes, boolean force) throws IOException {
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
                    // Release lock and surface a typed error.
                    try { lock.release(); } catch (IOException ignored) {}
                    try { lockChannel.close(); } catch (IOException ignored) {}
                    Discovery.AlreadyRunningException are =
                            new Discovery.AlreadyRunningException(discoveryFile,
                                    existing.pid(), existing.port());
                    throw are;
                }
                // --force: request shutdown of the live daemon, wait briefly for it to exit.
                sendShutdown(existing.port());
                waitForFileGone(discoveryFile, 5_000);
            } else {
                // Stale file from a dead PID — log and remove.
                System.err.println("Removing stale discovery file: " + discoveryFile);
                Files.deleteIfExists(discoveryFile);
            }
        }

        long startedAt = System.currentTimeMillis();
        Daemon daemon = new Daemon(scope, discovery, lock, lockChannel, idleMinutes, startedAt);

        // Initial scan.
        IndexSnapshot first = daemon.scanIntoSnapshot();
        daemon.indexRef.set(first);

        // Source-root overlap warning (post-scan; cheap).
        warnIfOverlap(scope.sourceRoots());

        // Bind TCP + boot MCP. (MCP server's transport is constructed eagerly; reads stdin lazily.)
        daemon.tcpServer.start();

        // Write discovery file with bound port.
        daemon.discovery.write(scope, ProcessHandle.current().pid(),
                daemon.tcpServer.port(), startedAt);

        // Idle watchdog (opt-in).
        if (daemon.watchdog != null) {
            daemon.watchdog.start();
        }

        // Shutdown hook: clean up discovery file + close servers.
        Runtime.getRuntime().addShutdownHook(new Thread(daemon::stop, "daemon-shutdown"));

        System.err.println("daemon ready: project_hash=" + scope.projectHash()
                + " pid=" + ProcessHandle.current().pid()
                + " port=" + daemon.tcpServer.port()
                + " methods=" + first.meta().methodCount()
                + " edges=" + first.meta().edgeCount()
                + " scan_ms=" + first.meta().scanMillis());

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
            JsonObject out = new JsonObject();
            out.addProperty("status", "ok");
            out.addProperty("methods", fresh.meta().methodCount());
            out.addProperty("edges",   fresh.meta().edgeCount());
            out.addProperty("scan_ms", fresh.meta().scanMillis());
            return out;
        } catch (IOException e) {
            throw new RuntimeException("rescan failed: " + e.getMessage(), e);
        } finally {
            rebuildCoordinator.unlock();
        }
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
        // Acknowledge first; perform the actual stop after the reply is sent.
        JsonObject ack = new JsonObject();
        ack.addProperty("status", "shutting-down");
        new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            stop();
            // Best-effort process exit (works for foreground; the JVM may otherwise hold
            // on stdin until MCP closes).
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
            // Drain (response is small, don't care about content).
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

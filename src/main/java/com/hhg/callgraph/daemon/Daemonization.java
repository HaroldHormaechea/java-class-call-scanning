package com.hhg.callgraph.daemon;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the foreground / background daemon launch path. {@code --foreground} (and the
 * hidden {@code --internal-spawned} marker used by the parent → child re-exec) keep the
 * daemon attached to the current JVM. The background mode spawns a child JVM with the
 * same classpath + main class + flags + {@code --internal-spawned} + {@code --foreground},
 * redirects its stderr to {@code <project-hash>.log}, then polls the discovery file for
 * up to {@code DISCOVERY_TIMEOUT_MILLIS} before exiting.
 */
public final class Daemonization {

    public static final long DISCOVERY_TIMEOUT_MILLIS = 10_000;

    private Daemonization() {}

    /**
     * Re-execs the current JVM in foreground+internal-spawned mode. Returns the discovery
     * record after the child writes it, or throws on timeout.
     */
    public static Discovery.Record spawnBackgroundAndAwait(ScopeConfig scope, Discovery discovery,
                                                          List<String> originalArgs)
            throws IOException {
        discovery.ensureDirectory();
        Path logPath = discovery.logPath(scope.projectHash());

        // Build the child command: same java + classpath + main, with --foreground +
        // --internal-spawned + the original user flags (minus --foreground if present).
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBinary());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("com.hhg.callgraph.CallGraphBuilder");
        cmd.add("daemon");
        cmd.add("start");
        cmd.add("--foreground");
        cmd.add("--internal-spawned");
        for (String arg : originalArgs) {
            if (arg.equals("--foreground") || arg.equals("--internal-spawned")) continue;
            cmd.add(arg);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.appendTo(logPath.toFile()));
        Process child = pb.start();

        // Detach stdin so the child doesn't inherit ours (avoids stdin contention with MCP).
        try { child.getOutputStream().close(); } catch (IOException ignored) {}

        long deadline = System.currentTimeMillis() + DISCOVERY_TIMEOUT_MILLIS;
        Path discoveryFile = discovery.filePath(scope.projectHash());
        while (System.currentTimeMillis() < deadline) {
            if (!child.isAlive()) {
                throw new IOException("daemon child exited prematurely (exit="
                        + child.exitValue() + ") — see " + logPath);
            }
            if (Files.exists(discoveryFile)) {
                Discovery.Record rec = Discovery.read(discoveryFile).orElse(null);
                if (rec != null) return rec;
            }
            try { Thread.sleep(50); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IOException("timed out waiting for daemon to write discovery file at "
                + discoveryFile + " (log: " + logPath + ")");
    }

    private static String javaBinary() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null) return "java";
        String os = System.getProperty("os.name", "").toLowerCase();
        String exe = os.contains("win") ? "java.exe" : "java";
        return Path.of(javaHome, "bin", exe).toString();
    }

    /** Detect whether the current JVM is the foreground portion of a backgrounded spawn. */
    public static boolean wasInternallySpawned(List<String> args) {
        return args.contains("--internal-spawned");
    }

    /** Best-effort PID lookup using {@link ProcessHandle}. */
    public static long currentPid() {
        try {
            return ProcessHandle.current().pid();
        } catch (Throwable t) {
            String name = ManagementFactory.getRuntimeMXBean().getName();
            int at = name.indexOf('@');
            return at > 0 ? Long.parseLong(name.substring(0, at)) : -1L;
        }
    }
}

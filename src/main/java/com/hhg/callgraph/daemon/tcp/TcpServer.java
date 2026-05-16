package com.hhg.callgraph.daemon.tcp;

import com.hhg.callgraph.daemon.IdleWatchdog;
import com.hhg.callgraph.daemon.op.Operations;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loopback-only TCP server. Binds to {@code 127.0.0.1} on an OS-assigned ephemeral
 * port; non-loopback binds are refused by construction (we never pass a non-loopback
 * address). Uses a cached thread pool for connection handling — concurrent reads scale
 * via this pool while the daemon's read path is lock-free (reads only the
 * {@code AtomicReference<IndexSnapshot>}).
 *
 * <p>The proposal originally specified a virtual-thread executor; that requires Java 21
 * runtime, but the project's Gradle wrapper (8.0) currently caps support at Java 19.
 * A cached pool is functionally close — see implementation notes.
 */
public final class TcpServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final ExecutorService executor;
    private final Operations operations;
    private final ConnectionHandler.TcpOps tcpOps;
    private final IdleWatchdog watchdog;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private Thread acceptorThread;

    public TcpServer(Operations operations,
                     ConnectionHandler.TcpOps tcpOps,
                     IdleWatchdog watchdog) throws IOException {
        this.operations = operations;
        this.tcpOps = tcpOps;
        this.watchdog = watchdog;
        this.serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "tcp-conn");
            t.setDaemon(true);
            return t;
        });
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public InetAddress boundAddress() {
        return serverSocket.getInetAddress();
    }

    /** Starts the accept loop on a daemon thread. */
    public void start() {
        acceptorThread = new Thread(this::acceptLoop, "tcp-acceptor");
        acceptorThread.setDaemon(true);
        acceptorThread.start();
    }

    private void acceptLoop() {
        while (accepting.get() && !serverSocket.isClosed()) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (accepting.get()) {
                    System.err.println("tcp-acceptor: accept failed: " + e);
                }
                return;
            }
            ConnectionHandler handler = new ConnectionHandler(
                    socket, operations, tcpOps, watchdog,
                    err -> System.err.println("tcp-connection: " + err));
            try {
                executor.execute(handler);
            } catch (Throwable t) {
                System.err.println("tcp-acceptor: failed to dispatch handler: " + t);
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }

    /** Stops accepting new connections; in-flight handlers keep running. */
    public void stopAccepting() {
        accepting.set(false);
        try {
            serverSocket.close();
        } catch (IOException ignored) {}
    }

    /** Stops accepting + tries to drain handlers for {@code graceSeconds}. */
    public void shutdown(int graceSeconds) {
        shutdownWithMs(graceSeconds * 1000L);
    }

    /**
     * Stops accepting + tries to drain handlers for {@code graceMs} milliseconds; after
     * the deadline, force-closes remaining handlers via
     * {@link java.util.concurrent.ExecutorService#shutdownNow()}. Used by the
     * self-restart pipeline (UC04 §6) which needs sub-second precision.
     */
    public void shutdownWithMs(long graceMs) {
        stopAccepting();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(Math.max(0L, graceMs),
                    java.util.concurrent.TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    @Override
    public void close() {
        shutdown(3);
    }
}

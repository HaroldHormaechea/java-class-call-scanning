package com.hhg.callgraph.daemon;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes index rebuilds. Readers never touch this lock — they read the
 * {@link IndexSnapshot} via the daemon's {@code AtomicReference}. Only the
 * orchestrator of {@code refresh-index} (and the initial scan) holds the lock.
 */
public final class RebuildCoordinator {

    private final ReentrantLock lock = new ReentrantLock();

    public void lock()   { lock.lock(); }
    public void unlock() { lock.unlock(); }

    public boolean isHeldByCurrentThread() {
        return lock.isHeldByCurrentThread();
    }
}

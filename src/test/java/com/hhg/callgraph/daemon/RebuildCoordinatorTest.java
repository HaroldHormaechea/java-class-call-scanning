package com.hhg.callgraph.daemon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers AC #12 — rebuilds are serialized; two concurrent {@code refresh-index} requests
 * never run in parallel. We model the rebuild-critical-section with a counter that the
 * coordinator must not allow to exceed 1 at any time.
 */
@DisplayName("RebuildCoordinator — mutual exclusion of rebuilds")
class RebuildCoordinatorTest {

    @Test
    @DisplayName("isHeldByCurrentThread is false before lock and true after")
    void heldByCurrentThread() {
        RebuildCoordinator coord = new RebuildCoordinator();
        assertFalse(coord.isHeldByCurrentThread());
        coord.lock();
        try {
            assertTrue(coord.isHeldByCurrentThread());
        } finally {
            coord.unlock();
        }
        assertFalse(coord.isHeldByCurrentThread());
    }

    @Test
    @DisplayName("two concurrent rebuilders never enter the critical section together")
    void neverConcurrent() throws Exception {
        RebuildCoordinator coord = new RebuildCoordinator();
        AtomicInteger inside = new AtomicInteger(0);
        AtomicInteger maxInside = new AtomicInteger(0);
        AtomicInteger runs = new AtomicInteger(0);
        int threads = 8;
        int rebuildsPerThread = 50;
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    ready.await();
                    for (int n = 0; n < rebuildsPerThread; n++) {
                        coord.lock();
                        try {
                            int cur = inside.incrementAndGet();
                            // Update peak inside-count.
                            maxInside.updateAndGet(prev -> Math.max(prev, cur));
                            // Tiny delay to widen the race window.
                            Thread.sleep(0, 100_000);
                            inside.decrementAndGet();
                            runs.incrementAndGet();
                        } finally {
                            coord.unlock();
                        }
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        ready.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertEquals(threads * rebuildsPerThread, runs.get(),
                "every rebuild attempt should have executed exactly once");
        assertEquals(1, maxInside.get(),
                "the critical section must never be entered concurrently");
    }
}

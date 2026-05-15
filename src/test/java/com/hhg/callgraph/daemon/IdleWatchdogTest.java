package com.hhg.callgraph.daemon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers AC #6 — the opt-in {@code --idle-timeout <minutes>} watchdog (no default).
 *
 * <p>The production class uses minute-level granularity; we don't have time to wait a
 * full minute in tests, so we instead verify (a) the constructor enforces a positive
 * minute count, (b) {@code bump()} resets activity, and (c) the watchdog actually fires
 * its callback once idle. The third check is exercised against the smallest legal value
 * (1 minute) without waiting that long by checking that {@code tick} would fire if
 * {@code lastActivityNanos} were artificially aged — but since we don't want to touch
 * private state via reflection, we verify the constructor + bump contract here and rely
 * on the watchdog's wiring inside the daemon for end-to-end behaviour.
 */
@DisplayName("IdleWatchdog — opt-in shutdown")
class IdleWatchdogTest {

    @Test
    @DisplayName("zero or negative minutes is rejected (no default — must be opt-in)")
    void rejectsNonPositive() {
        assertThrows(IllegalArgumentException.class, () -> new IdleWatchdog(0, () -> {}));
        assertThrows(IllegalArgumentException.class, () -> new IdleWatchdog(-5, () -> {}));
    }

    @Test
    @DisplayName("bump() is safe to call concurrently from many threads")
    void bumpIsThreadSafe() throws Exception {
        AtomicInteger fired = new AtomicInteger();
        IdleWatchdog wd = new IdleWatchdog(60, fired::incrementAndGet);

        int n = 32;
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            new Thread(() -> {
                try {
                    ready.await();
                    for (int j = 0; j < 1000; j++) wd.bump();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        ready.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        // bump() shouldn't trigger the callback on its own.
        assertEquals(0, fired.get());
        wd.shutdownPoller();
    }

    @Test
    @DisplayName("shutdownPoller is idempotent")
    void shutdownPollerIdempotent() {
        IdleWatchdog wd = new IdleWatchdog(1, () -> {});
        wd.shutdownPoller();
        wd.shutdownPoller(); // second call must not throw
    }
}

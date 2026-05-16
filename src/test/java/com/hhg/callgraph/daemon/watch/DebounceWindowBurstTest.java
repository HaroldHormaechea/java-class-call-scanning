package com.hhg.callgraph.daemon.watch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UC04 AC #17(g) — a burst of N events inside one debounce window produces exactly
 * one rebuild log. The {@link DebounceWindow} is the production component
 * responsible for that coalescing; this test pokes it directly with synthetic
 * {@link WatchEventKind} events so we exercise the coalescing logic without depending
 * on platform {@link java.nio.file.WatchService} latency.
 *
 * <p>Strict precedence (UC04 §10) is also covered: when build-config events are mixed
 * in, the window dispatches {@link DebounceWindow.WindowConsumer#onBuildConfigChange}
 * and skips bytecode / source-only consumers for the same window.
 */
@DisplayName("DebounceWindow — coalescing bursts (UC04 AC #17(g))")
class DebounceWindowBurstTest {

    private ScheduledExecutorService scheduler;

    @AfterEach
    void shutdownScheduler() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private DebounceWindow newWindow(long debounceMs, DebounceWindow.WindowConsumer consumer) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "test-debounce");
            t.setDaemon(true);
            return t;
        });
        return new DebounceWindow(debounceMs, scheduler, consumer);
    }

    @Test
    @DisplayName("100 bytecode events in a tight burst trigger exactly one rebuild")
    void burstCollapsesToOneRebuild(@TempDir Path tmp) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger eventCount = new AtomicInteger();
        CountdownConsumer consumer = new CountdownConsumer() {
            @Override
            public void onBytecodeChange(List<Path> changedClassFiles,
                                         List<Path> changedArchives,
                                         List<Path> triggerPaths,
                                         int events) {
                calls.incrementAndGet();
                eventCount.set(events);
                done.countDown();
            }
        };
        consumer.armBytecode();

        DebounceWindow window = newWindow(80, consumer);

        // Push 100 distinct paths so the buffer holds 100 events.
        for (int i = 0; i < 100; i++) {
            window.offer(tmp.resolve("p" + i + ".class"), WatchEventKind.CLASSPATH_BYTECODE);
        }

        assertTrue(consumer.bytecodeLatch().await(3, TimeUnit.SECONDS),
                "consumer must fire once within the debounce window");
        // Give the scheduler an extra full window to confirm no second firing.
        Thread.sleep(240);

        assertEquals(1, calls.get(),
                "100 bytecode events in one window must produce exactly one rebuild");
        assertEquals(100, eventCount.get(),
                "the coalesced event_count must equal the burst size");
    }

    @Test
    @DisplayName("debounce timer is reset on every new event — late event extends the window")
    void timerResetsOnEachEvent(@TempDir Path tmp) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountdownConsumer consumer = new CountdownConsumer() {
            @Override
            public void onBytecodeChange(List<Path> changedClassFiles,
                                         List<Path> changedArchives,
                                         List<Path> triggerPaths,
                                         int events) {
                calls.incrementAndGet();
                done.countDown();
            }
        };
        consumer.armBytecode();

        DebounceWindow window = newWindow(150, consumer);

        long start = System.nanoTime();
        // Offer an event every 50ms for 400ms — the timer should keep resetting,
        // and the drain shouldn't fire until ~150ms after the last event.
        for (int i = 0; i < 8; i++) {
            window.offer(tmp.resolve("p" + i + ".class"), WatchEventKind.CLASSPATH_BYTECODE);
            Thread.sleep(50);
        }
        assertTrue(consumer.bytecodeLatch().await(2, TimeUnit.SECONDS));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        // Total time should be at least 8*50 + 150 = 550 ms (give some tolerance).
        assertTrue(elapsedMs >= 500,
                "elapsed time " + elapsedMs + "ms < 500ms — the debounce timer didn't reset on later events");
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("build-config events trump bytecode events in the same window (UC04 §10 precedence)")
    void buildConfigBeatsBytecode(@TempDir Path tmp) throws Exception {
        AtomicInteger bytecodeCalls = new AtomicInteger();
        AtomicInteger buildConfigCalls = new AtomicInteger();
        CountdownConsumer consumer = new CountdownConsumer() {
            @Override
            public void onBuildConfigChange(List<Path> buildConfigPaths) {
                buildConfigCalls.incrementAndGet();
                done.countDown();
            }
            @Override
            public void onBytecodeChange(List<Path> changedClassFiles,
                                         List<Path> changedArchives,
                                         List<Path> triggerPaths,
                                         int events) {
                bytecodeCalls.incrementAndGet();
            }
        };
        consumer.armBuildConfig();

        DebounceWindow window = newWindow(60, consumer);
        window.offer(tmp.resolve("Foo.class"), WatchEventKind.CLASSPATH_BYTECODE);
        window.offer(tmp.resolve("build.gradle"), WatchEventKind.BUILD_CONFIG);
        window.offer(tmp.resolve("Bar.class"), WatchEventKind.CLASSPATH_BYTECODE);

        assertTrue(consumer.buildConfigLatch().await(3, TimeUnit.SECONDS));
        Thread.sleep(180); // make sure no second firing

        assertEquals(1, buildConfigCalls.get(), "build-config consumer must be invoked exactly once");
        assertEquals(0, bytecodeCalls.get(),
                "bytecode consumer must NOT fire when a build-config event is in the same window");
    }

    @Test
    @DisplayName("source-only events emit onSourceOnlyChange when no bytecode/build-config events present")
    void sourceOnlyFires(@TempDir Path tmp) throws Exception {
        AtomicInteger sourceCalls = new AtomicInteger();
        CountdownConsumer consumer = new CountdownConsumer() {
            @Override
            public void onSourceOnlyChange(List<Path> sourcePaths, int events) {
                sourceCalls.incrementAndGet();
                done.countDown();
            }
        };
        consumer.armSource();

        DebounceWindow window = newWindow(60, consumer);
        window.offer(tmp.resolve("Foo.java"), WatchEventKind.SOURCE_JAVA);
        window.offer(tmp.resolve("Bar.java"), WatchEventKind.SOURCE_JAVA);

        assertTrue(consumer.sourceLatch().await(2, TimeUnit.SECONDS));
        assertEquals(1, sourceCalls.get());
    }

    @Test
    @DisplayName("two distinct windows produce two separate consumer calls")
    void twoWindowsTwoCalls(@TempDir Path tmp) throws Exception {
        ResetableCountdownConsumer consumer = new ResetableCountdownConsumer();
        // Latch counts down to zero after BOTH consumer fires happen.
        consumer.armBytecode(2);

        DebounceWindow window = newWindow(60, consumer);

        window.offer(tmp.resolve("Foo.class"), WatchEventKind.CLASSPATH_BYTECODE);
        // Wait for the first drain to land (calls() == 1) before sending another event.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (consumer.calls() < 1 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(1, consumer.calls(),
                "first window should have produced exactly one consumer call by now");

        // Wait one more debounce window to be sure the first drain's timer is settled,
        // then push a second event.
        Thread.sleep(120);
        window.offer(tmp.resolve("Bar.class"), WatchEventKind.CLASSPATH_BYTECODE);

        assertTrue(consumer.bytecodeLatch().await(3, TimeUnit.SECONDS),
                "second window should fire as a separate consumer call");
        assertEquals(2, consumer.calls(),
                "two non-overlapping windows should produce exactly two consumer calls");
    }

    @Test
    @DisplayName("bufferSnapshot() reflects newly offered events before drain")
    void bufferSnapshotVisible(@TempDir Path tmp) {
        DebounceWindow window = newWindow(1_000, new NoopConsumer());
        Path p = tmp.resolve("X.class").toAbsolutePath();
        window.offer(p, WatchEventKind.CLASSPATH_BYTECODE);
        assertEquals(WatchEventKind.CLASSPATH_BYTECODE, window.bufferSnapshot().get(p));
    }

    // -----------------------------------------------------------------------
    // Test consumers
    // -----------------------------------------------------------------------

    private abstract static class CountdownConsumer implements DebounceWindow.WindowConsumer {
        protected java.util.concurrent.CountDownLatch done;

        java.util.concurrent.CountDownLatch bytecodeLatch() { return done; }
        java.util.concurrent.CountDownLatch buildConfigLatch() { return done; }
        java.util.concurrent.CountDownLatch sourceLatch() { return done; }

        void armBytecode()   { done = new java.util.concurrent.CountDownLatch(1); }
        void armBuildConfig(){ done = new java.util.concurrent.CountDownLatch(1); }
        void armSource()     { done = new java.util.concurrent.CountDownLatch(1); }

        @Override public void onBuildConfigChange(List<Path> p) {}
        @Override public void onBytecodeChange(List<Path> a, List<Path> b, List<Path> c, int n) {}
        @Override public void onSourceOnlyChange(List<Path> p, int n) {}
        @Override public void onConsumerError(Throwable t) {
            throw new RuntimeException("unexpected onConsumerError", t);
        }
    }

    private static final class ResetableCountdownConsumer implements DebounceWindow.WindowConsumer {
        private final List<Integer> events = new ArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();
        private java.util.concurrent.CountDownLatch done;

        void armBytecode(int hits) {
            done = new java.util.concurrent.CountDownLatch(hits);
        }
        java.util.concurrent.CountDownLatch bytecodeLatch() { return done; }
        int calls() { return calls.get(); }

        @Override public void onBuildConfigChange(List<Path> p) {}
        @Override public void onBytecodeChange(List<Path> a, List<Path> b, List<Path> c, int n) {
            events.add(n);
            calls.incrementAndGet();
            done.countDown();
        }
        @Override public void onSourceOnlyChange(List<Path> p, int n) {}
        @Override public void onConsumerError(Throwable t) {
            throw new RuntimeException("unexpected onConsumerError", t);
        }
    }

    private static final class NoopConsumer implements DebounceWindow.WindowConsumer {
        @Override public void onBuildConfigChange(List<Path> p) {}
        @Override public void onBytecodeChange(List<Path> a, List<Path> b, List<Path> c, int n) {}
        @Override public void onSourceOnlyChange(List<Path> p, int n) {}
        @Override public void onConsumerError(Throwable t) {}
    }
}

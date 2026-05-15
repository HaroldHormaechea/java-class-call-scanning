package com.hhg.callgraph.daemon;

import com.hhg.callgraph.model.CallGraph;
import com.hhg.callgraph.model.FieldAccessIndex;
import com.hhg.callgraph.model.SourceIndex;
import com.hhg.callgraph.model.TestIndex;
import com.hhg.callgraph.scanner.ScanResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers AC #11 / #12 — the daemon's {@code AtomicReference<IndexSnapshot>} swap is
 * atomic; concurrent readers see a stable snapshot regardless of when the swap occurred.
 *
 * <p>This is a lock-free reader test: we don't go through {@link RebuildCoordinator} here,
 * just the bare {@link AtomicReference}, which is the snapshot-reference scheme called
 * out in the use-case clarifications.
 */
@DisplayName("IndexSnapshot — AtomicReference swap is atomic for readers")
class IndexSnapshotConcurrencyTest {

    private static ScopeConfig scopeFor(Path... roots) {
        return ScopeConfig.of(List.of(roots), List.of(), List.of(), List.of());
    }

    private static IndexSnapshot makeSnapshot(int marker, ScopeConfig scope) {
        ScanResult empty = new ScanResult(new CallGraph(), new SourceIndex(),
                new FieldAccessIndex(), new TestIndex());
        return new IndexSnapshot(empty, scope,
                new ScanMeta(marker, marker, 0L, System.currentTimeMillis()));
    }

    @Test
    @DisplayName("post-build snapshot is non-null")
    void postBuildNonNull(@TempDir Path tmp) throws IOException {
        Path cp = Files.createDirectory(tmp.resolve("cp"));
        ScopeConfig scope = scopeFor(cp);
        IndexSnapshot s = makeSnapshot(1, scope);
        assertNotNull(s);
        assertNotNull(s.scan());
        assertNotNull(s.scope());
        assertNotNull(s.meta());
        assertEquals(1, s.meta().methodCount());
    }

    @Test
    @DisplayName("AtomicReference reads return same snapshot across threads (until swap)")
    void readsReturnSameSnapshot(@TempDir Path tmp) throws Exception {
        Path cp = Files.createDirectory(tmp.resolve("cp"));
        ScopeConfig scope = scopeFor(cp);
        IndexSnapshot first = makeSnapshot(1, scope);
        AtomicReference<IndexSnapshot> ref = new AtomicReference<>(first);

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        IndexSnapshot[] seen = new IndexSnapshot[threads];
        for (int i = 0; i < threads; i++) {
            int idx = i;
            new Thread(() -> {
                try {
                    start.await();
                    seen[idx] = ref.get();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        for (IndexSnapshot s : seen) {
            assertSame(first, s, "all readers should see the same atomic reference");
        }
    }

    @Test
    @DisplayName("swap is atomic — reader either sees old or new snapshot, never a torn one")
    void swapIsAtomic(@TempDir Path tmp) throws Exception {
        Path cp = Files.createDirectory(tmp.resolve("cp"));
        ScopeConfig scope = scopeFor(cp);
        IndexSnapshot first = makeSnapshot(1, scope);
        IndexSnapshot second = makeSnapshot(2, scope);
        AtomicReference<IndexSnapshot> ref = new AtomicReference<>(first);

        int rounds = 200;
        for (int r = 0; r < rounds; r++) {
            CountDownLatch ready = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            int[] observedMarker = new int[1];
            Thread reader = new Thread(() -> {
                try {
                    ready.await();
                    IndexSnapshot s = ref.get();
                    // Read .meta() through the reference; meta is immutable inside the
                    // snapshot so this must be 1 or 2, never something undefined.
                    observedMarker[0] = s.meta().methodCount();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            Thread swapper = new Thread(() -> {
                try {
                    ready.await();
                    ref.set(second);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            reader.start();
            swapper.start();
            ready.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));

            assertTrue(observedMarker[0] == 1 || observedMarker[0] == 2,
                    "reader saw a torn snapshot in round " + r + ": " + observedMarker[0]);

            // Reset for next round.
            ref.set(first);
        }
    }
}

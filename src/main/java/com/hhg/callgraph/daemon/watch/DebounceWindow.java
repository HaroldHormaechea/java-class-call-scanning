package com.hhg.callgraph.daemon.watch;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Coalesces inbound FS events into a single rebuild pass.
 *
 * <p>Single-thread {@link ScheduledExecutorService}; on every event arrival, the
 * pending {@link ScheduledFuture} is cancelled and replaced with one
 * {@code debounceMs} ahead. When the future fires, the buffer is atomically swapped
 * out and evaluated by the strict precedence in UC04 §10:
 * <ol>
 *   <li>build-config events present → {@link SelfRestart#restart};</li>
 *   <li>bytecode events present → {@link IncrementalRebuilder#rebuild};</li>
 *   <li>source-only events → emit a {@code skipped} rebuild log;</li>
 *   <li>empty / unknown only → no-op.</li>
 * </ol>
 */
public final class DebounceWindow {

    private final long debounceMs;
    private final ScheduledExecutorService scheduler;
    private final WindowConsumer consumer;
    private final Object lock = new Object();
    private final Map<Path, WatchEventKind> buffer = new LinkedHashMap<>();
    private ScheduledFuture<?> pending;

    public DebounceWindow(long debounceMs, ScheduledExecutorService scheduler, WindowConsumer consumer) {
        this.debounceMs = debounceMs;
        this.scheduler = scheduler;
        this.consumer = consumer;
    }

    /** Records one FS event into the current window. */
    public void offer(Path absolutePath, WatchEventKind kind) {
        if (absolutePath == null || kind == null) return;
        synchronized (lock) {
            // Last write wins for kind classification, but build-config beats everything.
            WatchEventKind existing = buffer.get(absolutePath);
            if (existing == WatchEventKind.BUILD_CONFIG) {
                // already most-severe
            } else {
                buffer.put(absolutePath, kind);
            }
            if (pending != null) {
                pending.cancel(false);
            }
            pending = scheduler.schedule(this::drain, debounceMs, TimeUnit.MILLISECONDS);
        }
    }

    private void drain() {
        Map<Path, WatchEventKind> snapshot;
        synchronized (lock) {
            if (buffer.isEmpty()) {
                pending = null;
                return;
            }
            snapshot = new LinkedHashMap<>(buffer);
            buffer.clear();
            pending = null;
        }

        // Classify the window.
        List<Path> classFiles = new ArrayList<>();
        List<Path> archives = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        List<Path> buildConfigs = new ArrayList<>();
        int eventCount = snapshot.size();

        for (Map.Entry<Path, WatchEventKind> e : snapshot.entrySet()) {
            switch (e.getValue()) {
                case CLASSPATH_BYTECODE -> classFiles.add(e.getKey());
                case CLASSPATH_ARCHIVE  -> archives.add(e.getKey());
                case SOURCE_JAVA        -> sources.add(e.getKey());
                case BUILD_CONFIG       -> buildConfigs.add(e.getKey());
                case UNKNOWN            -> { /* discard */ }
            }
        }

        try {
            if (!buildConfigs.isEmpty()) {
                consumer.onBuildConfigChange(buildConfigs);
                return;
            }
            if (!classFiles.isEmpty() || !archives.isEmpty()) {
                List<Path> triggers = new ArrayList<>();
                triggers.addAll(classFiles);
                triggers.addAll(archives);
                consumer.onBytecodeChange(classFiles, archives, triggers, eventCount);
                return;
            }
            if (!sources.isEmpty()) {
                consumer.onSourceOnlyChange(sources, eventCount);
                return;
            }
            // empty / UNKNOWN only — no-op
        } catch (Throwable t) {
            consumer.onConsumerError(t);
        }
    }

    /** Cancel any pending future without consuming the buffer. */
    public void stop() {
        synchronized (lock) {
            if (pending != null) {
                pending.cancel(false);
                pending = null;
            }
            buffer.clear();
        }
    }

    /** Test hook — returns an unmodifiable view of the current buffer. */
    public Map<Path, WatchEventKind> bufferSnapshot() {
        synchronized (lock) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(buffer));
        }
    }

    /**
     * Receiver of the four classified outcomes. {@link FileWatcher} implements this
     * to delegate into the {@link IncrementalRebuilder}, {@link SelfRestart}, and the
     * source-only logging path.
     */
    public interface WindowConsumer {
        void onBuildConfigChange(List<Path> buildConfigPaths);
        void onBytecodeChange(List<Path> changedClassFiles,
                              List<Path> changedArchives,
                              List<Path> triggerPaths,
                              int eventCount);
        void onSourceOnlyChange(List<Path> sourcePaths, int eventCount);
        void onConsumerError(Throwable t);
    }

    /** Defensive equality on the two paths (used by tests). */
    public static boolean samePath(Path a, Path b) {
        return Objects.equals(a == null ? null : a.toAbsolutePath(),
                b == null ? null : b.toAbsolutePath());
    }
}

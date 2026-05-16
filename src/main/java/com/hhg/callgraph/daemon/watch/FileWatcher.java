package com.hhg.callgraph.daemon.watch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hhg.callgraph.daemon.IndexSnapshot;
import com.hhg.callgraph.daemon.ScopeConfig;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Orchestrates the auto-watch + incremental-rebuild subsystem.
 *
 * <p>Two daemon threads:
 * <ul>
 *   <li>{@code watch-loop} — blocks on {@link WatchService#take()}, classifies events,
 *       and pushes them into the {@link DebounceWindow}.</li>
 *   <li>{@code watch-debounce} (a single-thread {@link ScheduledExecutorService}) —
 *       drains the window per UC04 §10.</li>
 * </ul>
 *
 * <p>Public API: {@link #start()} (idempotent), {@link #stop()} (cleans up).
 */
public final class FileWatcher implements DebounceWindow.WindowConsumer {

    private final ScopeConfig scope;
    private final WatcherConfig config;
    private final RebuildLogger logger;
    private final WatcherStatusHolder statusHolder;
    private final IncrementalRebuilder rebuilder;
    private final SelfRestart selfRestart;
    private final Supplier<IndexSnapshot> snapshotSupplier;

    private final List<WatchedRoot> classpathDirRoots = new ArrayList<>();
    private final List<WatchedRoot> sourceDirRoots = new ArrayList<>();
    private final List<WatchedRoot> archiveRoots = new ArrayList<>();
    private final List<WatchedRoot> buildConfigRoots = new ArrayList<>();
    private final Set<Path> archiveFiles = new HashSet<>();
    private final Set<Path> buildConfigFiles = new HashSet<>();
    private final Map<WatchKey, Path> keyToDir = new HashMap<>();

    private WatchService watchService;
    private Thread watchLoopThread;
    private ScheduledExecutorService debounceScheduler;
    private DebounceWindow debounceWindow;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> pendingRetry = new AtomicReference<>();

    public FileWatcher(ScopeConfig scope,
                       WatcherConfig config,
                       RebuildLogger logger,
                       WatcherStatusHolder statusHolder,
                       IncrementalRebuilder rebuilder,
                       SelfRestart selfRestart,
                       Supplier<IndexSnapshot> snapshotSupplier) {
        this.scope = scope;
        this.config = config;
        this.logger = logger;
        this.statusHolder = statusHolder;
        this.rebuilder = rebuilder;
        this.selfRestart = selfRestart;
        this.snapshotSupplier = snapshotSupplier;
    }

    /** Registers watches and stages the threads, but does not start the loops yet. */
    public void register() throws IOException {
        if (!config.enabled()) return;
        this.watchService = FileSystems.getDefault().newWatchService();

        // Classpath: directories are watched recursively; archives single-file (via parent).
        for (Path cp : scope.classpath()) {
            if (Files.isDirectory(cp)) {
                WatchedRoot wr = new WatchedRoot(cp.toAbsolutePath(),
                        WatchedRoot.WatchedRootKind.CLASSPATH_DIR, new LinkedHashSet<>());
                classpathDirRoots.add(wr);
                registerTree(cp, wr.registeredDirs());
            } else if (isArchive(cp)) {
                Path abs = cp.toAbsolutePath();
                WatchedRoot wr = new WatchedRoot(abs,
                        WatchedRoot.WatchedRootKind.CLASSPATH_ARCHIVE, new LinkedHashSet<>());
                archiveRoots.add(wr);
                archiveFiles.add(abs);
                Path parent = abs.getParent();
                if (parent != null && Files.isDirectory(parent)) {
                    registerSingleDir(parent);
                    wr.registeredDirs().add(parent);
                }
            }
        }

        for (Path src : scope.sourceRoots()) {
            if (Files.isDirectory(src)) {
                WatchedRoot wr = new WatchedRoot(src.toAbsolutePath(),
                        WatchedRoot.WatchedRootKind.SOURCE_DIR, new LinkedHashSet<>());
                sourceDirRoots.add(wr);
                registerTree(src, wr.registeredDirs());
            }
        }

        List<Path> configs = BuildConfigDiscovery.discover(scope.sourceRoots());
        for (Path cfg : configs) {
            Path abs = cfg.toAbsolutePath();
            buildConfigFiles.add(abs);
            Path parent = abs.getParent();
            if (parent != null && Files.isDirectory(parent)) {
                registerSingleDir(parent);
                WatchedRoot wr = new WatchedRoot(abs,
                        WatchedRoot.WatchedRootKind.BUILD_CONFIG, new LinkedHashSet<>());
                wr.registeredDirs().add(parent);
                buildConfigRoots.add(wr);
            }
        }

        // Inform the status holder of what we registered.
        List<Path> cp = new ArrayList<>();
        for (WatchedRoot r : classpathDirRoots) cp.add(r.root());
        for (WatchedRoot r : archiveRoots) cp.add(r.root());
        List<Path> sr = new ArrayList<>();
        for (WatchedRoot r : sourceDirRoots) sr.add(r.root());
        statusHolder.setWatchedRoots(cp, sr, new ArrayList<>(buildConfigFiles));
    }

    /** Starts the watch and debounce loops. Idempotent. */
    public void start() {
        if (!config.enabled()) return;
        if (!started.compareAndSet(false, true)) return;
        if (watchService == null) {
            try { register(); } catch (IOException e) {
                System.err.println("file-watcher: registration failed: " + e);
                return;
            }
        }

        this.debounceScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "watch-debounce");
            t.setDaemon(true);
            return t;
        });
        this.debounceWindow = new DebounceWindow(config.debounceMs(), debounceScheduler, this);

        this.watchLoopThread = new Thread(this::watchLoop, "watch-loop");
        watchLoopThread.setDaemon(true);
        watchLoopThread.start();
    }

    /** Stops both threads and closes the watch service. */
    public void stop() {
        if (!stopping.compareAndSet(false, true)) return;
        if (debounceWindow != null) debounceWindow.stop();
        if (watchLoopThread != null) watchLoopThread.interrupt();
        if (watchService != null) {
            try { watchService.close(); } catch (IOException ignored) {}
        }
        if (debounceScheduler != null) {
            debounceScheduler.shutdownNow();
        }
        ScheduledFuture<?> p = pendingRetry.getAndSet(null);
        if (p != null) p.cancel(false);
    }

    private void watchLoop() {
        while (!stopping.get() && !Thread.currentThread().isInterrupted()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (ClosedWatchServiceException | InterruptedException e) {
                return;
            }
            Path dir = keyToDir.get(key);
            if (dir == null) {
                key.reset();
                continue;
            }
            for (WatchEvent<?> ev : key.pollEvents()) {
                if (ev.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                @SuppressWarnings("unchecked")
                Path rel = ((WatchEvent<Path>) ev).context();
                if (rel == null) continue;
                Path abs = dir.resolve(rel).toAbsolutePath();

                // If a new directory was created under a watched root, register it recursively.
                if (ev.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(abs)) {
                    WatchedRoot r = enclosingRecursiveRoot(abs);
                    if (r != null) {
                        try {
                            registerTree(abs, r.registeredDirs());
                        } catch (IOException io) {
                            System.err.println("file-watcher: re-register failed for " + abs + ": " + io);
                        }
                    }
                }

                WatchEventKind kind = classify(abs);
                if (kind != WatchEventKind.UNKNOWN) {
                    debounceWindow.offer(abs, kind);
                }
            }
            boolean valid = key.reset();
            if (!valid) {
                keyToDir.remove(key);
            }
        }
    }

    /** Classifies a path into a {@link WatchEventKind}. */
    WatchEventKind classify(Path abs) {
        String name = abs.getFileName() == null ? "" : abs.getFileName().toString();
        if (buildConfigFiles.contains(abs)) return WatchEventKind.BUILD_CONFIG;
        if (archiveFiles.contains(abs)) return WatchEventKind.CLASSPATH_ARCHIVE;
        if (name.endsWith(".class") && underAny(abs, classpathDirRoots)) {
            return WatchEventKind.CLASSPATH_BYTECODE;
        }
        if (name.endsWith(".java") && underAny(abs, sourceDirRoots)) {
            return WatchEventKind.SOURCE_JAVA;
        }
        return WatchEventKind.UNKNOWN;
    }

    private static boolean underAny(Path p, List<WatchedRoot> roots) {
        for (WatchedRoot r : roots) {
            if (p.startsWith(r.root())) return true;
        }
        return false;
    }

    private WatchedRoot enclosingRecursiveRoot(Path abs) {
        for (WatchedRoot r : classpathDirRoots) {
            if (abs.startsWith(r.root())) return r;
        }
        for (WatchedRoot r : sourceDirRoots) {
            if (abs.startsWith(r.root())) return r;
        }
        return null;
    }

    private void registerTree(Path root, Set<Path> registered) throws IOException {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path d : (Iterable<Path>) walk::iterator) {
                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(d, BasicFileAttributes.class);
                } catch (IOException ignored) {
                    continue;
                }
                if (!attrs.isDirectory()) continue;
                registerSingleDir(d);
                registered.add(d.toAbsolutePath());
            }
        }
    }

    private void registerSingleDir(Path dir) throws IOException {
        WatchKey key = dir.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        keyToDir.put(key, dir);
    }

    private static boolean isArchive(Path p) {
        String n = p.getFileName() == null ? "" : p.getFileName().toString().toLowerCase();
        return n.endsWith(".jar") || n.endsWith(".war") || n.endsWith(".ear");
    }

    // -----------------------------------------------------------------------
    // DebounceWindow.WindowConsumer
    // -----------------------------------------------------------------------

    @Override
    public void onBuildConfigChange(List<Path> buildConfigPaths) {
        // Cancel any pending retry — restart trumps it.
        ScheduledFuture<?> r = pendingRetry.getAndSet(null);
        if (r != null) r.cancel(false);
        selfRestart.restart(buildConfigPaths);
    }

    @Override
    public void onBytecodeChange(List<Path> changedClassFiles,
                                 List<Path> changedArchives,
                                 List<Path> triggerPaths,
                                 int eventCount) {
        // Cancel any pending retry — a fresh successful pass supersedes it once it
        // completes successfully; if it fails, the retry path takes over.
        ScheduledFuture<?> r = pendingRetry.getAndSet(null);
        if (r != null) r.cancel(false);

        attemptRebuild(changedClassFiles, changedArchives, triggerPaths, eventCount,
                /*isRetry=*/ false);
    }

    private void attemptRebuild(List<Path> changedClassFiles,
                                List<Path> changedArchives,
                                List<Path> triggerPaths,
                                int eventCount,
                                boolean isRetry) {
        try {
            IndexSnapshot prior = snapshotSupplier.get();

            // Reconcile to detect deletions of class files and archives.
            Set<Path> touchedRoots = new LinkedHashSet<>();
            for (Path p : changedClassFiles) {
                WatchedRoot r2 = enclosingRecursiveRoot(p);
                if (r2 != null) touchedRoots.add(r2.root());
            }
            // For archives there's no "root subtree" — we still want their deletion
            // status, which we infer directly from existence.
            DirectoryReconciler.Result rec = DirectoryReconciler.reconcile(
                    touchedRoots,
                    prior == null ? Map.of() : prior.scan().classOrigins());

            // Filter: a path appearing as "changed" + "missing-on-disk" is actually a
            // deletion. Reconciler-deleted ∪ changed-but-missing = the deletion set.
            List<Path> deletedClassFiles = new ArrayList<>(rec.deleted());
            List<Path> deletedArchives = new ArrayList<>();
            for (Path archive : changedArchives) {
                if (!Files.exists(archive)) deletedArchives.add(archive);
            }
            // If a "changed" class file doesn't exist on disk anymore, move it.
            List<Path> finalChangedClass = new ArrayList<>();
            for (Path p : changedClassFiles) {
                if (Files.exists(p)) finalChangedClass.add(p);
                else if (!deletedClassFiles.contains(p)) deletedClassFiles.add(p);
            }
            List<Path> finalChangedArchives = new ArrayList<>();
            for (Path p : changedArchives) {
                if (Files.exists(p)) finalChangedArchives.add(p);
            }

            RebuildOutcome out = rebuilder.rebuild(finalChangedClass, finalChangedArchives,
                    deletedClassFiles, deletedArchives, prior,
                    triggerPaths, eventCount);

            emitRebuildLog(out, /*outcome=*/ "success", null);
            statusHolder.recordRebuild("classpath", "success", out.elapsedMs(),
                    out.scopeClasses().size(), out.scopeArchives().size());
            statusHolder.clearError();
        } catch (Throwable t) {
            if (!isRetry) {
                // Schedule one retry.
                ScheduledFuture<?> rf = debounceScheduler.schedule(
                        () -> attemptRebuild(changedClassFiles, changedArchives, triggerPaths,
                                eventCount, true),
                        config.debounceMs(), TimeUnit.MILLISECONDS);
                pendingRetry.set(rf);
            } else {
                emitFailureLog(triggerPaths, eventCount, t);
                statusHolder.recordError(t);
            }
        }
    }

    @Override
    public void onSourceOnlyChange(List<Path> sourcePaths, int eventCount) {
        long start = System.nanoTime();
        long elapsed = (System.nanoTime() - start) / 1_000_000L;
        // Emit one skipped log line.
        JsonObject obj = new JsonObject();
        obj.addProperty("event", "watch.rebuild");
        obj.addProperty("schema_version", 1);
        obj.addProperty("trigger.watcher", "source");
        obj.add("trigger.paths", pathsToJsonArray(sourcePaths));
        obj.addProperty("trigger.event_count", eventCount);
        obj.add("scope.classes", new JsonArray());
        obj.add("scope.archives", new JsonArray());
        obj.addProperty("outcome", "skipped");
        obj.addProperty("outcome.reason", "source-only-no-bytecode-change");
        obj.addProperty("elapsed_ms", elapsed);
        IndexSnapshot live = snapshotSupplier.get();
        int methods = live == null ? 0 : live.meta().methodCount();
        int edges   = live == null ? 0 : live.meta().edgeCount();
        obj.addProperty("index.methods_before", methods);
        obj.addProperty("index.methods_after", methods);
        obj.addProperty("index.edges_before", edges);
        obj.addProperty("index.edges_after", edges);
        obj.addProperty("timestamp", java.time.Instant.now().toString());
        logger.emit(obj);
        statusHolder.recordSkipped("source", elapsed);
    }

    @Override
    public void onConsumerError(Throwable t) {
        System.err.println("file-watcher: consumer error: " + t);
    }

    private void emitRebuildLog(RebuildOutcome out, String outcome, String reason) {
        JsonObject obj = new JsonObject();
        obj.addProperty("event", "watch.rebuild");
        obj.addProperty("schema_version", 1);
        obj.addProperty("trigger.watcher", "classpath");
        obj.add("trigger.paths", pathsToJsonArray(out.triggerPaths()));
        obj.addProperty("trigger.event_count", out.eventCount());
        JsonArray cls = new JsonArray();
        for (String c : out.scopeClasses()) cls.add(c);
        obj.add("scope.classes", cls);
        JsonArray arc = new JsonArray();
        for (Path p : out.scopeArchives()) arc.add(p.toAbsolutePath().toString());
        obj.add("scope.archives", arc);
        obj.addProperty("outcome", outcome);
        if (reason != null) obj.addProperty("outcome.reason", reason);
        obj.addProperty("elapsed_ms", out.elapsedMs());
        obj.addProperty("index.methods_before", out.methodsBefore());
        obj.addProperty("index.methods_after", out.methodsAfter());
        obj.addProperty("index.edges_before", out.edgesBefore());
        obj.addProperty("index.edges_after", out.edgesAfter());
        obj.addProperty("timestamp", java.time.Instant.now().toString());
        logger.emit(obj);
    }

    private void emitFailureLog(List<Path> triggerPaths, int eventCount, Throwable t) {
        JsonObject obj = new JsonObject();
        obj.addProperty("event", "watch.rebuild");
        obj.addProperty("schema_version", 1);
        obj.addProperty("trigger.watcher", "classpath");
        obj.add("trigger.paths", pathsToJsonArray(triggerPaths));
        obj.addProperty("trigger.event_count", eventCount);
        obj.add("scope.classes", new JsonArray());
        obj.add("scope.archives", new JsonArray());
        obj.addProperty("outcome", "failure");
        obj.addProperty("outcome.reason",
                t.getClass().getName() + ": " + (t.getMessage() == null ? "" : t.getMessage()));
        IndexSnapshot live = snapshotSupplier.get();
        int methods = live == null ? 0 : live.meta().methodCount();
        int edges   = live == null ? 0 : live.meta().edgeCount();
        obj.addProperty("elapsed_ms", 0);
        obj.addProperty("index.methods_before", methods);
        obj.addProperty("index.methods_after", methods);
        obj.addProperty("index.edges_before", edges);
        obj.addProperty("index.edges_after", edges);
        obj.addProperty("timestamp", java.time.Instant.now().toString());
        logger.emit(obj);
    }

    private static JsonArray pathsToJsonArray(List<Path> paths) {
        JsonArray arr = new JsonArray();
        if (paths != null) {
            for (Path p : paths) {
                arr.add(p.toAbsolutePath().toString());
            }
        }
        return arr;
    }
}

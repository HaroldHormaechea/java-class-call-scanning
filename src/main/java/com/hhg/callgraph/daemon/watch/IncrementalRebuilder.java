package com.hhg.callgraph.daemon.watch;

import com.hhg.callgraph.daemon.IndexSnapshot;
import com.hhg.callgraph.daemon.RebuildCoordinator;
import com.hhg.callgraph.daemon.ScanMeta;
import com.hhg.callgraph.daemon.ScopeConfig;
import com.hhg.callgraph.model.CallGraph;
import com.hhg.callgraph.model.FieldAccessIndex;
import com.hhg.callgraph.model.SourceIndex;
import com.hhg.callgraph.model.TestIndex;
import com.hhg.callgraph.scanner.ClassFileScanner;
import com.hhg.callgraph.scanner.ScanResult;
import com.hhg.callgraph.scanner.test.JUnit5TestDetector;
import com.hhg.callgraph.scanner.test.SpockTestDetector;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Performs an incremental rebuild for a coalesced FS-event batch.
 *
 * <p>Inputs come from {@link DebounceWindow}: changed events are authoritative for
 * additions and modifications; deletions are sourced from
 * {@link DirectoryReconciler}. The rebuild is serialised via the daemon's
 * {@link RebuildCoordinator}; readers continue to see the prior snapshot until the
 * atomic swap completes.
 */
public final class IncrementalRebuilder {

    private final AtomicReference<IndexSnapshot> indexRef;
    private final RebuildCoordinator coordinator;
    private final ScopeConfig scope;

    public IncrementalRebuilder(AtomicReference<IndexSnapshot> indexRef,
                                RebuildCoordinator coordinator,
                                ScopeConfig scope) {
        this.indexRef = indexRef;
        this.coordinator = coordinator;
        this.scope = scope;
    }

    /**
     * Rebuilds the snapshot for the given event batch.
     *
     * @param changedClassFiles  {@code ENTRY_CREATE} + {@code ENTRY_MODIFY} events for {@code .class} files
     * @param changedArchives    archive ({@code .jar}/{@code .war}) modifications
     * @param deletedClassFiles  {@code .class} files identified as deleted by the reconciler
     * @param deletedArchives    archive files identified as deleted by the reconciler
     * @param prior              the prior live snapshot (read once)
     * @param triggerPaths       canonical paths of the FS events (for the log)
     * @param eventCount         coalesced FS-event count
     */
    public RebuildOutcome rebuild(List<Path> changedClassFiles,
                                  List<Path> changedArchives,
                                  List<Path> deletedClassFiles,
                                  List<Path> deletedArchives,
                                  IndexSnapshot prior,
                                  List<Path> triggerPaths,
                                  int eventCount) throws IOException {
        long start = System.nanoTime();
        coordinator.lock();
        try {
            // The prior may have advanced between the watcher reading it and the lock
            // being acquired (manual refresh-index racing the watcher). Re-read.
            IndexSnapshot live = indexRef.get();
            if (live != null) prior = live;

            ScanResult priorScan = prior.scan();
            Map<String, Path> priorOrigins = priorScan.classOrigins();

            // Reverse map origin -> classes (computed lazily).
            Map<Path, Set<String>> originToClasses = new HashMap<>();
            for (Map.Entry<String, Path> e : priorOrigins.entrySet()) {
                Path abs = e.getValue() == null ? null : e.getValue().toAbsolutePath();
                if (abs == null) continue;
                originToClasses.computeIfAbsent(abs, k -> new LinkedHashSet<>()).add(e.getKey());
            }

            // Affected class names = union of priorOrigins[path] for every path in
            // the four input sets.
            Set<String> affectedClasses = new LinkedHashSet<>();
            collectAffected(changedClassFiles, originToClasses, affectedClasses);
            collectAffected(changedArchives, originToClasses, affectedClasses);
            collectAffected(deletedClassFiles, originToClasses, affectedClasses);
            collectAffected(deletedArchives, originToClasses, affectedClasses);

            // Deep-copy the indexes so we mutate a draft.
            CallGraph draftGraph = priorScan.callGraph().copy();
            SourceIndex draftSource = priorScan.sourceIndex().copy();
            FieldAccessIndex draftFields = priorScan.fieldAccessIndex().copy();
            TestIndex draftTests = priorScan.testIndex().copy();

            for (String cls : affectedClasses) {
                draftGraph.removeClass(cls);
                draftSource.removeClass(cls);
                draftFields.removeClass(cls);
                draftTests.removeClass(cls);
            }

            // Scan changed (non-deleted) inputs.
            ClassFileScanner scanner = new ClassFileScanner(List.of(
                    new JUnit5TestDetector(),
                    new SpockTestDetector()));
            ScanResult partial = null;
            if (!changedClassFiles.isEmpty() || !changedArchives.isEmpty()) {
                CallGraph pg = new CallGraph();
                SourceIndex ps = new SourceIndex();
                FieldAccessIndex pf = new FieldAccessIndex();
                TestIndex pt = new TestIndex();
                Map<String, Path> po = new HashMap<>();
                if (!changedClassFiles.isEmpty()) {
                    ScanResult cf = scanner.scanClassFiles(changedClassFiles);
                    pg.mergeFrom(cf.callGraph());
                    ps.mergeFrom(cf.sourceIndex());
                    pf.mergeFrom(cf.fieldAccessIndex());
                    pt.mergeFrom(cf.testIndex());
                    po.putAll(cf.classOrigins());
                }
                for (Path arc : changedArchives) {
                    ScanResult ar = scanner.scanArchive(arc);
                    pg.mergeFrom(ar.callGraph());
                    ps.mergeFrom(ar.sourceIndex());
                    pf.mergeFrom(ar.fieldAccessIndex());
                    pt.mergeFrom(ar.testIndex());
                    po.putAll(ar.classOrigins());
                }
                partial = new ScanResult(pg, ps, pf, pt, po);

                // Merge partial into the drafts.
                draftGraph.mergeFrom(partial.callGraph());
                draftSource.mergeFrom(partial.sourceIndex());
                draftFields.mergeFrom(partial.fieldAccessIndex());
                draftTests.mergeFrom(partial.testIndex());
            }

            // Build the new classOrigins explicitly: prior - affected + partial.
            Map<String, Path> newOrigins = new HashMap<>(priorOrigins);
            for (String cls : affectedClasses) {
                newOrigins.remove(cls);
            }
            if (partial != null) {
                newOrigins.putAll(partial.classOrigins());
            }

            ScanResult newScan = new ScanResult(draftGraph, draftSource, draftFields, draftTests, newOrigins);

            int methodsAfter = draftGraph.methodCount();
            int edgesAfter   = draftGraph.edgeCount();
            int methodsBefore = priorScan.callGraph().methodCount();
            int edgesBefore   = priorScan.callGraph().edgeCount();

            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            ScanMeta meta = new ScanMeta(methodsAfter, edgesAfter, elapsedMs, System.currentTimeMillis());
            IndexSnapshot newSnap = new IndexSnapshot(newScan, scope, meta);
            indexRef.set(newSnap);

            // Dotted FQNs for the rebuild log scope.classes field.
            List<String> dottedScope = new ArrayList<>(affectedClasses.size());
            for (String cls : affectedClasses) dottedScope.add(cls.replace('/', '.'));

            List<Path> archiveScope = new ArrayList<>();
            for (Path p : changedArchives) archiveScope.add(p.toAbsolutePath());
            for (Path p : deletedArchives) archiveScope.add(p.toAbsolutePath());

            return new RebuildOutcome(newSnap, elapsedMs, dottedScope, archiveScope,
                    triggerPaths == null ? List.of() : triggerPaths,
                    eventCount, methodsBefore, methodsAfter, edgesBefore, edgesAfter);
        } finally {
            coordinator.unlock();
        }
    }

    private static void collectAffected(List<Path> paths,
                                        Map<Path, Set<String>> originToClasses,
                                        Set<String> out) {
        if (paths == null) return;
        for (Path p : paths) {
            if (p == null) continue;
            Path abs = p.toAbsolutePath();
            Set<String> cls = originToClasses.get(abs);
            if (cls != null) out.addAll(cls);
        }
    }
}

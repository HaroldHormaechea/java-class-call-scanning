package com.hhg.callgraph.daemon.watch;

import com.hhg.callgraph.daemon.IndexSnapshot;

import java.nio.file.Path;
import java.util.List;

/**
 * Return value from {@link IncrementalRebuilder#rebuild(java.util.List, java.util.List,
 * java.util.List, java.util.List, com.hhg.callgraph.daemon.IndexSnapshot)}. Carries
 * everything the logger and the {@link WatcherStatusHolder} need.
 *
 * @param snapshot       the newly-built snapshot, already published via {@code AtomicReference.set(...)}
 * @param elapsedMs      wall-clock duration of the rebuild
 * @param scopeClasses   dotted FQNs of every affected class
 * @param scopeArchives  absolute archive paths that contributed to this rebuild
 * @param triggerPaths   absolute paths of the FS events that drove the rebuild
 * @param eventCount     coalesced FS-event count for this debounce window
 * @param methodsBefore  method count in the prior snapshot
 * @param methodsAfter   method count in the new snapshot
 * @param edgesBefore    edge count in the prior snapshot
 * @param edgesAfter     edge count in the new snapshot
 */
public record RebuildOutcome(IndexSnapshot snapshot,
                             long elapsedMs,
                             List<String> scopeClasses,
                             List<Path> scopeArchives,
                             List<Path> triggerPaths,
                             int eventCount,
                             int methodsBefore,
                             int methodsAfter,
                             int edgesBefore,
                             int edgesAfter) {
}

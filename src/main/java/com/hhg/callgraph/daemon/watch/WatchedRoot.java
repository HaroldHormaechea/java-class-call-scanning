package com.hhg.callgraph.daemon.watch;

import java.nio.file.Path;
import java.util.Set;

/**
 * Records a registered root and the directories actually mounted on the
 * {@link java.nio.file.WatchService}.
 *
 * <p>{@code root} is the user-supplied directory or the parent directory that hosts the
 * single watched file (for archives and build-config files). {@code registeredDirs}
 * accumulates every subdirectory registered (recursive subtree for directory roots,
 * single-entry parent for file watches) — the recursive-add path on {@code ENTRY_CREATE}
 * updates this set as new subtrees appear.
 */
public record WatchedRoot(Path root, WatchedRootKind kind, Set<Path> registeredDirs) {

    public enum WatchedRootKind {
        CLASSPATH_DIR,
        CLASSPATH_ARCHIVE,
        SOURCE_DIR,
        BUILD_CONFIG
    }
}

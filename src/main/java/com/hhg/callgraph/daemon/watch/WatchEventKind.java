package com.hhg.callgraph.daemon.watch;

/**
 * Classification of a single FS event observed by the watcher. The kind is derived
 * from where the path lives (classpath root vs. source root vs. build-config) and the
 * extension. Used by {@link DebounceWindow} to apply the strict precedence in UC04 §10.
 */
public enum WatchEventKind {
    /** A {@code .class} file under a classpath directory root. */
    CLASSPATH_BYTECODE,
    /** A {@code .jar} or {@code .war} archive under a classpath root (single-file watch). */
    CLASSPATH_ARCHIVE,
    /** A {@code .java} file under a source root. */
    SOURCE_JAVA,
    /** A discovered build/dependency configuration file. */
    BUILD_CONFIG,
    /** Anything else — discarded. */
    UNKNOWN
}

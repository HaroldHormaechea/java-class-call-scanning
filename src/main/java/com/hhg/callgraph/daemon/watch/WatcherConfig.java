package com.hhg.callgraph.daemon.watch;

import java.nio.file.Path;

/**
 * Static configuration for the watcher subsystem, built by the CLI from
 * {@code daemon start} flags. Defaults match UC04 AC #5 / #10 / #13.
 *
 * @param enabled            false when {@code --no-watch} is in effect
 * @param debounceMs         coalescing window per AC #5 (default 1000 ms)
 * @param restartDrainMs     TCP grace period for graceful self-restart (default 2000 ms)
 * @param logFile            structured rebuild log destination (default
 *                           {@code <cache-dir>/<project-hash>.log})
 * @param logMaxSizeBytes    rotation size threshold (default 10 MB)
 * @param logMaxFiles        number of rotated files to retain (default 5)
 */
public record WatcherConfig(boolean enabled,
                            long debounceMs,
                            long restartDrainMs,
                            Path logFile,
                            long logMaxSizeBytes,
                            int logMaxFiles) {

    public static final long DEFAULT_DEBOUNCE_MS    = 1000L;
    public static final long DEFAULT_RESTART_DRAIN_MS = 2000L;
    public static final long DEFAULT_LOG_MAX_SIZE_BYTES = 10L * 1024L * 1024L;
    public static final int  DEFAULT_LOG_MAX_FILES = 5;

    /** Disabled instance — produced when the daemon was started with {@code --no-watch}. */
    public static WatcherConfig disabled(Path logFile) {
        return new WatcherConfig(false, DEFAULT_DEBOUNCE_MS, DEFAULT_RESTART_DRAIN_MS,
                logFile, DEFAULT_LOG_MAX_SIZE_BYTES, DEFAULT_LOG_MAX_FILES);
    }

    /** Convenience constructor using all defaults except {@code logFile}. */
    public static WatcherConfig defaults(Path logFile) {
        return new WatcherConfig(true, DEFAULT_DEBOUNCE_MS, DEFAULT_RESTART_DRAIN_MS,
                logFile, DEFAULT_LOG_MAX_SIZE_BYTES, DEFAULT_LOG_MAX_FILES);
    }
}

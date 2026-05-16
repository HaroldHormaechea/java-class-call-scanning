package com.hhg.callgraph.daemon.watch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hhg.callgraph.daemon.Daemon;

import java.nio.file.Path;
import java.util.List;

/**
 * Hook used by {@link DebounceWindow} to invoke a graceful in-process self-restart
 * when a build/dependency configuration file changed (UC04 §10).
 *
 * <p>The actual pipeline lives on {@link Daemon#restartInPlace(java.util.List)} —
 * keeping it on the daemon means the {@link com.hhg.callgraph.daemon.mcp.McpStdioServer}
 * reference, the project {@code FileLock}, and the {@link WatcherStatusHolder} stay
 * accessible without exposing them through extra interfaces.
 */
public final class SelfRestart {

    private final Daemon daemon;
    private final RebuildLogger logger;

    public SelfRestart(Daemon daemon, RebuildLogger logger) {
        this.daemon = daemon;
        this.logger = logger;
    }

    /** Called by the debounce window for a build-config event batch. */
    public void restart(List<Path> changedBuildConfigPaths) {
        if (logger != null) {
            JsonObject starting = new JsonObject();
            starting.addProperty("event", "watch.self_restart");
            starting.addProperty("schema_version", 1);
            starting.addProperty("trigger.watcher", "build-config");
            JsonArray paths = new JsonArray();
            if (changedBuildConfigPaths != null) {
                for (Path p : changedBuildConfigPaths) paths.add(p.toAbsolutePath().toString());
            }
            starting.add("trigger.paths", paths);
            starting.addProperty("trigger.event_count",
                    changedBuildConfigPaths == null ? 0 : changedBuildConfigPaths.size());
            starting.addProperty("phase", "starting");
            starting.addProperty("timestamp", java.time.Instant.now().toString());
            logger.emit(starting);
        }
        daemon.restartInPlace(changedBuildConfigPaths);
    }
}

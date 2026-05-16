package com.hhg.callgraph.daemon.watch;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Thread-safe holder for the {@code watcher-status} payload.
 *
 * <p><b>Two log-shape conventions live next to each other and are intentionally different
 * — do not unify them:</b>
 * <ul>
 *   <li>Structured rebuild log (UC04 AC #12): flat sibling keys with a literal {@code .}
 *       in the key name ({@code "trigger.watcher"}, {@code "scope.classes"} …). Produced
 *       by {@link RebuildLogger}.</li>
 *   <li>{@code watcher-status} payload (UC04 AC #14): nested objects with snake_case
 *       inner keys ({@code "last_rebuild": {"trigger_watcher": …}}). Produced by
 *       {@link #snapshot()}.</li>
 * </ul>
 * The two surfaces serve different consumers (jsonl parsers vs. typed clients) and were
 * specified literally in UC04. Keep them separate.
 */
public final class WatcherStatusHolder {

    /** Inner record matching the {@code last_rebuild} sub-object of AC #14. */
    public record LastRebuild(String timestamp,
                              String triggerWatcher,
                              String outcome,
                              Long elapsedMs,
                              int scopeClassCount,
                              int scopeArchiveCount) {
    }

    /** Inner record matching the {@code last_error} sub-object of AC #14. */
    public record LastError(String exceptionClass, String message, String timestamp) {
    }

    private final Object lock = new Object();
    private boolean enabled;
    private List<Path> watchedClasspathRoots = List.of();
    private List<Path> watchedSourceRoots = List.of();
    private List<Path> watchedBuildConfigFiles = List.of();
    private long debounceMs;
    private LastRebuild lastRebuild;
    private LastError lastError;
    private final Path logFile;
    private final int logSchemaVersion;

    public WatcherStatusHolder(boolean enabled, long debounceMs, Path logFile, int logSchemaVersion) {
        this.enabled = enabled;
        this.debounceMs = debounceMs;
        this.logFile = logFile;
        this.logSchemaVersion = logSchemaVersion;
    }

    /** Toggle to the disabled payload form (called when {@code --no-watch} is in effect). */
    public void setDisabled() {
        synchronized (lock) {
            this.enabled = false;
            this.watchedClasspathRoots = List.of();
            this.watchedSourceRoots = List.of();
            this.watchedBuildConfigFiles = List.of();
        }
    }

    /** Inform the holder of what the watcher actually registered. */
    public void setWatchedRoots(List<Path> classpath, List<Path> sources, List<Path> buildConfigs) {
        synchronized (lock) {
            this.watchedClasspathRoots = classpath == null ? List.of() : List.copyOf(classpath);
            this.watchedSourceRoots = sources == null ? List.of() : List.copyOf(sources);
            this.watchedBuildConfigFiles = buildConfigs == null ? List.of() : List.copyOf(buildConfigs);
        }
    }

    /** Convenience: full mutator used by every rebuild success path (UC04 §5). */
    public void recordRebuild(String triggerWatcher,
                              String outcome,
                              long elapsedMs,
                              int scopeClassCount,
                              int scopeArchiveCount) {
        synchronized (lock) {
            this.lastRebuild = new LastRebuild(
                    Instant.now().toString(),
                    Objects.requireNonNullElse(triggerWatcher, "manual"),
                    Objects.requireNonNullElse(outcome, "success"),
                    elapsedMs,
                    scopeClassCount,
                    scopeArchiveCount);
        }
    }

    /** Used by the source-only "skipped" path. */
    public void recordSkipped(String triggerWatcher, long elapsedMs) {
        synchronized (lock) {
            this.lastRebuild = new LastRebuild(
                    Instant.now().toString(),
                    Objects.requireNonNullElse(triggerWatcher, "source"),
                    "skipped",
                    elapsedMs,
                    0,
                    0);
        }
    }

    /** Used by the self-restart completed path. */
    public void recordSelfRestart(long elapsedMs, int scopeClassCount, int scopeArchiveCount) {
        synchronized (lock) {
            this.lastRebuild = new LastRebuild(
                    Instant.now().toString(),
                    "build-config",
                    "self-restart",
                    elapsedMs,
                    scopeClassCount,
                    scopeArchiveCount);
        }
    }

    /** Records an error (does NOT clear it on subsequent successful rebuilds). */
    public void recordError(Throwable t) {
        synchronized (lock) {
            String cls = t == null ? "Unknown" : t.getClass().getName();
            String msg = t == null ? "" : Objects.requireNonNullElse(t.getMessage(), t.toString());
            this.lastError = new LastError(cls, msg, Instant.now().toString());
        }
    }

    /** Clears {@link #lastError}. Idempotent. */
    public void clearError() {
        synchronized (lock) {
            this.lastError = null;
        }
    }

    /** Returns a fresh JSON payload matching UC04 AC #14 literally. */
    public JsonObject snapshot() {
        synchronized (lock) {
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.add("watched_classpath_roots", toJsonPathArray(watchedClasspathRoots));
            obj.add("watched_source_roots", toJsonPathArray(watchedSourceRoots));
            obj.add("watched_build_config_files", toJsonPathArray(watchedBuildConfigFiles));
            obj.addProperty("debounce_ms", debounceMs);

            if (lastRebuild == null) {
                obj.add("last_rebuild", JsonNull.INSTANCE);
            } else {
                JsonObject lr = new JsonObject();
                lr.addProperty("timestamp", lastRebuild.timestamp());
                lr.addProperty("trigger_watcher", lastRebuild.triggerWatcher());
                lr.addProperty("outcome", lastRebuild.outcome());
                if (lastRebuild.elapsedMs() == null) {
                    lr.add("elapsed_ms", JsonNull.INSTANCE);
                } else {
                    lr.addProperty("elapsed_ms", lastRebuild.elapsedMs());
                }
                lr.addProperty("scope_class_count", lastRebuild.scopeClassCount());
                lr.addProperty("scope_archive_count", lastRebuild.scopeArchiveCount());
                obj.add("last_rebuild", lr);
            }

            if (lastError == null) {
                obj.add("last_error", JsonNull.INSTANCE);
            } else {
                JsonObject le = new JsonObject();
                le.addProperty("class", lastError.exceptionClass());
                le.addProperty("message", lastError.message());
                le.addProperty("timestamp", lastError.timestamp());
                obj.add("last_error", le);
            }

            if (logFile != null) {
                obj.addProperty("log_file", logFile.toAbsolutePath().toString());
            } else {
                obj.add("log_file", JsonNull.INSTANCE);
            }
            obj.addProperty("log_schema_version", logSchemaVersion);
            return obj;
        }
    }

    private static JsonArray toJsonPathArray(List<Path> paths) {
        JsonArray arr = new JsonArray();
        if (paths == null) return arr;
        List<String> sortedCopy = new ArrayList<>(paths.size());
        for (Path p : paths) {
            sortedCopy.add(p == null ? "" : p.toAbsolutePath().toString());
        }
        for (String s : sortedCopy) arr.add(s);
        return arr;
    }
}

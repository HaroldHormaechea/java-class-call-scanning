package com.hhg.callgraph.daemon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves the daemon discovery directory per platform and reads / writes / deletes
 * the per-project {@code <project-hash>.json} file. Format:
 *
 * <pre>{@code
 *   { "schema": 1,
 *     "project_hash": "....",
 *     "classpath":    [ "..." ],
 *     "source_roots": [ "..." ],
 *     "include":      [ "..." ],
 *     "exclude":      [ "..." ],
 *     "pid":          12345,
 *     "port":         54321,
 *     "started_at_epoch_millis": 1234567890000,
 *     "daemon_version": "1.0-SNAPSHOT" }
 * }</pre>
 */
public final class Discovery {

    public static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path directory;
    private final String daemonVersion;

    public Discovery(Path directory, String daemonVersion) {
        this.directory = directory;
        this.daemonVersion = daemonVersion == null ? "unknown" : daemonVersion;
    }

    /** Convenience: build a Discovery rooted at the platform-default cache dir. */
    public static Discovery defaults(String daemonVersion) {
        return new Discovery(resolveCacheDirectory(), daemonVersion);
    }

    /** Resolves the platform cache directory, creating intermediate dirs lazily. */
    public static Path resolveCacheDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path base;
        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData == null || localAppData.isBlank()) {
                localAppData = System.getenv("APPDATA");
            }
            if (localAppData == null || localAppData.isBlank()) {
                base = Paths.get(System.getProperty("user.home", "."), "AppData", "Local");
            } else {
                base = Paths.get(localAppData);
            }
        } else if (os.contains("mac")) {
            base = Paths.get(System.getProperty("user.home", "."), "Library", "Caches");
        } else {
            String xdg = System.getenv("XDG_CACHE_HOME");
            if (xdg != null && !xdg.isBlank()) {
                base = Paths.get(xdg);
            } else {
                base = Paths.get(System.getProperty("user.home", "."), ".cache");
            }
        }
        return base.resolve("java-class-call-scanning");
    }

    public Path directory()                     { return directory; }
    public Path filePath(String projectHash)    { return directory.resolve(projectHash + ".json"); }
    public Path lockPath(String projectHash)    { return directory.resolve(projectHash + ".lock"); }
    public Path logPath(String projectHash)     { return directory.resolve(projectHash + ".log"); }

    public void ensureDirectory() throws IOException {
        Files.createDirectories(directory);
    }

    /** Writes the discovery file. Overwrites if present (caller verified absence). */
    public void write(ScopeConfig scope, long pid, int port, long startedAtEpochMillis) throws IOException {
        ensureDirectory();

        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA_VERSION);
        root.addProperty("project_hash", scope.projectHash());
        root.add("classpath", toJsonStringArray(scope.classpath().stream().map(Path::toString).toList()));
        root.add("source_roots", toJsonStringArray(scope.sourceRoots().stream().map(Path::toString).toList()));
        root.add("include", toJsonStringArray(scope.include()));
        root.add("exclude", toJsonStringArray(scope.exclude()));
        root.addProperty("pid", pid);
        root.addProperty("port", port);
        root.addProperty("started_at_epoch_millis", startedAtEpochMillis);
        root.addProperty("daemon_version", daemonVersion);

        Path target = filePath(scope.projectHash());
        Files.writeString(target, GSON.toJson(root) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static JsonArray toJsonStringArray(List<String> items) {
        JsonArray arr = new JsonArray();
        for (String s : items) arr.add(s);
        return arr;
    }

    /** Reads and parses the discovery file at the given path. */
    public static Optional<Record> read(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(content).getAsJsonObject();
            int schema = obj.has("schema") ? obj.get("schema").getAsInt() : -1;
            if (schema != SCHEMA_VERSION) {
                return Optional.empty();
            }
            String hash = obj.get("project_hash").getAsString();
            List<String> cp = stringList(obj, "classpath");
            List<String> sr = stringList(obj, "source_roots");
            List<String> inc = stringList(obj, "include");
            List<String> exc = stringList(obj, "exclude");
            long pid = obj.get("pid").getAsLong();
            int port = obj.get("port").getAsInt();
            long started = obj.has("started_at_epoch_millis")
                    ? obj.get("started_at_epoch_millis").getAsLong() : 0L;
            String ver = obj.has("daemon_version") ? obj.get("daemon_version").getAsString() : "unknown";
            return Optional.of(new Record(hash, cp, sr, inc, exc, pid, port, started, ver));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static List<String> stringList(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) return List.of();
        JsonArray arr = obj.getAsJsonArray(key);
        List<String> out = new java.util.ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) out.add(arr.get(i).getAsString());
        return out;
    }

    public boolean delete(String projectHash) {
        Path target = filePath(projectHash);
        try {
            return Files.deleteIfExists(target);
        } catch (IOException e) {
            return false;
        }
    }

    /** Checks whether the recorded PID is alive on this host. */
    public static boolean pidAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /**
     * Parsed snapshot of a discovery file.
     */
    public record Record(String projectHash, List<String> classpath, List<String> sourceRoots,
                         List<String> include, List<String> exclude,
                         long pid, int port, long startedAtEpochMillis, String daemonVersion) {
    }

    /**
     * Exception thrown when a discovery file claims a slot that is still alive.
     * Not used for control flow inside this class, but exposed for callers.
     */
    public static final class AlreadyRunningException extends FileAlreadyExistsException {
        public final long pid;
        public final int port;
        public AlreadyRunningException(Path file, long pid, int port) {
            super(file.toString());
            this.pid = pid;
            this.port = port;
        }
    }
}

package com.hhg.callgraph.daemon.watch;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Polling helper for the watcher's rotating JSON-lines log. Used by the
 * {@code FileWatcher*Test} suite to avoid timing-fragile {@code Thread.sleep(debounce + ε)}
 * waits — instead we poll the structured log until the assertion-matching line appears.
 *
 * <p>Defaults: {@value #DEFAULT_TIMEOUT_MS} ms on Linux/Windows, {@value #MACOS_TIMEOUT_MS} ms
 * on macOS where {@link java.nio.file.WatchService} is backed by polling kqueue and adds
 * multi-second latency between FS write and event (UC04 §18).
 *
 * <p>Threading: the helper opens-and-reads the file on each poll; the underlying
 * {@link RotatingLogFile} writer uses {@code CREATE + APPEND} so concurrent reads
 * are safe.
 */
public final class LogFileAssertions {

    public static final long DEFAULT_TIMEOUT_MS = 10_000L;
    public static final long MACOS_TIMEOUT_MS   = 15_000L;
    private static final long POLL_INTERVAL_MS = 50L;

    private LogFileAssertions() {}

    /** Returns the platform-appropriate default timeout. */
    public static Duration defaultTimeout() {
        boolean isMac = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("mac");
        return Duration.ofMillis(isMac ? MACOS_TIMEOUT_MS : DEFAULT_TIMEOUT_MS);
    }

    /**
     * Polls {@code logFile} until {@code predicate} matches one of its JSON-object lines
     * or the timeout elapses. Returns the matched line on success. Fails the test with
     * a descriptive message on timeout, including the file's tail content.
     */
    public static JsonObject awaitLogLine(Path logFile,
                                          Predicate<JsonObject> predicate,
                                          Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Optional<JsonObject> hit;
        do {
            hit = scanForMatch(logFile, predicate);
            if (hit.isPresent()) return hit.get();
            try { Thread.sleep(POLL_INTERVAL_MS); }
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for log line at " + logFile);
            }
        } while (System.nanoTime() < deadline);

        fail("timed out after " + timeout.toMillis() + " ms waiting for matching log line in "
                + logFile + "\nFull contents:\n" + safeRead(logFile));
        return null; // unreachable
    }

    /** Convenience overload using the platform default timeout. */
    public static JsonObject awaitLogLine(Path logFile, Predicate<JsonObject> predicate) {
        return awaitLogLine(logFile, predicate, defaultTimeout());
    }

    /** Reads every JSON line in {@code logFile} and returns them in order. */
    public static List<JsonObject> readAllLogLines(Path logFile) {
        List<JsonObject> out = new ArrayList<>();
        if (!Files.exists(logFile)) return out;
        try {
            for (String line : Files.readAllLines(logFile, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) continue;
                try {
                    out.add(JsonParser.parseString(line).getAsJsonObject());
                } catch (JsonSyntaxException ignored) {
                    // Skip non-JSON lines defensively.
                }
            }
        } catch (IOException e) {
            fail("failed reading log file " + logFile + ": " + e);
        }
        return out;
    }

    /** Convenience matcher: returns true when {@code event} equals the line's {@code event} field. */
    public static Predicate<JsonObject> eventEquals(String event) {
        return obj -> obj.has("event") && event.equals(obj.get("event").getAsString());
    }

    /** Matches a {@code watch.rebuild} log line with the given outcome. */
    public static Predicate<JsonObject> rebuildOutcome(String outcome) {
        return obj -> obj.has("event")
                && "watch.rebuild".equals(obj.get("event").getAsString())
                && obj.has("outcome")
                && outcome.equals(obj.get("outcome").getAsString());
    }

    /** Matches a {@code watch.self_restart} log line with the given phase. */
    public static Predicate<JsonObject> selfRestartPhase(String phase) {
        return obj -> obj.has("event")
                && "watch.self_restart".equals(obj.get("event").getAsString())
                && obj.has("phase")
                && phase.equals(obj.get("phase").getAsString());
    }

    private static Optional<JsonObject> scanForMatch(Path logFile, Predicate<JsonObject> predicate) {
        if (!Files.exists(logFile)) return Optional.empty();
        try {
            for (String line : Files.readAllLines(logFile, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) continue;
                JsonObject obj;
                try {
                    obj = JsonParser.parseString(line).getAsJsonObject();
                } catch (JsonSyntaxException ignored) {
                    continue;
                }
                if (predicate.test(obj)) return Optional.of(obj);
            }
        } catch (IOException ignored) {
            // Best-effort — try again next poll.
        }
        return Optional.empty();
    }

    private static String safeRead(Path logFile) {
        try {
            return Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8)
                    : "(file does not exist)";
        } catch (IOException e) {
            return "(failed to read: " + e + ")";
        }
    }
}

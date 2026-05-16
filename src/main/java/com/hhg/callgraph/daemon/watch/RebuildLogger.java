package com.hhg.callgraph.daemon.watch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;

/**
 * Emits one JSON object per line to both {@link System#err} <em>and</em> a
 * {@link RotatingLogFile}. UC04 AC #12 / AC #13: every rebuild attempt and every
 * self-restart phase produces exactly one log line, and the file form must remain
 * a tailable jsonl stream.
 *
 * <p>A per-instance lock guarantees concurrent writers don't interleave bytes.
 */
public final class RebuildLogger {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final RotatingLogFile file;     // may be null when --no-watch
    private final Object lock = new Object();

    public RebuildLogger(RotatingLogFile file) {
        this.file = file;
    }

    /** Serialise {@code obj} as a single JSON line and write to both sinks. */
    public void emit(JsonObject obj) {
        if (obj == null) return;
        String line = GSON.toJson(obj);
        synchronized (lock) {
            System.err.println(line);
            if (file != null) {
                try {
                    file.write(line);
                } catch (IOException e) {
                    System.err.println("rebuild-logger: file write failed: " + e);
                }
            }
        }
    }
}

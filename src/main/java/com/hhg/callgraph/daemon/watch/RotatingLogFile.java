package com.hhg.callgraph.daemon.watch;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Append-only writer with size-based rotation for the watcher's structured-log file.
 * Format: one self-contained line per call to {@link #write(String)}; the writer
 * adds the trailing newline itself.
 *
 * <p>Rotation rule (UC04 AC #13): if {@code size + lineBytes > maxSize}, rotate
 * <em>before</em> writing — rename {@code <base>.log} → {@code <base>.log.1}, shift
 * {@code .log.{i}} → {@code .log.{i+1}} up to {@code maxFiles}, drop the oldest.
 * Thread-safe via internal synchronization.
 */
public final class RotatingLogFile implements AutoCloseable {

    private final Path baseFile;
    private final long maxSize;
    private final int maxFiles;
    private final Object lock = new Object();

    public RotatingLogFile(Path baseFile, long maxSize, int maxFiles) {
        this.baseFile = baseFile;
        this.maxSize = maxSize > 0 ? maxSize : Long.MAX_VALUE;
        this.maxFiles = Math.max(1, maxFiles);
    }

    /** Writes one line, appending a trailing newline. Rotates if needed. */
    public void write(String line) throws IOException {
        if (line == null) return;
        byte[] bytes = (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        synchronized (lock) {
            ensureParent();
            long size = currentSize();
            if (size + bytes.length > maxSize) {
                rotate();
            }
            try (OutputStream os = Files.newOutputStream(baseFile,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                os.write(bytes);
            }
        }
    }

    /** Returns the destination file (for status payloads). */
    public Path logFile() {
        return baseFile;
    }

    private long currentSize() {
        try {
            return Files.exists(baseFile) ? Files.size(baseFile) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    private void ensureParent() throws IOException {
        Path parent = baseFile.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }

    private void rotate() {
        try {
            Path oldest = numbered(maxFiles);
            try { Files.deleteIfExists(oldest); } catch (IOException ignored) {}
            // shift backwards: .log.{N-1} -> .log.N
            for (int i = maxFiles - 1; i >= 1; i--) {
                Path src = numbered(i);
                Path dst = numbered(i + 1);
                if (Files.exists(src)) {
                    try {
                        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException ignored) {}
                }
            }
            // move base to .log.1
            if (Files.exists(baseFile)) {
                Path dst = numbered(1);
                try {
                    Files.move(baseFile, dst, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {}
            }
        } catch (Throwable t) {
            // Don't let rotation crash the writer — log to stderr.
            System.err.println("rotating-log: rotation failed: " + t);
        }
    }

    private Path numbered(int idx) {
        return baseFile.resolveSibling(baseFile.getFileName().toString() + "." + idx);
    }

    /** No-op: each write opens & closes its own stream. */
    @Override
    public void close() {
        // intentionally empty
    }
}

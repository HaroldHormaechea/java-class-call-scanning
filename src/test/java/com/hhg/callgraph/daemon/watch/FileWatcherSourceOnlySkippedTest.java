package com.hhg.callgraph.daemon.watch;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static com.hhg.callgraph.daemon.watch.LogFileAssertions.awaitLogLine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * UC04 AC #17(d) — a {@code .java}-only change under a source root emits a
 * {@code skipped / source-only-no-bytecode-change} log entry and does NOT mutate the
 * index. The daemon does not invoke the build itself.
 *
 * <p>Two scenarios:
 * <ol>
 *   <li>Modifying an existing {@code .java} file emits the skipped log line.</li>
 *   <li>Creating a brand new {@code .java} file under a source root also emits the
 *       skipped log line — there is no matching {@code .class} so we still log a
 *       breadcrumb.</li>
 * </ol>
 */
@DisplayName("FileWatcher — source-only changes are skipped (UC04 AC #17(d))")
class FileWatcherSourceOnlySkippedTest {

    @Test
    @DisplayName("modifying an existing .java file emits the 'skipped, source-only-no-bytecode-change' log line")
    void existingJavaFileModification(@TempDir Path tmp) throws IOException {
        Path classpath = tmp.resolve("classes");
        Files.createDirectories(classpath);
        Path source = tmp.resolve("src");
        Files.createDirectories(source.resolve("com/example"));
        Path javaFile = source.resolve("com/example/Foo.java");
        Files.writeString(javaFile, "package com.example;\npublic class Foo {}\n");
        Path buildGradle = tmp.resolve("build.gradle");
        Files.writeString(buildGradle, "plugins { id 'java' }\n");
        Path logFile = tmp.resolve("watcher.log");

        // Seed at least one class so the snapshot isn't trivially empty.
        FileWatcherTestFixture.seedClass(
                classpath.resolve("com/hhg/benchmark/service/UserService.class"),
                "service/UserService.class");

        try (FileWatcherTestFixture fixture = new FileWatcherTestFixture(
                classpath, source, buildGradle, logFile, /*debounceMs=*/ 250)) {

            var snapshotBefore = fixture.indexRef.get();

            Files.writeString(javaFile, "package com.example;\npublic class Foo { /* edit */ }\n");

            JsonObject line = awaitLogLine(logFile,
                    obj -> obj.has("outcome")
                            && "skipped".equals(obj.get("outcome").getAsString())
                            && obj.has("outcome.reason")
                            && "source-only-no-bytecode-change"
                                    .equals(obj.get("outcome.reason").getAsString()),
                    Duration.ofSeconds(15));
            assertNotNull(line);
            assertEquals("source", line.get("trigger.watcher").getAsString());
            assertEquals("watch.rebuild", line.get("event").getAsString());

            // The index reference must be the SAME object — no swap happened.
            assertSame(snapshotBefore, fixture.indexRef.get(),
                    "source-only events MUST NOT mutate the index");

            // watcher-status records the skipped event.
            JsonObject status = fixture.statusHolder.snapshot();
            JsonObject lr = status.getAsJsonObject("last_rebuild");
            assertEquals("source", lr.get("trigger_watcher").getAsString());
            assertEquals("skipped", lr.get("outcome").getAsString());
        }
    }

    @Test
    @DisplayName("creating a brand-new .java file under a source root also emits a skipped log")
    void newJavaFileCreation(@TempDir Path tmp) throws IOException {
        Path classpath = tmp.resolve("classes");
        Files.createDirectories(classpath);
        Path source = tmp.resolve("src");
        Files.createDirectories(source.resolve("com/example"));
        Path buildGradle = tmp.resolve("build.gradle");
        Files.writeString(buildGradle, "plugins { id 'java' }\n");
        Path logFile = tmp.resolve("watcher.log");

        FileWatcherTestFixture.seedClass(
                classpath.resolve("com/hhg/benchmark/service/UserService.class"),
                "service/UserService.class");

        try (FileWatcherTestFixture fixture = new FileWatcherTestFixture(
                classpath, source, buildGradle, logFile, /*debounceMs=*/ 250)) {

            var snapshotBefore = fixture.indexRef.get();

            Path newJava = source.resolve("com/example/Bar.java");
            Files.writeString(newJava,
                    "package com.example;\npublic class Bar {}\n");

            JsonObject line = awaitLogLine(logFile,
                    obj -> obj.has("outcome")
                            && "skipped".equals(obj.get("outcome").getAsString()),
                    Duration.ofSeconds(15));
            assertNotNull(line);
            assertSame(snapshotBefore, fixture.indexRef.get(),
                    "newly created .java file alone must not swap the index");
        }
    }
}

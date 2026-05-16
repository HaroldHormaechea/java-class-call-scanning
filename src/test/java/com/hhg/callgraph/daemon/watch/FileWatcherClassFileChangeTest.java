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
import static com.hhg.callgraph.daemon.watch.LogFileAssertions.rebuildOutcome;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UC04 AC #17(a) — modifying a {@code .class} file under a classpath root triggers an
 * incremental rebuild whose log lists the expected FQN.
 *
 * <p>End-to-end through the real watcher → debounce window → incremental rebuilder →
 * rotating log file. We assert against the structured log rather than waiting for
 * {@code Thread.sleep(debounce + ε)} (per the proposal's no-sleep policy).
 */
@DisplayName("FileWatcher — class file modification triggers incremental rebuild (UC04 AC #17(a))")
class FileWatcherClassFileChangeTest {

    @Test
    @DisplayName("modifying UserService.class produces a 'success' log line listing the FQN")
    void classFileModificationTriggersRebuild(@TempDir Path tmp) throws IOException {
        Path classpath = tmp.resolve("classes");
        Files.createDirectories(classpath);
        Path source = tmp.resolve("src");
        Files.createDirectories(source);
        Path buildGradle = tmp.resolve("build.gradle");
        Files.writeString(buildGradle, "plugins { id 'java' }\n");
        Path logFile = tmp.resolve("watcher.log");

        // Seed UserService.class into the classpath dir.
        Path userServiceClass = classpath.resolve("com/hhg/benchmark/service/UserService.class");
        FileWatcherTestFixture.seedClass(userServiceClass, "service/UserService.class");

        try (FileWatcherTestFixture fixture = new FileWatcherTestFixture(
                classpath, source, buildGradle, logFile, /*debounceMs=*/ 250)) {

            int methodsBefore = fixture.indexRef.get().scan().callGraph().methodCount();
            assertTrue(methodsBefore > 0);

            // Rewrite UserService.class with the same bytes — that counts as a modification.
            // We don't change semantics; the rebuild just re-scans and produces equal counts.
            byte[] bytes = Files.readAllBytes(userServiceClass);
            Files.write(userServiceClass, bytes);
            // Touch the mtime explicitly in case the JVM optimised the same-content write.
            Files.setLastModifiedTime(userServiceClass,
                    java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));

            JsonObject line = awaitLogLine(logFile, rebuildOutcome("success"));
            assertNotNull(line);
            assertEquals("classpath", line.get("trigger.watcher").getAsString());
            assertEquals("watch.rebuild", line.get("event").getAsString());
            assertTrue(line.get("scope.classes").getAsJsonArray().size() >= 1,
                    "scope.classes must list the affected class");
            boolean found = false;
            for (var el : line.get("scope.classes").getAsJsonArray()) {
                if (el.getAsString().contains("UserService")) { found = true; break; }
            }
            assertTrue(found, "scope.classes must list UserService; got "
                    + line.get("scope.classes"));

            // Index swapped — methods count should remain consistent (same bytecode in).
            assertEquals(methodsBefore,
                    fixture.indexRef.get().scan().callGraph().methodCount());
            // watcher-status records the rebuild.
            JsonObject status = fixture.statusHolder.snapshot();
            JsonObject lr = status.getAsJsonObject("last_rebuild");
            assertEquals("classpath", lr.get("trigger_watcher").getAsString());
            assertEquals("success", lr.get("outcome").getAsString());
        }
    }

    @Test
    @DisplayName("adding a brand-new .class file under the classpath also rebuilds")
    void newClassFileTriggersRebuild(@TempDir Path tmp) throws IOException {
        Path classpath = tmp.resolve("classes");
        Files.createDirectories(classpath);
        Path source = tmp.resolve("src");
        Files.createDirectories(source);
        Path buildGradle = tmp.resolve("build.gradle");
        Files.writeString(buildGradle, "plugins { id 'java' }\n");
        Path logFile = tmp.resolve("watcher.log");
        // Seed one class so the watcher has something to register.
        Path userServiceClass = classpath.resolve("com/hhg/benchmark/service/UserService.class");
        FileWatcherTestFixture.seedClass(userServiceClass, "service/UserService.class");

        try (FileWatcherTestFixture fixture = new FileWatcherTestFixture(
                classpath, source, buildGradle, logFile, /*debounceMs=*/ 250)) {

            int methodsBefore = fixture.indexRef.get().scan().callGraph().methodCount();
            Path newClass = classpath.resolve("com/hhg/benchmark/service/CustomerService.class");
            FileWatcherTestFixture.seedClass(newClass, "service/CustomerService.class");

            awaitLogLine(logFile, rebuildOutcome("success"), Duration.ofSeconds(15));

            int methodsAfter = fixture.indexRef.get().scan().callGraph().methodCount();
            assertTrue(methodsAfter > methodsBefore,
                    "adding a new class should grow the index; before=" + methodsBefore
                            + " after=" + methodsAfter);
        }
    }
}

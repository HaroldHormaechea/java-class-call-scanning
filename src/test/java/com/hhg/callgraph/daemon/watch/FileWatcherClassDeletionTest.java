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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UC04 AC #17(c) — deleting a {@code .class} file under a classpath root removes its
 * contributions in the next swapped snapshot.
 *
 * <p>Deletion detection works in two layers per UC04 §8: an ENTRY_DELETE event arrives
 * from the WatchService, and a directory-reconciliation walk at the end of the debounce
 * window safety-nets the case where the rename/atomic-replace channel hid the deletion.
 * This test exercises the happy path of the first layer.
 */
@DisplayName("FileWatcher — class file deletion (UC04 AC #17(c))")
class FileWatcherClassDeletionTest {

    @Test
    @DisplayName("deleting UserService.class removes its contributions from the next snapshot")
    void deletionRemovesContributions(@TempDir Path tmp) throws IOException {
        Path classpath = tmp.resolve("classes");
        Files.createDirectories(classpath);
        Path source = tmp.resolve("src");
        Files.createDirectories(source);
        Path buildGradle = tmp.resolve("build.gradle");
        Files.writeString(buildGradle, "plugins { id 'java' }\n");
        Path logFile = tmp.resolve("watcher.log");

        // Seed two classes so we can verify only one is removed.
        Path userServiceClass = classpath.resolve("com/hhg/benchmark/service/UserService.class");
        Path customerServiceClass = classpath.resolve("com/hhg/benchmark/service/CustomerService.class");
        FileWatcherTestFixture.seedClass(userServiceClass, "service/UserService.class");
        FileWatcherTestFixture.seedClass(customerServiceClass, "service/CustomerService.class");

        try (FileWatcherTestFixture fixture = new FileWatcherTestFixture(
                classpath, source, buildGradle, logFile, /*debounceMs=*/ 250)) {

            assertTrue(fixture.indexRef.get().scan().classOrigins()
                    .containsKey("com/hhg/benchmark/service/UserService"));
            int classesBefore = fixture.indexRef.get().scan().classOrigins().size();

            Files.delete(userServiceClass);

            JsonObject line = awaitLogLine(logFile, rebuildOutcome("success"),
                    Duration.ofSeconds(15));
            assertNotNull(line);
            assertEquals("classpath", line.get("trigger.watcher").getAsString());

            // After the rebuild, UserService is no longer in classOrigins.
            assertFalse(fixture.indexRef.get().scan().classOrigins()
                    .containsKey("com/hhg/benchmark/service/UserService"),
                    "UserService origin must be removed after deletion");
            assertTrue(fixture.indexRef.get().scan().classOrigins()
                    .containsKey("com/hhg/benchmark/service/CustomerService"),
                    "CustomerService should still be present");
            assertEquals(classesBefore - 1,
                    fixture.indexRef.get().scan().classOrigins().size());

            // scope.classes lists UserService.
            boolean listed = false;
            for (var el : line.get("scope.classes").getAsJsonArray()) {
                if (el.getAsString().contains("UserService")) { listed = true; break; }
            }
            assertTrue(listed, "scope.classes must list the deleted class");
        }
    }

    private static void assertEquals(String expected, String actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
    private static void assertEquals(int expected, int actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}

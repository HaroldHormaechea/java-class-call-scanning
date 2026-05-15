package com.hhg.callgraph;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Single source of truth for the build-time version string at runtime.
 *
 * <p>The version is supplied by Gradle's {@code processResources} task, which
 * expands the {@code ${version}} token in
 * {@code src/main/resources/com/hhg/callgraph/build-version.properties} from
 * {@code project.version} in {@code build.gradle}. Both
 * {@code Daemon.DAEMON_VERSION} and {@code McpStdioServer.SERVER_VERSION}
 * resolve through this class so the two constants cannot drift apart.
 *
 * <p>The resource is loaded lazily on first access via the
 * Initialization-on-demand Holder Idiom (IODH), so a missing or malformed
 * resource surfaces as an {@link IllegalStateException} from {@link #value()}
 * rather than during class-loading of unrelated callers.
 */
public final class BuildVersion {

    private BuildVersion() {
        // non-instantiable
    }

    /**
     * Returns the build-time version string (e.g. {@code "0.1.1"}).
     *
     * @throws IllegalStateException if the {@code build-version.properties}
     *                               resource is missing from the classpath,
     *                               cannot be read, or does not contain a
     *                               non-blank {@code version} property.
     */
    public static String value() {
        return Holder.VALUE;
    }

    private static final class Holder {
        private static final String RESOURCE_NAME = "build-version.properties";
        private static final String VALUE = load();

        private static String load() {
            try (InputStream in = BuildVersion.class.getResourceAsStream(RESOURCE_NAME)) {
                if (in == null) {
                    throw new IllegalStateException("Missing classpath resource: com/hhg/callgraph/" + RESOURCE_NAME + " (loaded by " + BuildVersion.class.getName() + ")");
                }
                Properties props = new Properties();
                props.load(in);
                String value = props.getProperty("version");
                if (value == null) {
                    throw new IllegalStateException("Missing 'version' key in classpath resource: com/hhg/callgraph/" + RESOURCE_NAME + " (loaded by " + BuildVersion.class.getName() + ")");
                }
                if (value.isBlank()) {
                    throw new IllegalStateException("Blank 'version' value in classpath resource: com/hhg/callgraph/" + RESOURCE_NAME + " (loaded by " + BuildVersion.class.getName() + ")");
                }
                return value.trim();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read classpath resource: com/hhg/callgraph/" + RESOURCE_NAME + " (loaded by " + BuildVersion.class.getName() + ")", e);
            }
        }
    }
}

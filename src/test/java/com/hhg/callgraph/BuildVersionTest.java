package com.hhg.callgraph;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Use Case 03 — embed build version at runtime.
 *
 * <p>This is the canonical assertion source for AC #9 (runtime/build identity)
 * and AC #8 (clear failure when the build-generated resource is missing). The
 * two runtime constants {@code Daemon.DAEMON_VERSION} and
 * {@code McpStdioServer.SERVER_VERSION} are both wired to
 * {@link BuildVersion#value()}, so verifying that {@code value()} matches
 * {@code project.version} transitively proves both constants do.
 *
 * <p>{@code build.gradle} exposes {@code project.version} to tests via the
 * {@code project.version} system property (see {@code test { systemProperty
 * 'project.version', project.version }}). The IDE may not set this property,
 * so {@link #valueMatchesBuildGradleProjectVersion()} uses {@link Assumptions}
 * to skip rather than fail when the property is absent.
 */
@DisplayName("BuildVersion — UC 03 build-time version embedding")
class BuildVersionTest {

    @Test
    @DisplayName("value() returns a non-null, non-blank version string (AC #1, #2, #3, #4)")
    void valueIsNotBlank() {
        String v = BuildVersion.value();
        assertNotNull(v, "BuildVersion.value() must not be null");
        assertTrue(!v.isBlank(),
                "BuildVersion.value() must not be blank; was '" + v + "'");
        // Defensive: the template token must never reach runtime.
        assertTrue(!v.contains("${"),
                "BuildVersion.value() must not contain an unexpanded Gradle token; "
                        + "got '" + v + "' — processResources did not run");
    }

    @Test
    @DisplayName("value() equals build.gradle's project.version (AC #9 runtime/build identity)")
    void valueMatchesBuildGradleProjectVersion() {
        String projectVersion = System.getProperty("project.version");
        Assumptions.assumeTrue(projectVersion != null && !projectVersion.isBlank(),
                "Skipping: 'project.version' system property is unset (run via "
                        + "`./gradlew test`; build.gradle wires this through "
                        + "test { systemProperty 'project.version', project.version })");
        assertEquals(projectVersion, BuildVersion.value(),
                "BuildVersion.value() must equal build.gradle's project.version — "
                        + "any drift means processResources did not run or the "
                        + "resource was not on the classpath");
    }

    /**
     * AC #8 — error-path coverage. Loads {@link BuildVersion} through an isolated
     * {@link URLClassLoader} that hides {@code build-version.properties}, then
     * invokes {@code value()} reflectively. The static-holder pattern wraps the
     * underlying {@link IllegalStateException} in an {@link ExceptionInInitializerError}
     * per JLS §12.4.2 (failure during class initialization), so we unwrap one
     * level and assert the cause carries the expected message naming the
     * resource path and the loading class.
     */
    @Test
    @DisplayName("missing resource ⇒ clear IllegalStateException naming the resource + class (AC #8)")
    void missingResourceThrowsIllegalStateException() throws Exception {
        URL[] urls = classpathAsUrls();
        Assumptions.assumeTrue(urls.length > 0,
                "Skipping: cannot resolve java.class.path into URLs");

        // Parent = platform CL so the BuildVersion class itself is found only via
        // our URLs (forcing a fresh class with its own Holder), and so the parent
        // cannot serve build-version.properties either.
        try (URLClassLoader isolated = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader()) {
            @Override
            public URL findResource(String name) {
                if ("com/hhg/callgraph/build-version.properties".equals(name)) {
                    return null; // hide the build-generated resource
                }
                return super.findResource(name);
            }
        }) {
            Class<?> bv = Class.forName("com.hhg.callgraph.BuildVersion", true, isolated);
            // Sanity: must be a *different* Class from the one the test classloader
            // already loaded — otherwise the Holder is already initialized and we
            // wouldn't be exercising the missing-resource branch.
            assertTrue(bv != BuildVersion.class,
                    "isolated BuildVersion class must be distinct from the test's "
                            + "BuildVersion class (got the same Class object — the "
                            + "isolated classloader is delegating to the app loader)");

            Method value = bv.getMethod("value");
            InvocationTargetException ite = assertThrows(InvocationTargetException.class,
                    () -> value.invoke(null),
                    "value() must throw when build-version.properties is missing");

            Throwable cause = ite.getCause();
            assertInstanceOf(ExceptionInInitializerError.class, cause,
                    "Holder initialization failure must surface as "
                            + "ExceptionInInitializerError (JLS §12.4.2); got: " + cause);

            Throwable rootCause = cause.getCause();
            assertInstanceOf(IllegalStateException.class, rootCause,
                    "ExceptionInInitializerError must wrap our IllegalStateException; got: "
                            + rootCause);

            String msg = rootCause.getMessage();
            assertNotNull(msg, "IllegalStateException must carry a message");
            assertTrue(msg.contains("build-version.properties"),
                    "error message must name the missing resource; got: " + msg);
            assertTrue(msg.contains("com/hhg/callgraph/build-version.properties"),
                    "error message must give the fully-qualified resource path; got: " + msg);
            assertTrue(msg.contains(bv.getName()),
                    "error message must name the loading class (" + bv.getName()
                            + "); got: " + msg);
        }
    }

    /**
     * Returns the current JVM classpath as URLs so we can build an isolating
     * {@link URLClassLoader}. The app classloader in Java 9+ is not a
     * {@link URLClassLoader}, so we go via the {@code java.class.path} system
     * property instead.
     */
    private static URL[] classpathAsUrls() {
        String cp = System.getProperty("java.class.path", "");
        if (cp.isEmpty()) return new URL[0];
        List<URL> out = new ArrayList<>();
        for (String entry : cp.split(File.pathSeparator)) {
            if (entry.isEmpty()) continue;
            try {
                out.add(Path.of(entry).toUri().toURL());
            } catch (Exception e) {
                fail("could not convert classpath entry '" + entry + "' to URL: " + e);
            }
        }
        return out.toArray(URL[]::new);
    }
}

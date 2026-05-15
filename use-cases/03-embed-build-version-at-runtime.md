# Use Case 03: Embed build version into runtime constants

## Summary
The daemon and MCP stdio server both report a hardcoded `"1.0-SNAPSHOT"` string at runtime, regardless of the actual `version` declared in `build.gradle`. Specifically, `Daemon.java` defines `public static final String DAEMON_VERSION = "1.0-SNAPSHOT"` (used in the discovery file under the `daemon_version` key for compatibility checks) and `McpStdioServer.java` defines `private static final String SERVER_VERSION = "1.0-SNAPSHOT"` (returned in the MCP `initialize` handshake's `serverInfo.version`). The released v0.1.0 fat JAR ships with these literals unchanged, so an MCP client introspecting `serverInfo.version` and a CLI client inspecting the discovery file both see the wrong version. The fix is to derive both constants from a build-generated classpath resource so the runtime version always matches `project.version` in `build.gradle`. The Gradle wrapper for this is `processResources` with token expansion against a `src/main/resources/version.properties` template; the JDK can read the resource at class-load time via `getResourceAsStream`. No new runtime dependencies are introduced.

## Acceptance Criteria
1. `src/main/resources/version.properties` (or a similarly-named single resource) exists in the source tree with a `version=` line that gets expanded at build time from Gradle's `project.version`.
2. `build.gradle` configures `processResources` (or an equivalent Gradle task hook) so the version token in that resource is replaced during `./gradlew processResources` / `./gradlew build`.
3. `Daemon.DAEMON_VERSION` is no longer a hardcoded literal — its value is read from the build-generated resource at class-load time (or on first access via a static initializer / holder pattern).
4. `McpStdioServer.SERVER_VERSION` is no longer a hardcoded literal — its value comes from the same source as `DAEMON_VERSION`.
5. The two constants share a single resolution path (one helper class, one lookup, not two copies of the same IO code) to avoid drift.
6. When `./gradlew shadowJar` is run with `version = '0.1.1'` in `build.gradle`, the resulting fat JAR's MCP `serverInfo.version` reports `"0.1.1"` (verified by spawning the daemon in `--foreground` and sending an MCP `initialize` request).
7. When the same fat JAR writes the discovery file at daemon startup, the file's `daemon_version` field is `"0.1.1"` (verified by reading `~/.cache/java-class-call-scanning/<hash>.json`).
8. If the build-generated resource is somehow missing at runtime (e.g., classpath issue), the lookup throws a clear, single-line error message naming the missing resource and the class trying to load it — not a silent fallback to a hardcoded default.
9. All existing tests still pass under `./gradlew test` with the changes in place.
10. No new third-party dependencies are added to `build.gradle`. Only the JDK's `java.util.Properties` and `getResourceAsStream` are used to load the resource.
11. The fat JAR (`./gradlew shadowJar`) includes the build-generated `version.properties` file (verified by inspecting the JAR contents — `unzip -l` should show it).

## Potential Pitfalls & Open Questions
- **Risk** — *shadowJar resource processing.* The Shadow plugin re-bundles resources from the main source set; confirm the build-generated (token-expanded) version of `version.properties` ends up in the fat JAR, not the unprocessed template. If both end up in the JAR, the first one on the classpath wins — verify the order.
- **Risk** — *Resource caching on incremental builds.* `processResources` is up-to-date checked. If the token expansion uses `expand([version: project.version])` from the Groovy DSL, Gradle should re-run when `project.version` changes; but if the implementation uses `filter` with `ReplaceTokens`, the up-to-date check sometimes misses version-only changes. Confirm an iterative `./gradlew clean shadowJar` with a different version produces an updated JAR.
- **Edge case** — *Class-load failure surface.* If the resource is missing at class-load time and the static initializer throws, the daemon and the MCP server both fail to even start. Prefer lazy initialization (load on first access) or a single eager check at app startup with a clear error.
- **Edge case** — *No version assertion in existing tests.* Some tests may not have caught this bug because they don't assert on `serverInfo.version` or `daemon_version`. The dev-team should add at least one focused test that the embedded version matches the build-time `project.version` (using `BuildConfig`-style indirection or by parsing `build.gradle` from the test, since Gradle exposes `project.version` to tests via system properties or `org.gradle.test-retry.version`).

## Original Description
Two hardcoded string constants in the codebase report the wrong version after a release. `McpStdioServer.SERVER_VERSION = "1.0-SNAPSHOT"` is what the MCP server announces in its `initialize` handshake, and `Daemon.DAEMON_VERSION = "1.0-SNAPSHOT"` is written into the daemon's discovery file as `daemon_version`. The v0.1.0 release I just built still reports `1.0-SNAPSHOT` for both, which is wrong — they should reflect the actual build version. The fix is to inject the version from `build.gradle` at build time (via Gradle resource processing) and load it at runtime from a properties file on the classpath, so the two constants always match what's in `build.gradle`.

## Clarifications
(None — captured directly without an interactive clarification loop; AC contract is intentionally explicit to cover the bug surface.)

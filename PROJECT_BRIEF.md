---
schema_version: 1
project:
  name: java-class-call-scanning
  maturity_target: mvp
stack:
  languages: [java]
  frameworks: []
  runtimes: [jvm]
  versions:
    java: "21"
    gradle: "8.0"
    asm: "9.8"
    gson: "2.11.0"
    junit_jupiter: "5.9.1"
    shadow_plugin: "8.1.1"
  data_stores: []
build:
  tool: gradle
  commands:
    test: "./gradlew test"
    lint: null
    format: null
paths:
  production:
    - "src/main/java/**"
  test:
    - "src/test/java/**"
  api_boundary: null
test:
  framework: junit5
  levels: [unit, integration]
  coverage_target: "none — tests required for new logic, no enforced number"
profiles: []
deployment:
  provider: "GitHub Releases"
  iac: none
  environments: [development, release]
vcs:
  enabled: true
  already_initialized: true
  default_branch: main
  remote: "https://github.com/HaroldHormaechea/java-class-call-scanning.git"
use_cases:
  index: USE_CASES.md
  folder: use-cases/
---

# Project Brief

## Overview

- **Name:** java-class-call-scanning
- **Problem:** Java CI suites typically run the full test set even when a change affects a small subset of code, wasting build minutes and feedback time.
- **Users:**
  1. Java developers working on medium-to-large multi-module codebases (Gradle/Maven; e.g., Spring Framework) who want fast local feedback on which tests cover their change.
  2. CI systems that can read a JSON impact report and run only the affected test classes per pull request.
  3. Test engineers and build maintainers investigating call relationships interactively via `--print-hierarchy`.
- **Value proposition:** A static, bytecode-level call graph (ASM) cross-referenced with a Git diff yields a deterministic, framework-agnostic list of impacted tests — no test execution, no instrumentation, no agent attach. Works directly on compiled `.class` files, JARs, and nested WARs (`WEB-INF/lib/*.jar`).
- **Maturity target:** **MVP** — the tool is feature-complete enough for early adopters per the README, but lacks a hardened release process, formal CI, and the stability/documentation bar implied by "production". Version string is `1.0-SNAPSHOT`. (Defaulted by the subagent since interactive prompts are unavailable inside this skill invocation; revise via `/revise-brief` if "production" is preferred.)
- **In-scope:**
  1. Bytecode scanning of directories, JARs, and WARs (including nested `WEB-INF/lib/*.jar`) into a method-level call graph and field-access index.
  2. Source-line mapping via `LineNumberTable` to correlate methods to source positions.
  3. Git unified-diff parsing with multi-encoding support (UTF-8, UTF-16LE/BE, BOM) and changed-method identification.
  4. Test-method detection for JUnit Jupiter 5 and Spock; backward call-graph walk to mark impacted test methods.
  5. Output: JSON (machine-readable for CI) and console; interactive `--print-hierarchy` explorer.
- **Non-goals:**
  1. Resolving reflection-based calls (`Method.invoke()` and friends).
  2. Runtime polymorphism / virtual dispatch resolution (no CHA/RTA).
  3. Source-level (AST) analysis — bytecode only.
  4. Test execution — the tool only identifies *which* tests to run, never runs them.
  5. Coverage instrumentation or runtime tracing.
- **Success criteria:**
  1. Given a real Git diff against a real Java project (e.g., Spring Framework), the tool produces an accurate impacted-test list with no false negatives for statically-resolvable callers.
  2. JSON output is stable enough to be consumed by a CI step that selects test classes/methods.
  3. Scan + analyze a multi-module project end-to-end in seconds-to-low-minutes (acceptable interactive feedback).
  4. Handles common JAR/WAR packaging without manual classpath wrangling.

## Monetization

- **Commercial intent:** No.
- **Model:** Open-source, non-commercial. No paid tier, no hosted service, no dual-license, no enterprise edition planned.
- **License:** Apache License 2.0 (file: `LICENSE`). Permits commercial use, modification, distribution, patent grant; requires attribution and preservation of the license/notice. The license choice is intentionally permissive so downstream Java tooling vendors and in-house CI systems can adopt without friction.
- **Target market:** Java developers and CI systems looking for free, scriptable tooling for selective test execution. No specific commercial segments are targeted.
- **Tiers:** None — single freely-downloadable artifact.
- **Distribution:** Planned via GitHub Releases (fat JAR `JavaClassCallScanning.jar` attached to tagged releases). Source available on GitHub. No package-manager presence (Maven Central, etc.) at this time.
- **Constraints:**
  1. License headers must be preserved on redistributed source files (Apache-2.0 standard).
  2. No telemetry, no network calls, no data collection from users — the tool runs entirely locally on user-provided artifacts.
  3. No contributor license agreement (CLA) required; inbound = outbound under Apache-2.0 per repository convention unless the maintainer decides otherwise later.

## Technologies

- **Constraints:** Must run on a stock JDK without native dependencies; must operate purely on already-compiled `.class`/JAR/WAR inputs (no source-level analysis); must not require any network access at runtime.
- **Runtimes:** JVM only. Distributed as a self-contained fat JAR runnable with `java -jar` on Java 21+.
- **Languages:** Java 21 (LTS).
- **Frameworks:** None in production. The CLI is plain Java — no Spring, no Micronaut, no Quarkus.
  - Note: `src/test/java/` contains a Spring Boot 3.2.5 fixture used as a realistic integration target to scan. It is a test fixture, not a production dependency, and is excluded from `stack.frameworks`.
- **Pinned versions (production):**
  - Java: 21 (required minimum per README)
  - Gradle: 8.0 (wrapper checked into the repo at `gradle/wrapper/gradle-wrapper.properties`)
  - ASM: 9.8 (bytecode reader / visitor; the analytical core)
  - Gson: 2.11.0 (JSON output serialization)
  - JUnit Jupiter: 5.9.1 (test framework; BOM-managed in `build.gradle`)
  - Shadow plugin: 8.1.1 (fat-JAR packaging)
- **Test-only / fixture dependencies (not production):** Spring Boot 3.2.5, H2 (in-memory DB used solely by the Spring Boot benchmark fixture for realistic scanning targets).
- **Data stores:** None. The tool is stateless across invocations — input is filesystem artifacts, output is stdout (JSON or human-readable).
- **Auth strategy:** None. Single-user CLI executed locally; no identities, no sessions, no secrets.
- **External services:** None at runtime. Git is used only via the user-provided unified-diff text (file or stdin); the tool itself does not invoke `git`.
- **AI / ML dependency:** None.
- **Build tool:** Gradle 8.0 via wrapper (`./gradlew`).
- **Build commands:**
  - Test: `./gradlew test`
  - Lint: not configured (deferred; Spotless / Checkstyle / Error Prone are common Java choices when added later)
  - Format: not configured (deferred; Spotless with Google Java Format is a typical fit)
- **Packaging:** `./gradlew shadowJar` produces `build/libs/JavaClassCallScanning.jar` — the single distributable artifact.

## Architecture

- **Platforms:** CLI on the JVM (Linux/macOS/Windows wherever Java 21+ is installed). No web, mobile, desktop GUI, or browser-extension targets.
- **Service shape:** Monolith. A single Java process packaged as a fat JAR via the Shadow plugin. There is no client/server split, no IPC, no inter-process boundary.
- **Components (internal packages under `src/main/java/`):**
  - `CallGraphBuilder` (root entry point) — parses CLI arguments and drives the pipeline.
  - `model` — domain types: `CallGraph`, `SourceIndex`, `FieldAccessIndex`, `TestIndex`, method/field identifiers.
  - `scanner` — ASM `ClassReader` + `ClassNode`/`MethodNode` visitors that walk `.class` / JAR / WAR / nested-WAR inputs and populate the model.
  - `scanner.test` — test-method detectors for JUnit Jupiter 5 (annotations) and Spock (specification class shape + feature-method conventions).
  - `diff` — unified-diff parser handling UTF-8, UTF-16 LE/BE, and BOM-prefixed inputs; maps changed line ranges to changed methods via `SourceIndex` (`LineNumberTable`).
  - `output` — JSON serialization (Gson) and console renderers, including the interactive `--print-hierarchy` explorer.
- **Communication between components:** In-process Java method calls only. No HTTP, gRPC, queues, or sockets.
- **Async workloads:** None. Single-threaded driver; ASM visits and graph walks may parallelize internally in the future but there are no background workers, schedulers, or jobs.
- **Integrations:**
  - **Inputs (filesystem):** directories of `.class` files; JAR archives; WAR archives including nested `WEB-INF/lib/*.jar`; optional source roots (for line mapping context); a unified Git diff supplied as a file path or piped via stdin.
  - **Outputs (stdout/stderr):** JSON document on stdout, or human-readable console output; interactive prompts in `--print-hierarchy` mode.
  - **No external service calls.** The tool does not invoke `git`, fetch from networks, or contact any registry.
- **Data flow narrative:** `CallGraphBuilder` receives CLI args → `scanner` walks classpath inputs with ASM and populates `CallGraph` + `SourceIndex` + `FieldAccessIndex`; `scanner.test` flags methods belonging to test classes into `TestIndex` → `diff` parses the unified diff, locates changed methods via `SourceIndex` → an impact analyzer walks `CallGraph` backward from changed methods to collect transitive callers and intersects with `TestIndex` to produce the impacted-test set → `output` renders that set as JSON (machine consumers) or console (humans).
- **Trust boundaries:** Untrusted input is bounded to (a) bytecode artifacts the user explicitly points the tool at and (b) a textual unified diff. The tool only reads — no write-back to the analyzed artifacts. ASM parses bytecode in a structurally validating way; the tool exits non-zero on malformed input rather than executing anything. No sensitive data is held; nothing leaves the process beyond stdout/stderr.
- **Multi-tenancy:** N/A. Single-user, single-invocation, local CLI.
- **Path scopes (dev-team write boundaries):**
  - Production code: `src/main/java/**`
  - Test code: `src/test/java/**`
  - API boundary: none (no controller/API layer — `paths.api_boundary` left null).

## Quality & Standards

- **Style guide:** Java language defaults; no formal company or open-source style guide adopted. Match the conventions already established in `src/main/java/` (package layout, naming, brace style).
- **Linters and formatters:** None configured today. The user explicitly deferred this decision. Reasonable future additions when the project warrants them: Spotless (formatter, with Google Java Format or Palantir Java Format), Checkstyle or Error Prone (lint). The brief will be updated via `/revise-brief` if/when these are introduced.
- **Testing:**
  - Framework: **JUnit Jupiter 5** (`5.9.1`, BOM-managed in `build.gradle`).
  - Levels: **unit** (focused tests on individual scanners, diff parsing, indexing) and **integration** (the `benchmark` package — a real Spring Boot fixture compiled and scanned end-to-end to verify call-graph and impacted-test behavior on a realistic codebase).
  - No end-to-end level (the CLI itself is the integration surface; no separate E2E harness).
  - Coverage target: **none** — new logic must come with tests, but there is no enforced percentage.
  - Run: `./gradlew test`.
- **Security baseline:**
  - No network access at runtime → no SAST/DAST exposure beyond local file handling.
  - No secrets handled; no secret management required.
  - Dependency scanning: not currently configured. Reasonable future additions: Dependabot (GitHub-native, free, easy to enable on a public repo) for ASM/Gson/JUnit/Shadow plugin updates; GitHub's built-in vulnerability alerts.
  - No threat-model document is part of the scaffold today; the attack surface (local CLI processing user-supplied artifacts) is narrow enough that one is not warranted at MVP maturity.
- **Accessibility target:** N/A — no UI surface. Console output should remain readable in a default terminal (no ANSI-color reliance for meaning; colors, if any, are advisory).
- **Performance budgets:** No hard numbers committed. Soft expectation per success criteria: scanning a multi-module Java project (e.g., Spring Framework-scale) should complete in seconds-to-low-minutes on a developer laptop.
- **Documentation:** README-only at the project root. No ADR practice and no docs site planned at MVP. README is authoritative for usage; `PROJECT_BRIEF.md` is authoritative for project shape and dev-team operation.
- **Observability:** stdout/stderr logging only — the CLI emits human-readable progress and errors. No metrics, no tracing, no log aggregation (inappropriate for a one-shot local CLI).

## Profiles

**None.**

No profile skills are active for this project. The available profiles in this workspace do not fit a Java bytecode-analysis CLI:

- `profile-java-server-architecture` — opinionated for Spring Boot web apps with a Controller → Facade → Service → Repository call chain. This project is a CLI with no controller/API/repository layers.
- `profile-java-database-access` — mandates DTO projections, bulk queries, Hibernate as JPA implementation. This project has no production data store, no JPA, no SQL queries.
- `profile-aws-deployment` — AWS-first deployment guidance with cost tables. This project distributes as a downloadable JAR via GitHub Releases; no cloud deployment.

If a future use case introduces a server component, a database, or a cloud deployment target, the corresponding profile can be enabled via `/revise-brief` and added to the `profiles` frontmatter list.

## Deployment

### Production

- **Hosting target / distribution channel:** **GitHub Releases.** The tool is not a hosted service — it is a downloadable executable JAR. The release artifact is `JavaClassCallScanning.jar` (a fat JAR produced by the Shadow plugin) attached to a tagged GitHub Release.
- **Cloud provider:** None. No servers, no managed containers, no serverless functions.
- **Infrastructure as Code:** None. There is no infrastructure to provision. The "release process" is a build + an artifact upload.
- **CI/CD:** **Not configured today.** No `.github/workflows/` directory exists. A planned addition is a GitHub Actions workflow that, on tag push:
  1. Sets up Java 21.
  2. Runs `./gradlew test`.
  3. Runs `./gradlew shadowJar`.
  4. Attaches `build/libs/JavaClassCallScanning.jar` to the corresponding GitHub Release.
  Until that workflow is added, releases are built and uploaded manually by the maintainer.
- **Environments:**
  - `development` — local developer machines (Java 21 + Gradle wrapper).
  - `release` — tagged GitHub Releases containing the fat JAR.
  - No staging environment exists or is meaningful (no service to stage).
- **Secrets management:** None required. No API keys, no service accounts, no environment-scoped credentials. The eventual release workflow will need only the GitHub-provided `GITHUB_TOKEN` to attach release assets — no third-party secrets.
- **Observability in production:** N/A — the tool runs on end users' machines, not infrastructure operated by the project. Each invocation writes its own stdout/stderr; nothing is aggregated centrally.
- **Backup / disaster recovery:** N/A. The source of truth is the Git repository on GitHub; release artifacts are reproducible from any tagged commit via `./gradlew shadowJar`.

### Development

- **Local dev environment:** Native toolchain. Requirements:
  - JDK 21+ (any LTS-aligned distribution: Temurin, Zulu, Liberica, Oracle, etc.).
  - The Gradle wrapper (`./gradlew`) is checked into the repo at version 8.0 — no separate Gradle install needed.
- **Containerization:** Not used. No Dockerfile, no docker-compose. The CLI is a single JAR; containerization would add operational overhead without benefit.
- **Hot reload / fast feedback:** Not applicable to a one-shot CLI. Inner loop is `./gradlew test` for unit/integration tests and `./gradlew run` (or `java -jar`) for ad-hoc executions.
- **Seed data strategy:** None. The "test data" is the `src/test/java/` Spring Boot benchmark fixture which gets compiled and then scanned by the tool under test — there is no database to seed.
- **Database migrations:** N/A — no database.

## Use Cases

Use cases are captured individually under `use-cases/` and indexed in `USE_CASES.md`.

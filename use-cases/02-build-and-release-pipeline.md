# Use Case 02: Build & release pipeline via GitHub Actions

## Summary
Introduce two GitHub Actions workflows that automate versioning and release of the fat-JAR distribution, closing the CI/CD gap that `PROJECT_BRIEF.md` § Deployment currently calls out. The first workflow is manually triggered (`workflow_dispatch`) and accepts an optional `version` input: when blank, the workflow reads the current `version = '...'` in `build.gradle` and computes the next **minor** SemVer value (incrementing minor, resetting patch to 0, leaving major untouched); when provided, the input value is used verbatim (after a SemVer-shape validation). The workflow rewrites `build.gradle`, commits the change directly to `main`, and pushes an annotated tag `v<x>.<y>.<z>` at that commit. The project starts at `v0.1.0`. The second workflow runs on tag push (`v*.*.*`); it sets up Java 21, runs `./gradlew test`, and on success builds the fat JAR via `./gradlew shadowJar`. It then creates a GitHub Release for the triggering tag, marked **prerelease** (since the project remains in `0.x`), attaching exactly two assets — `java-class-call-scanning.jar` (the fat JAR, no version suffix, no `-all`) and `USAGE.md` (the in-repo usage doc, uploaded verbatim) — and a changelog body listing one one-sentence bullet per pull request merged into `main` since the previous tag, derived from the PR title. A new `USAGE.md` file lives permanently at the repo root and is the source of truth for the usage doc: a brief "what this is" section followed by a "how to use" section that covers both CLI and MCP invocation, with no architectural information. Squash-merge is the assumed default merge style. The project explicitly does not support older versions, so release branches and patch-line hotfixes are out of scope.

## Acceptance Criteria
1. `build.gradle` declares the project version as a single `version = '<x>.<y>.<z>'` line (replacing the current `1.0-SNAPSHOT`); this is the canonical source the bump workflow reads and rewrites.
2. A workflow at `.github/workflows/bump-and-tag.yml` is triggerable only from the GitHub Actions UI via `workflow_dispatch` (not on push, not on schedule).
3. The bump workflow exposes an optional `version` input (free-text). When the input is **blank**, the workflow computes the next version by reading `build.gradle`, incrementing the **minor** component by 1, setting **patch** to 0, and leaving **major** unchanged. When the input is **provided**, the workflow uses it as the target version verbatim.
4. A supplied `version` input is validated to match the SemVer shape `<major>.<minor>.<patch>` (numeric components, no prerelease suffixes); a malformed value fails the workflow before any commit is made.
5. The first run against the unbumped repo, with no input, produces version `0.1.0` and tag `v0.1.0`.
6. The bump workflow writes the resolved version back to `build.gradle`, commits the change directly to `main` (no PR) with a deterministic message (e.g., `chore: release v<x>.<y>.<z>`), and pushes an annotated Git tag named `v<x>.<y>.<z>` pointing at that commit.
7. If the resolved tag already exists on the remote, the bump workflow fails the run rather than overwriting; concurrent runs are serialized via a `concurrency:` group on the workflow.
8. A workflow at `.github/workflows/release.yml` is triggered automatically when a tag matching `v*.*.*` is pushed.
9. The release workflow sets up Java 21, runs `./gradlew test`, and fails the run if any test fails — no release is created on test failure.
10. On test success, the release workflow runs `./gradlew shadowJar` and uploads exactly two release assets: `java-class-call-scanning.jar` (the fat JAR, no version suffix, no `-all`) and `USAGE.md` (the in-repo file uploaded verbatim). No other JARs (plain, sources, javadoc) and no other files are attached.
11. The release workflow creates a GitHub Release for the triggering tag with `prerelease: true` (because the project lives in `0.x`) and attaches exactly the two assets listed in AC 10. GitHub's auto-attached source archives for the tag are accepted as a platform-level artifact and out of scope.
12. The release body is a changelog: one bullet per pull request merged into `main` between the previous tag and the new tag, each bullet a one-sentence summary derived from the PR title. On the very first release (no prior tag) the range covers the full history.
13. The PR-listing logic correctly handles squash-merged PRs (the default merge style), so each squash-merged PR yields exactly one bullet.
14. `./gradlew shadowJar` continues to work on a developer machine and produces a runnable fat JAR; the rename to `java-class-call-scanning.jar` is applied at packaging or upload time and does not break the local inner loop.
15. A `USAGE.md` file exists at the repo root, committed to git, and is shipped to end users both via the repo and as a release asset.
16. `USAGE.md` opens with the H2 heading `## What this is` (exact text) — a brief description of the tool's purpose and primary use cases. Architecture, internal design, component diagrams, and design rationale are explicitly out of scope for this section.
17. The next section of `USAGE.md` is the H2 heading `## How to use` (exact text), which documents both invocation modes: running the fat JAR as a CLI (`java -jar java-class-call-scanning.jar …`) and running it as an MCP server. Architecture is explicitly out of scope here too.
18. The release workflow does not regenerate, template, or version-stamp `USAGE.md` — it uploads the repo's file verbatim as it exists at the tagged commit.

## Original Description
I want to create the build and release pipelines.

Create a github action that when triggered:
 - Increases minor version by 1 (we are following semver, major.minor.patch, but we'll only be updating minors until the release; starting by 0.1)
 - Creates a new tag

On creating a new tag, we must execute a github action or action set that:
 - Runs all the tests
 - Creates a new release by building the application and packaging it as a fat jar
 - Final artifact must only be java-class-call-scanner.jar (fat jar, without version in the name or any other artifact as outcome) and a changelog with a very small summary, one sentence each, of all the tickets involved in it.

## Clarifications
- Q: How should the bump-and-tag workflow be triggered?
  A: Manual (`workflow_dispatch`) only — not on push, not on schedule.
- Q: What is the canonical source of the version string the bump workflow rewrites?
  A: `build.gradle` (the `version = '...'` line); replaces the current `1.0-SNAPSHOT`.
- Q: What should the changelog "tickets" be sourced from?
  A: Pull requests merged into `main` since the previous tag; one bullet per PR, summary derived from the PR title.
- Q: Confirm the release artifact name. The user originally wrote `java-class-call-scanner.jar` ("scanner"), but the project slug is `java-class-call-scanning`.
  A: Use `java-class-call-scanning.jar` to match the project slug.
- Q: Should the bump commit go directly to `main`, or via a PR? And should the bump be parameterized?
  A: Direct push to `main`. The workflow exposes an optional `version` input that defaults to "next minor" when blank, and is used verbatim when provided (to allow targeting a specific version when needed).
- Q: Should `0.x` releases be marked as GitHub prereleases?
  A: Yes — `prerelease: true` while the project lives in `0.x`.
- Q: Is a PR-title scheme (e.g., Conventional Commits) required?
  A: No — current free-form PR titles are fine; the changelog is just the list of titles.
- Q: What is the default merge style on `main`?
  A: Squash merge.
- Q: How should the release body behave when no PRs were merged in the range?
  A: Treated as unlikely — no special-case handling.
- Q: Does `./gradlew test` cover the integration level (Spring Boot benchmark fixture)?
  A: Use the current `./gradlew test` task as-is; do not introduce a separate integration task for this use case.
- Q: How is a "hotfix of an older line" handled (override input lower than current version)?
  A: Will not happen — the project explicitly does not support older versions. Release branches and patch-line hotfixes are out of scope.
- Q: Is branch protection on `main` an obstacle for direct push from the bump workflow?
  A: No — branch protection is not active on this repository, so `GITHUB_TOKEN` can push to `main` directly.
- Q: Should the release ship a separate usage document alongside the JAR?
  A: Yes — add a `USAGE.md` file at the repo root with exactly two H2 sections: `## What this is` (brief purpose) and `## How to use` (CLI and MCP invocation). No architectural information in either section. The file is committed to the repo and uploaded verbatim as a second release asset on every tagged release.
- Q: How is the per-release changelog exposed — as a file asset or as the GitHub Release body?
  A: As the GitHub Release body (markdown rendered on the release page; surfaced via the GitHub Releases API and the Releases feed). It is not uploaded as a separate file asset, and a cumulative repo-level `CHANGELOG.md` is not maintained.

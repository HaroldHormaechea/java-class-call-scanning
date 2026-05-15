# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Java bytecode analysis tool that scans compiled Java classes and detects method invocations using the ASM (ObjectWeb) library. It analyzes bytecode instructions to build a call graph showing which methods invoke which other methods.

## Build Commands

```bash
# Build the project (compiles classes to build/classes/java/main/)
./gradlew build

# Run tests
./gradlew test

# Clean and rebuild
./gradlew clean build
```

## Running the Scanner

The Scanner requires compiled .class files in the build directory. After building:

```bash
./gradlew run
# Or run Scanner.main() directly from IDE
```

## Architecture

### Core Components

- **Scanner.java** (`com.hgg.main`) - Entry point that iterates through compiled .class files, uses ASM's ClassReader/ClassNode/MethodNode to parse bytecode, and extracts MethodInsnNode instructions representing method calls
- **Invocation.java** (`com.hgg.main`) - Data model representing a method invocation with source class/method and target class/method

### ASM Library Usage

The project uses ASM 9.8 tree API:
- `ClassReader` - Reads raw bytecode from .class files
- `ClassNode` - Tree representation of a class structure
- `MethodNode` - Contains method bytecode instructions
- `MethodInsnNode` - Represents INVOKE* bytecode instructions (method calls)

### Test Targets

`com.hgg.main.targets` contains sample classes (TargetClass1/2/3) that form a call chain for testing the scanner.

## Known Limitations

- Scanner.java uses a hardcoded absolute Windows path to locate .class files - needs parameterization for portability
- Test framework is configured but no tests are implemented yet

## Dev-team write authorizations

The dev-team's path scope is defined by `paths.production` and `paths.test` in `PROJECT_BRIEF.md` frontmatter (`src/main/java/**` and `src/test/java/**`). The following additional paths are authorized for dev-team writes, because they are project-level infrastructure / docs / build config that does not fit those globs but is necessary for normal project work:

- `build.gradle`, `settings.gradle` — build configuration (developer scope).
- `gradle/wrapper/**`, `gradlew`, `gradlew.bat` — Gradle wrapper (developer scope; typically updated via `./gradlew wrapper --gradle-version <x>` rather than hand-edits).
- `.github/workflows/**` — GitHub Actions workflow YAML (developer scope).
- `README.md`, `USAGE.md`, `CHANGELOG.md` — root-level documentation (developer scope).
- `.gitignore` — repository-level VCS config (developer scope).
- `src/main/resources/**` — production resources packaged into the JAR alongside compiled classes (developer scope; semantically production code, but `PROJECT_BRIEF.md` frontmatter `paths.production` currently lists only `src/main/java/**` — a future `/revise-brief` should fold this in).

QA may read all of the above but writes only to `paths.test`. Authorization for any new repository-level file outside these patterns must be added here before the dev-team modifies it.

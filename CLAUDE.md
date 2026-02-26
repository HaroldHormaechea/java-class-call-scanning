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

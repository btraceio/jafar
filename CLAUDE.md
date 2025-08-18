# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Jafar is an experimental, fast JFR (Java Flight Recording) parser with a small, focused API. It provides both typed and untyped APIs for parsing JFR files and extracting event data with minimal ceremony.

### Architecture

The project is organized as a multi-module Gradle build with the following structure:

- **parser/**: Core parsing engine with typed and untyped APIs
- **demo/**: Demonstration application comparing different JFR parsers  
- **tools/**: Utilities including JFR file scrubbing functionality
- **jafar-gradle-plugin/**: Gradle plugin for generating Jafar type interfaces

Key architectural components:
- `JafarParser`: Main entry point supporting both typed and untyped parsing
- `TypedJafarParser`: Strongly-typed API using annotated interfaces (@JfrType, @JfrField)
- `UntypedJafarParser`: Map-based lightweight parsing API
- `ParsingContext`: Reusable context for sharing expensive resources across sessions

## Build Commands

### Prerequisites
- Java 21+
- Git LFS (for test recordings): `git lfs pull`

### Essential Commands
```bash
# Fetch binary test resources (required before first build)
./get_resources.sh

# Build all modules
./gradlew build

# Build shadow JARs for all modules
./gradlew shadowJar

# Run tests
./gradlew test

# Run tests with verbose output
./gradlew test --info

# Run a specific test class
./gradlew :parser:test --tests "io.jafar.parser.TypedJafarParserTest"

# Run demo application
java -jar demo/build/libs/demo-all.jar [jafar|jmc|jfr|jfr-stream] /path/to/recording.jfr

# Run JFR Shell (Interactive JFR Analysis)
./gradlew :jfr-shell:run --console=plain

# Rebuild the gradle plugin
./rebuild_plugin.sh

# Code formatting (Spotless)
./gradlew spotlessApply

# Check formatting
./gradlew spotlessCheck

# Publish to local Maven repository
./gradlew publishToMavenLocal
```

### Module-specific Commands
```bash
# Build only the parser module
./gradlew :parser:build

# Build only the demo
./gradlew :demo:build

# Run the demo application directly
./gradlew :demo:run --args="jafar /path/to/recording.jfr"
```

## Development Notes

### Parser APIs
- **Typed API**: Uses interface definitions with `@JfrType("event.name")` annotations
- **Untyped API**: Returns events as `Map<String, Object>` with wrapper types for arrays/complex values
- Both APIs support handler registration and synchronous event processing

### Key Classes to Understand
- `JafarParser`: Factory methods for creating typed/untyped parsers
- `TypedJafarParserImpl`/`UntypedJafarParserImpl`: Core implementation classes
- `ParsingContext`: Manages shared resources and metadata across parsing sessions
- `ChunkParserListener`: Low-level parsing lifecycle hooks
- `Values`: Utility class for extracting values from untyped event maps

### Testing Strategy
- JFR test files stored in `src/test/resources/` 
- Fuzz testing and stability tests included
- Tests use JUnit 5 with large heap allocation (8GB max, 1GB min)
- Mock recordings created using JMC FlightRecorder writer

### Gradle Plugin
The `generateJafarTypes` task generates typed interfaces from JFR metadata:
- Can use runtime JVM metadata or existing JFR files as input
- Supports filtering by event type names
- Configurable output package and directory

### JFR Shell (Interactive Analysis Tool)
The jfr-shell module provides a Groovy-based interactive environment for JFR analysis:
- **Session-based**: Open JFR files and maintain analysis state
- **Typed API Integration**: Uses generated interfaces for type-safe event handling
- **Groovy Scripting**: Full Groovy language support for complex analytics
- **Built-in Commands**: `help`, `info`, `types`, `open()`, `handle()`, `run()`, `export()`
- **Script Examples**: Pre-built templates in `jfr-shell/src/main/resources/examples/`

#### JFR Shell Usage:
```bash
# Start interactive shell
./gradlew :jfr-shell:run --console=plain

# Example session:
jfr> open("/path/to/recording.jfr")
jfr> def threadStats = [:]
jfr> handle(JFRExecutionSample) { event, ctl ->
       def threadId = event.sampledThread().javaThreadId()
       threadStats[threadId] = (threadStats[threadId] ?: 0) + 1
     }
jfr> run()
jfr> threadStats.sort { -it.value }.take(5)
jfr> export(threadStats, "results.json")
```
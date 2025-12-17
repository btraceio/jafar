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

## Release Process

The project uses a fully automated release workflow. See [RELEASING.md](RELEASING.md) for complete details.

### Quick Release Steps

1. **Update versions** in `build.gradle` and `jafar-gradle-plugin/build.gradle` (remove `-SNAPSHOT`)
2. **Update CHANGELOG.md** with release notes for the new version
3. **Commit and push** changes to main branch
4. **Create and push tag**:
   ```bash
   git tag -a v0.4.0 -m "Release v0.4.0"
   git push origin v0.4.0
   ```

### What Happens Automatically

The release workflow (`.github/workflows/release.yml`) automatically:
- ✅ Publishes `jafar-parser` and `jafar-tools` to Maven Central (Sonatype)
- ✅ Publishes `jafar-gradle-plugin` to Maven Central (Sonatype)
- ✅ Publishes `jfr-shell` to GitHub Packages
- ✅ Triggers JitPack build and waits for completion
- ✅ Updates [btraceio/jbang-catalog](https://github.com/btraceio/jbang-catalog) with new version
- ✅ Creates GitHub Release with changelog notes

### Version Management

- **Root version**: Defined in `build.gradle` as `project.version="X.Y.Z"`
- **Subprojects**: Use `rootProject.version` (automatic sync)
- **Gradle plugin**: Has separate version in `jafar-gradle-plugin/build.gradle`
- **Development versions**: Use `-SNAPSHOT` suffix (e.g., `0.4.0-SNAPSHOT`)

### Post-Release

After release completes, prepare for next development iteration:

```bash
# Update to next SNAPSHOT version
# Edit build.gradle: project.version="0.5.0-SNAPSHOT"
# Edit jafar-gradle-plugin/build.gradle: version = "0.5.0-SNAPSHOT"
# Update CHANGELOG.md with [Unreleased] section

git add build.gradle jafar-gradle-plugin/build.gradle CHANGELOG.md
git commit -m "Prepare for next development iteration"
git push origin main
```

### Testing Releases

```bash
# Verify JBang distribution (available immediately)
jbang --fresh jfr-shell@btraceio --version

# Verify Maven Central (takes ~2 hours to sync)
# Check: https://central.sonatype.com/artifact/io.btrace/jafar-parser/X.Y.Z
```

### Manual Release (Emergency Only)

If automated workflow fails:
```bash
# Publish to Sonatype
SONATYPE_USERNAME=xxx SONATYPE_PASSWORD=xxx ./gradlew publish -x :jfr-shell:publish

# Publish jfr-shell to GitHub Packages
GITHUB_ACTOR=xxx GITHUB_TOKEN=xxx ./gradlew :jfr-shell:publishMavenPublicationToGitHubPackagesRepository
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

### Composite Build Configuration

The project uses Gradle composite builds to ensure the demo project and other consumers always use the latest local source code during development.

**Why this is needed:**
- The `jafar-gradle-plugin` depends on `jafar-parser`
- Without composite builds, the plugin would resolve `jafar-parser` from Maven repositories (which may be stale)
- Composite builds ensure the plugin uses the current local parser source code

**Root project (`settings.gradle`):**
```gradle
// Let builds resolve the in-repo Gradle plugin by ID without publishing
pluginManagement {
    includeBuild('jafar-gradle-plugin')
}

// Wire the plugin build to use the in-repo parser project instead of a published module
includeBuild('jafar-gradle-plugin') {
    dependencySubstitution {
        substitute(module("io.btrace:jafar-parser")).using(project(":parser"))
    }
}
```

**Demo project (`demo/settings.gradle`):**
```gradle
// Include the plugin for use
pluginManagement {
    includeBuild('../jafar-gradle-plugin')
}

// Include parent build to get access to parser module
includeBuild('..') {
    dependencySubstitution {
        substitute(module("io.btrace:jafar-parser")).using(project(":parser"))
    }
}
```

**Important notes:**
- When modifying parser code, the changes are immediately available to the plugin (no `publishToMavenLocal` needed)
- If you encounter `StackOverflowError` in `TypeGenerator`, ensure both `/parser/src/main/java/io/jafar/utils/TypeGenerator.java` and `/parser/src/java21/java/io/jafar/utils/TypeGenerator.java` are updated
- After changing settings.gradle, run `./gradlew --stop` and `rm -rf demo/.gradle/` to clear caches

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
- WHen fixing an issue, always check the alternative implementation for other Java versions
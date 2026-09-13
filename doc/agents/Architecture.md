# Architecture and conventions

The parser APIs, coding style, testing strategy, and the composite build.
The module map is in [AGENTS.md](../../AGENTS.md#module-map).

## Coding Style & Naming Conventions
- Language: Java 25 (shell/MCP modules), Java 8 bytecode (parser/tools/demo), Groovy (plugin). Indent 4 spaces, no tabs; aim for 120 col width.
- Packages: `io.jafar.*`. Classes `PascalCase`, methods/fields `camelCase`, constants `UPPER_SNAKE_CASE`.
- Keep public API minimal; prefer package-private for internals. Use meaningful names and final where sensible.

## Pre-commit Formatting
- Spotless enforces formatting for Java, Groovy, and Gradle files.
- Git hook: `.githooks/pre-commit` runs `./gradlew spotlessApply` and restages changes.
- If hooks don't run, set `git config core.hooksPath .githooks` once.

## Parser APIs
- **Typed API**: Uses interface definitions with `@JfrType("event.name")` annotations
- **Untyped API**: Returns events as `Map<String, Object>` with wrapper types for arrays/complex values
- Both APIs support handler registration and synchronous event processing

## Key Classes to Understand
- `JafarParser`: Factory methods for creating typed/untyped parsers
- `TypedJafarParserImpl`/`UntypedJafarParserImpl`: Core implementation classes
- `ParsingContext`: Manages shared resources and metadata across parsing sessions
- `ChunkParserListener`: Low-level parsing lifecycle hooks
- `Values`: Utility class for extracting values from untyped event maps

## Testing Strategy
- Frameworks: JUnit Jupiter 5, Mockito. Place tests under `src/test/java` mirroring package paths.
- Name tests `*Test.java`; parameterized tests encouraged for edge cases; see existing fuzz/stability tests in `parser-core` and `parser-codegen`.
- JFR test files stored in `src/test/resources/`
- Tests use JUnit 5 with large heap allocation (8GB max, 1GB min)
- Mock recordings created using JMC FlightRecorder writer

## Gradle Plugin
The `generateJafarTypes` task generates typed interfaces from JFR metadata:
- Can use runtime JVM metadata or existing JFR files as input
- Supports filtering by event type names
- Configurable output package and directory

## Composite Build Configuration

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
        substitute(module("io.btrace:jafar-parser-core")).using(project(":parser-core"))
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
        substitute(module("io.btrace:jafar-parser-core")).using(project(":parser-core"))
    }
}
```

**Important notes:**
- When modifying parser code, the changes are immediately available to the plugin (no `publishToMavenLocal` needed)
- If you encounter `StackOverflowError` in `TypeGenerator`, ensure both `/parser-core/src/main/java/io/jafar/utils/TypeGenerator.java` and `/parser-core/src/java21/java/io/jafar/utils/TypeGenerator.java` are updated
- After changing settings.gradle, run `./gradlew --stop` and `rm -rf demo/.gradle/` to clear caches

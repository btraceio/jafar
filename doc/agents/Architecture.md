# Architecture and conventions

The parser APIs, coding style, testing strategy, and the composite build.
The module map is in [AGENTS.md](../../AGENTS.md#module-map).

## Coding Style & Naming Conventions
- Language: Java 25 (shell/MCP modules), Java 8 bytecode (parser/tools/demo), Groovy (plugin). Indent 4 spaces, no tabs; aim for 120 col width.
- Packages: `io.jafar.*`. Classes `PascalCase`, methods/fields `camelCase`, constants `UPPER_SNAKE_CASE`.
- Keep public API minimal; prefer package-private for internals. Use meaningful names and final where sensible.

## Formatting
- Spotless enforces formatting for Java, Groovy, and Gradle files.
- Every `build` runs `spotlessCheck` (the root build wires it into every module's
  `check`); run `./gradlew spotlessApply` before committing to keep builds green.
- The repository ships no git hooks; do not set `core.hooksPath` for it — that would
  replace your own global hooks.

## Parser APIs
- **Typed API**: Uses interface definitions with `@JfrType("event.name")` annotations
- **Untyped API**: Returns events as `Map<String, Object>` with wrapper types for arrays/complex values
- Two Java implementations produce untyped values. The generic `EventStream` (`GenericValueReader` +
  `MapValueBuilder`) is in `parser-core`. `EventStreamGenerated` in `parser-codegen` generates a
  deserializer per event type with `UntypedCodeGenerator`, shaped by the `UntypedStrategy`.
  `UntypedJafarParserImpl` picks between them through the `UntypedEventStreamFactory` SPI
  (ServiceLoader, like `DeserializerFactory`): every strategy reads with the generated reader when
  `parser-codegen` is on the classpath, and with the generic one when it is not or when
  `-Djafar.untyped.generated=false` is set. `UntypedJafarParserImpl.usesGeneratedReader()` reports
  which, and `UntypedStrategyDispatchTest` observes it through the shared deserializer cache. They
  must yield the same values: the contract is in
  `UntypedCodeGenerator`'s Javadoc and `GeneratedUntypedParityTest` compares every event of
  `tck-test.jfr`. Generated code calls the generic path's own helpers through
  `GeneratedDeserializerSupport` rather than re-implementing them. Generated deserializers are
  shared across chunks only by structural key, never by class id — see
  [DataShapes.md](DataShapes.md#a-type-id-is-not-a-type).
- Both APIs support handler registration and synchronous event processing

## Key Classes to Understand
- `JafarParser`: Factory methods for creating typed/untyped parsers
- `TypedJafarParserImpl`/`UntypedJafarParserImpl`: Core implementation classes
- `ParsingContext`: Manages shared resources and metadata across parsing sessions
- `ChunkParserListener`: Low-level parsing lifecycle hooks. **Callbacks for different chunks run
  concurrently**: `StreamingChunkParser` submits every chunk to a pool of `max(nproc - 2, 1)`
  threads, and only the callbacks of one chunk run in sequence. A listener that collects into one
  `ArrayList`, `HashMap`, counter or single "current chunk" slot loses data, throws, or mixes
  chunks — and on a machine with three or fewer processors (one thread) it looks correct. Keep
  state per chunk, keyed by `ParserContext.getChunkIndex()` or kept in the chunk's own
  `ParserContext`, and merge in chunk order after `parse` returns. In this repository:
  `JafarSources.PerChunk` (metadata and chunk sources), `JafarSources.ChunkState` (`crossref`) and
  `JFRSession`'s per-chunk scan. The nets are `MultiChunkDeterminismTest`,
  `ConstantPoolChunkLocalityTest` and `JFRSessionDeterminismTest` in `jfr-shell`/`shell-core`. The
  Go parser is sequential, so this has no Go counterpart.
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

# Architecture and conventions

The parser APIs, coding style, testing strategy, and the composite build.
The module map is in [AGENTS.md](../../AGENTS.md#module-map).

## Module boundaries are enforced

Module boundaries are enforced, not just recommended: the `arch-tests` module
imports the compiled classes of every covered module and runs frozen ArchUnit
rules over them — nothing outside the parser plane (`parser-core`,
`parser-codegen`) may depend on `io.jafar.parser.impl`/`internal_api`, nothing
outside `hdump-parser` may depend on its `impl`/`internal`/`index` packages,
`api` packages never depend on their module's implementation packages, parser
modules and `shell-core` never depend on the consumer modules, and parser
implementation code declares no mutable static fields. The rules run in every
`./gradlew build` (the `tests` CI job), and a new violation fails the build —
there is no escape statement; today's violations are grandfathered in a
committed freeze store. The commands, the store's regeneration rules and the
documented gaps are in [Gates.md](Gates.md).

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

## Architectural invariants

The patterns the whole repository is built on. The `arch-rules` module asserts them over compiled
classes (ArchUnit, run by every `build` and by `./gradlew :arch-rules:test`); break one and the gate
fails. Run them locally in your dev loop — CI is the final gate, not the first place you learn.

### A1. `api` packages do not leak implementation types

A module's `api` package (`io.jafar.<module>.api`) is its public surface. Types from `impl`,
`internal`, `internal_api` and `index` may be *constructed inside method bodies* — the
factory-in-api pattern: `HeapDumpParser.parse` builds a `HeapDumpImpl` behind the `HeapDump`
interface — but must never appear in public or protected signatures (parameters, returns, throws,
fields, supertypes; generic arguments included). Enforced by `ApiSurfacePurityTest`.

### A2. Internal packages are contracts, not walls — but the contract lists its members

`io.jafar.parser.internal_api` is deliberately shared by parser-core/parser-codegen, shell-core,
the Jafar shell backend, tools, jfr2pprof — and (unchecked here) the separate demo build. A
module's other `impl`/`internal`/`index` packages are nobody else's business. Do not "fix" an
internal_api import as a layering violation: it is the shared internal contract, and *extending
its consumer list is an architecture decision*, not a convenience import. Enforced by
`InternalApiContractTest`.

### A3. Extension goes through ServiceLoader-discovered SPIs, never hard-wiring

`JfrBackend`, `ShellModule`, `LlmBackend`, `HandlerFactory`, `DeserializerFactory`,
`UntypedEventStreamFactory` are the SPI surface. Backends and modules are *discovered* — the SPI
packages must not depend on their implementations, and an integration that special-cases a
concrete backend is wrong: it defeats the plugin architecture the shells are built on. Enforced by
`SpiRulesTest` (the interfaces stay interfaces; SPI packages stay impl-free).

### A4. SPI interfaces are published contracts

`shell-core`'s public API is guarded by `japicmp` against the last released baseline (see its
`build.gradle`): third-party backend plugins compile against it. Changing an SPI is a release
decision — evolve compatibly or deprecate first. The jfr-shell-tck is the behavioural net for
backends.

### A5. parser-core's `api` is the documented exception

parser-core predates the strict layering: its `api` shares the `internal_api` metadata model on
purpose (`JafarRecordedEvent.type()` returns `MetadataClass`; `ParserContext` is a documented
hybrid — "used internally and exposed via events"). For parser-core, A1 forbids only `impl` types
in signatures; `internal_api` is allowed there. This is the encoded reality, not an aspiration:
new modules (hdump, pprof, otlp) follow A1 in full.

### Clean code

Mechanically enforced on main sources by PMD (`./gradlew pmdMain`, part of `check`; ruleset:
`config/pmd/ruleset.xml` — curated, must stay green): no empty catch blocks, no empty control
statements, no constructors calling overridable methods, no dead locals/fields/methods. When a
member is reflection-driven or updater-driven (invisible to the analysis), suppress at the member
with `@SuppressWarnings("PMD.<Rule>")` and a comment saying why — see `JafarMcpServer` and
`MetadataClass.typeSkipper` for the two living examples. Everything the ruleset flags that is not
suppressed is genuinely dead: delete it, keep the side effects (an "unused" local in a parser is
often a stream-advancing read — drop the variable, keep the call).

Beyond the gates, the conventions the codebase already follows:

- Keep public API minimal; package-private is the default, `public` earns its place.
- Reach for the pattern the area already uses: Factory (api entry points), Strategy/Adapter
  (shell output formats, providers), Command (command dispatch), sealed exhaustive hierarchies
  for value types (`Value`, `BoolExpr`, `ValueExpr`), SPI for anything pluggable. The full
  pattern inventory lives in `doc/cli/Architecture.md` (Design Patterns).
- Prefer sealed types + exhaustive switches: the compiler catches the missed case.
- Fail loud over fail quiet — except where a comment documents a deliberate best-effort catch.

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
- `ParsingContext`: Manages shared resources and metadata across parsing sessions.
  `withParallelism(n)` returns a view sharing the caches whose parsers build their
  `StreamingChunkParser` with `n` chunk threads; `withParallelism(1)` is the sequential,
  file-ordered mode. The pool size is fixed when the `StreamingChunkParser` is constructed (typed:
  in the parser's constructor; untyped: per `run()`), which is why it is a context property and
  not a setter on the parser
- `withParallelism` reaches only code that asks the context: the two parsers, and `JFRSession`'s
  scans through `ParsingContext.parallelism()`. Code that builds a `StreamingChunkParser` with no
  context in hand still uses the default pool: `JafarSources` (the backend SPI's metadata, chunk
  and constant-pool sources are created without a context), `Scrubber` and `TypeGenerator`
- Handlers live in `impl.HandlerRegistry`, one class for both parsers (untyped: one registry;
  typed: one per event class in a `ConcurrentHashMap`). It is a copy-on-write array with a
  registration count per handler: writers replace the array under the registry's lock, the
  dispatch loop reads it once per event with `snapshot()`. Do not go back to iterating a mutable
  collection there - a handler that deregisters during `run()` then throws
  `ConcurrentModificationException` out of `run()` (regression tests:
  `UntypedHandlerDeregistrationTest`, `TypedJafarParserLifecycleTest`), and the per-event iterator
  costs 32 bytes per event on JDK 21 (measured; see `gradle/allocation-budgets.properties`)
- A parser and its `withParserListener` copies share the registry (typed: also the type and
  factory maps) and an owner count; the shared state is cleared when the last owner closes. The
  typed copies also share one `StreamingChunkParser`, which is **not** owner-counted: closing any
  of them shuts its pool (counting it would leak the pool in the common
  `p.withParserListener(l).run()` pattern, where the copy is never closed)
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

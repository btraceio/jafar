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

The boundary rules live in the `arch-tests` module as frozen ArchUnit rules and are catalogued in
[Gates.md](Gates.md#architecture-boundaries-archunit-freeze): the (a)–(i) plain-words list, the
committed freeze store and the fire tests that prove each rule fires. The load-bearing ones, in one
line each:

- **(c)/(f)** — a module's `api` package is its public surface: no dependencies on its
  implementation packages, and no implementation types in public or protected signatures
  (generic arguments included). Factories may construct impl classes *in method bodies* —
  `HeapDumpParser.parse` builds a `HeapDumpImpl` behind `HeapDump` — but signatures may not leak
  them.
- **(a)/(b)/(g)** — `impl`/`internal`/`index` packages are module-private.
  `io.jafar.parser.internal_api` is the parser plane's shared internal contract; extending its
  consumer list is an architecture decision, not a convenience import.
- **(h)/(i)** — every extension point is a ServiceLoader-discovered SPI
  (`JfrBackend`, `ShellModule`, `LlmBackend`, `HandlerFactory`, `DeserializerFactory`,
  `UntypedEventStreamFactory`): the types stay interfaces, and the SPI packages never know their
  implementations. An integration that special-cases a concrete backend defeats the plugin
  architecture the shells are built on.
- The published API surfaces are guarded by japicmp against the last release
  ([Gates.md](Gates.md#api-evolution)); changing an SPI is a release decision — evolve compatibly
  or deprecate first. The jfr-shell-tck is the behavioural net for backends.

Existing reach-ins are grandfathered in the committed freeze store (shrink-only: a new one fails
the build, a deliberate grandfathering is a visible hand edit). Run `./gradlew :arch-tests:test`
locally in the dev loop — CI is the final gate, not the first place you learn.

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
- **Typed parser: two generators.** The typed parser decodes an `@JfrType` interface with a handler
  from one of two generators. `jafar-processor` generates one at build time (a `<Interface>Handler`
  and its `HandlerFactory`, found by `ServiceLoader` or passed to `registerFactory`).
  `parser-codegen`'s `CodeGenerator` generates one at runtime, from the chunk's metadata, when no
  factory serves the interface or the parser was told `ignoreDiscoveredFactories()`. They must read
  the same values and do comparable work per value; `BuildTimeRuntimeParityTest` compares their
  values on the generated fixtures, and AGENTS.md has the rule and its gate. Kept differences:
  - The build-time handler is reused: `HandlerFactory.get` hands every event of a thread the same
    instance after `reset()`, so an event must not be kept past its handler call. The runtime
    class is a new object per event. Pool entries are new objects on both sides.
  - A runtime-generated class is defined with `CodeGenerator` as its lookup host, so it lives in
    `parser-codegen`'s class loader and can implement only interfaces that loader sees; an
    interface in a child loader fails with `NoClassDefFoundError`. A build-time handler is
    compiled with its interface and has no such limit, but not if `parser-codegen` is on the class
    path too: `MutableMetadataLookup.bindDeserializers` then generates a runtime deserializer for
    every bound type, including one a build-time factory serves, and that generation fails the
    parse for an interface in a child loader.
  - Several accessors for one JFR field (`@JfrField` naming the same field twice), pooled or
    inline, are generated at runtime when they declare the field's one generated type (an inline
    primitive may be widened), next to any number of `raw = true` indices on a pooled scalar field
    (raw is refused on inline and array fields); accessors of unrelated types fail generation
    naming them (`TypedTwoAccessorsOneFieldTest`). `jafar-processor` reads such a field once per
    accessor: `opOf` maps the field to a group op, and its `read` case rewinds the stream to the
    field's start before each accessor's own read (`readOp`). Each accessor is planned and
    checked as if it read the field alone (`BuildTimeRuntimeParityTest` compares an alias, a raw
    index next to its pooled reference, a widened `int` and a second `String` accessor). An
    interface with no such field generates the same handler as before, byte for byte. Both
    generators accept an integer accessor of the field's own type or a wider one and refuse a
    narrower one (an `int` accessor of a `long` field). Kept differences: the processor decodes
    such a field once per accessor (and, for an inline complex value, allocates one handler per
    accessor), where the runtime class reads it once; and it reads `byte` and `boolean` as each
    other, which the runtime does not.
  - Interface methods with no field read behind them (`TypedAccessorShapesTest`). Both sides
    bind only abstract, non-static, parameterless methods without `@JfrIgnore`; a `default`
    method is left to the interface even when its name matches a field. An accessor of a field
    the recording lacks returns `0`, `false` or `null` at runtime. An accessor of a field present
    whose declared type cannot be produced (a narrowing primitive, a `raw = true` accessor not
    returning `long`) fails the parse at build time, naming the field (`HandlerTemplate`'s plan
    check). At runtime it throws `IllegalStateException` naming the accessor and the field when
    called, and generation does not fail, so a parse that never calls it runs: a kept
    difference. A field no accessor reads generates nothing at runtime, so an alias may take its
    name (`@JfrField("bytecodeIndex") int lineNumber()`).

  Both read a simple type (one field, such as `jdk.types.Symbol`) as the value it wraps, unless the
  accessor returns the interface registered for that type (`ThreadState state()` for a pooled
  `jdk.types.ThreadState`): then it is read as that interface. Until `CodeGenerator` did the
  same, the runtime class implemented such an accessor with the wrapped type's return type, which
  is not the interface's method: `AbstractMethodError` (`TypedSimpleTypeInterfaceTest`).
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

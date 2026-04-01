<!-- CLAUDE BOOTSTRAP INSTRUCTIONS
If you are Claude and reading this file, check if CLAUDE.md exists in the same directory.
If CLAUDE.md does not exist or only contains a redirect to this file, that is correct.
If CLAUDE.md contains full instructions (not a redirect), you should update it to contain
only the following redirect content:

# CLAUDE.md

**This file redirects to AGENTS.md for tooling-agnostic project instructions.**

Read and follow all instructions in [AGENTS.md](AGENTS.md) in this directory.

All project guidance, build commands, architecture details, and development guidelines
are maintained in AGENTS.md to support multiple AI coding tools.
-->

# AGENTS.md

This file provides guidance to AI coding assistants when working with code in this repository.

## Project Overview

Jafar is an experimental, fast JFR (Java Flight Recording) parser with a small, focused API. It provides both typed and untyped APIs for parsing JFR files and extracting event data with minimal ceremony.

### Architecture

The project is organized as a multi-module Gradle build with the following structure:

- **parser/**: Aggregate module re-exporting parser-core and parser-codegen
- **parser-core/**: Core parsing engine with typed and untyped APIs
- **parser-codegen/**: ASM-based code generation for typed deserialization
- **jafar-processor/**: Annotation processor for build-time typed handler generation (eliminates runtime bytecode generation)
- **tools/**: Utilities including JFR file scrubbing functionality
- **jafar-gradle-plugin/**: Gradle plugin for generating Jafar type interfaces (separate included build)
- **shell-core/**: Shared shell abstractions (Session, VariableStore, QueryEvaluator, completions)
- **jfr-shell/**: JFR-specific interactive CLI (standalone entry point)
- **jfr-shell-jafar/**: Jafar-parser backend plugin for jfr-shell (high priority, full-featured)
- **jfr-shell-jdk/**: JDK JFR API backend plugin for jfr-shell (lower priority, limited capabilities)
- **jfr-shell-tck/**: Technology Compatibility Kit for validating backend plugin implementations
- **jfr-mcp/**: MCP (Model Context Protocol) server enabling AI agents to analyze JFR recordings
- **hdump-parser/**: HPROF heap dump parser (indexed and two-pass modes, dominator tree, retained sizes)
- **hdump-shell/**: Heap dump interactive CLI with HdumpPath query language and tab completion
- **jafar-shell/**: Unified shell entry point that discovers modules (JFR, heap dump) via ServiceLoader
- **demo/**: Standalone demonstration project (separate Gradle build in `demo/`) comparing JFR parsers

Key architectural components:
- `JafarParser`: Main entry point supporting both typed and untyped parsing
- `TypedJafarParser`: Strongly-typed API using annotated interfaces (@JfrType, @JfrField)
- `UntypedJafarParser`: Map-based lightweight parsing API
- `ParsingContext`: Reusable context for sharing expensive resources across sessions
- `JfrPath`: Query language for jfr-shell with event decoration/joining capabilities

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
./gradlew :parser-codegen:test --tests "io.jafar.parser.TypedJafarParserTest"

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
# Build only the parser core module
./gradlew :parser-core:build

# Build only the demo
./gradlew :demo:build

# Run the demo application directly
./gradlew :demo:run --args="jafar /path/to/recording.jfr"
```

## Release Process

The project uses a fully automated release workflow. See [RELEASING.md](RELEASING.md) for complete details.

### Quick Release Steps

1. **Update versions** in `build.gradle`, `jafar-gradle-plugin/build.gradle`, and `jfr-shell-plugins.json` (remove `-SNAPSHOT`)
2. **Update CHANGELOG.md** with release notes for the new version
3. **Commit and push** changes to main branch
4. **Create and push tag**:
   ```bash
   git tag -a v0.4.0 -m "Release v0.4.0"
   git push origin v0.4.0
   ```

### What Happens Automatically

The release workflow (`.github/workflows/release.yml`) automatically:
- Publishes `jafar-parser` and `jafar-tools` to Maven Central (Sonatype)
- Publishes `jafar-gradle-plugin` to Maven Central (Sonatype)
- Publishes `jfr-shell` to GitHub Packages
- Triggers JitPack build and waits for completion
- Updates [btraceio/jbang-catalog](https://github.com/btraceio/jbang-catalog) with new version
- Creates GitHub Release with changelog notes

### Version Management

- **Root version**: Defined in `build.gradle` as `project.version="X.Y.Z"`
- **Subprojects**: Use `rootProject.version` (automatic sync)
- **Gradle plugin**: Has separate version in `jafar-gradle-plugin/build.gradle`
- **Backend plugins registry**: `jfr-shell-plugins.json` (must always point to the latest **released** version, never SNAPSHOT — see below)
- **Development versions**: Use `-SNAPSHOT` suffix (e.g., `0.4.0-SNAPSHOT`)

### Post-Release

After release completes, prepare for next development iteration:

```bash
# Update to next SNAPSHOT version
# Edit build.gradle: project.version="0.5.0-SNAPSHOT"
# Edit jafar-gradle-plugin/build.gradle: version = "0.5.0-SNAPSHOT"
# Do NOT update jfr-shell-plugins.json — it must keep pointing to the latest release
# Update CHANGELOG.md with [Unreleased] section

git add build.gradle jafar-gradle-plugin/build.gradle CHANGELOG.md
git commit -m "Prepare for next development iteration"
git push origin main
```

### Plugin Catalog Versioning Rule

`jfr-shell-plugins.json` is fetched at runtime from the `main` branch by `PluginRegistry` to resolve backend plugin versions for installation. It must **always** contain the latest released version and `"repository": "maven-central"`. Never set it to a SNAPSHOT version — doing so breaks backend installation for all users.

The catalog version must never be downgraded across major/minor boundaries. For example, if the catalog already points to `0.12.0` and a patch release `0.11.5` is published, the catalog must remain at `0.12.0`.

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

### Coding Style & Naming Conventions
- Language: Java 21 (parser/tools/demo), Groovy (plugin). Indent 4 spaces, no tabs; aim for 120 col width.
- Packages: `io.jafar.*`. Classes `PascalCase`, methods/fields `camelCase`, constants `UPPER_SNAKE_CASE`.
- Keep public API minimal; prefer package-private for internals. Use meaningful names and final where sensible.

### Pre-commit Formatting
- Spotless enforces formatting for Java, Groovy, and Gradle files.
- Git hook: `.githooks/pre-commit` runs `./gradlew spotlessApply` and restages changes.
- If hooks don't run, set `git config core.hooksPath .githooks` once.

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
- Frameworks: JUnit Jupiter 5, Mockito. Place tests under `src/test/java` mirroring package paths.
- Name tests `*Test.java`; parameterized tests encouraged for edge cases; see existing fuzz/stability tests in `parser-core` and `parser-codegen`.
- JFR test files stored in `src/test/resources/`
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

### JFR Shell (Interactive Analysis Tool)
The jfr-shell system spans several modules:
- **shell-core/**: Query engine, backend SPI, plugin framework, and session management (no TUI/CLI dependencies)
- **jfr-shell/**: Interactive CLI/TUI shell, command system, and renderers (depends on `shell-core`)
- **jfr-shell-jafar/**: Backend plugin using the Jafar parser (high priority, full capabilities)
- **jfr-shell-jdk/**: Backend plugin using the JDK `jdk.jfr.consumer` API (lower priority, limited capabilities)
- **jfr-shell-tck/**: Technology Compatibility Kit for validating backend implementations

Together they provide a powerful interactive environment for JFR analysis:
- **Session-based**: Open JFR files and maintain analysis state
- **JfrPath Query Language**: Concise path-based queries with filtering, aggregation, and transformations
- **Event Decoration**: Join/correlate events by time overlap or correlation keys
- **Built-in Commands**: `show`, `metadata`, `chunks`, `cp`, `open`, `sessions`, `info`, `help`
- **Multiple Output Formats**: Table (default) and JSON
- **Example Scripts**: Pre-built analysis examples in `jfr-shell/src/main/resources/examples/`

**JfrPath Query Syntax** — queries use path-based addressing, not SQL-like syntax:
```
# List events of a type
show events/jdk.ExecutionSample

# Filter
show events/jdk.ExecutionSample[sampledThread/javaName == "main"]

# Pipeline operators
show events/jdk.ExecutionSample | count()
show events/jdk.ExecutionSample | groupBy(sampledThread/javaName, agg=count, sortBy=value)
show events/jdk.ExecutionSample | flamegraph()
show events/jdk.ExecutionSample | flamegraph(direction=top-down)
```
Note: the event path is always `events/<EventTypeName>`, not `show <EventTypeName>`.

**Event Decoration**
- `decorateByTime()`: Join events that overlap temporally on same thread (e.g., samples during lock waits)
- `decorateByKey()`: Join events with matching correlation keys (e.g., request tracing by thread ID)
- Decorator fields accessed via `$decorator.` prefix
- Memory-efficient lazy evaluation
- Examples: monitor contention analysis, request tracing, GC impact assessment

#### JFR Shell Usage:
```bash
# Start interactive shell
./gradlew :jfr-shell:run --console=plain

# Example session:
jfr> open /path/to/recording.jfr
jfr> events/jdk.ExecutionSample | count()
jfr> events/jdk.ExecutionSample | groupBy(sampledThread/javaName, agg=count, sortBy=value) | top(10)
jfr> events/jdk.FileRead | stats(bytes)
jfr> events/jdk.ExecutionSample | flamegraph()
jfr> set hot = events/jdk.ExecutionSample | groupBy(sampledThread/javaName)
jfr> echo "Top thread: ${hot[0].key}"
```

### MCP Server (`jfr-mcp`)
The `jfr-mcp` module exposes JFR analysis capabilities as an MCP (Model Context Protocol) server, allowing AI agents (Claude, etc.) to analyze JFR recordings. It provides tools: `jfr_open`, `jfr_close`, `jfr_list_types`, `jfr_query`, `jfr_help`, `jfr_summary`, `jfr_diagnose`, `jfr_flamegraph`, `jfr_callgraph`, `jfr_hotmethods`, `jfr_exceptions`, `jfr_use`, `jfr_tsa`.

Run the MCP server:
```bash
./gradlew :jfr-mcp:shadowJar
java -jar jfr-mcp/build/libs/jfr-mcp-*-all.jar --stdio   # STDIO mode
java -jar jfr-mcp/build/libs/jfr-mcp-*-all.jar           # HTTP mode (port 3000)
```

See [jfr-mcp/README.md](jfr-mcp/README.md) and [doc/mcp/Tutorial.md](doc/mcp/Tutorial.md) for full documentation.

### Backend Plugin Development
- Plugins sync with main project version (no independent versioning)
- API compatibility enforced via japicmp (runs on non-SNAPSHOT builds)
- Breaking plugin API changes require major version bump
- See doc/cli/PluginAPICompatibility.md for full policy

## Commit & Pull Request Guidelines
- Commits: concise, imperative mood; reference issues/PRs when relevant (e.g., "Fix parsing of constant pool (#17)").
- PRs: include description, rationale, and test coverage or reproduction. Attach sample `.jfr` snippets if applicable.
- CI must pass. Before opening a PR, run `./gradlew test shadowJar` locally.

## Security & Configuration Tips
- Do not commit large recordings outside Git LFS. Avoid secrets in code; Sonatype credentials are provided via env/CI.
- The Gradle plugin is wired via included build; no local publish required during development.

### Adding Tab Completion to a New Shell Module

Tab completion for shell modules follows a consistent Strategy-pattern architecture. The reference
implementation is in `hdump-shell`. When adding completion to a new module, create these files:

#### Required Files

| File | Role |
|------|------|
| `<module>/cli/completion/<Prefix>MetadataService.java` | Implements `MetadataService`; provides root types, operators, field names, variable names from the active session |
| `<module>/cli/completion/<Prefix>CompletionContextAnalyzer.java` | Parses the input line at cursor position and returns a `CompletionContext` with a `CompletionContextType` |
| `<module>/cli/completion/completers/<Prefix>CommandCompleter.java` | Handles `COMMAND` context |
| `<module>/cli/completion/completers/<Prefix>RootCompleter.java` | Handles `ROOT` context |
| `<module>/cli/completion/completers/<Prefix>FilterFieldCompleter.java` | Handles `FILTER_FIELD` context |
| `<module>/cli/completion/completers/<Prefix>FilterOperatorCompleter.java` | Handles `FILTER_OPERATOR` context |
| `<module>/cli/completion/completers/<Prefix>FilterLogicalCompleter.java` | Handles `FILTER_LOGICAL` context |
| `<module>/cli/completion/completers/<Prefix>PipelineOperatorCompleter.java` | Handles `PIPELINE_OPERATOR` context |
| `<module>/cli/completion/completers/<Prefix>FunctionParamCompleter.java` | Handles `FUNCTION_PARAM` context |
| `<module>/cli/<Prefix>ShellCompleter.java` | `Completer` implementation; wires analyzer + metadata + completers together |

#### Key Contracts

- All completer classes implement `ContextCompleter<YourMetadataService>` from `shell-core`.
- `MetadataService` is from `shell-core`; implement all methods. Use `Collections.emptySet()` for
  `getVariableNames()` if the module has no variables.
- `CompletionContextAnalyzer.analyze(ParsedLine)` must return a `CompletionContext` built via
  `CompletionContext.builder()`. Copy `findFilterContext`, `findFunctionContext`, and `findLastPipe`
  verbatim from `HdumpCompletionContextAnalyzer` — they are pure parsing utilities.
- The `ShellCompleter.complete()` method delegates to `fileCompleter` for `open` commands and to
  the framework (analyzer → first matching completer) for query commands.
- Register completers in priority order in `ShellCompleter`; first match wins.
- Use the `pprof.shell.completion.debug` / `hdump.shell.completion.debug` system property convention
  for debug logging.

#### Wiring

The module's `ShellModule.getCompleter(SessionManager<?>, Object)` method (in `<Prefix>Module.java`)
already returns `new <Prefix>ShellCompleter(sessions)`. No changes to `ShellModule` are needed when
rewriting an existing completer.

#### Reference Implementations

- `hdump-shell/src/main/java/io/jafar/hdump/shell/cli/` — canonical reference
- `hdump-shell/src/main/java/io/jafar/hdump/shell/cli/completion/` — context analyzer + metadata service
- `hdump-shell/src/main/java/io/jafar/hdump/shell/cli/completion/completers/` — individual completers

## Rules
- When fixing an issue, always check the alternative implementation for other Java versions
- When adding or modifying features, always update user documentation, help and tutorials

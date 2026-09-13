# The shells

`jfr-shell`, JfrPath, backend plugins, and the tab-completion recipe.

## JFR Shell (Interactive Analysis Tool)
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

### JFR Shell Usage:
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

## Backend Plugin Development
- Plugins sync with main project version (no independent versioning)
- API compatibility enforced via japicmp (runs on non-SNAPSHOT builds)
- Breaking plugin API changes require major version bump
- See doc/cli/PluginAPICompatibility.md for full policy

## Adding Tab Completion to a New Shell Module

Tab completion for shell modules follows a consistent Strategy-pattern architecture. The reference
implementation is in `hdump-shell`. When adding completion to a new module, create these files:

### Required Files

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

### Key Contracts

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

### Wiring

The module's `ShellModule.getCompleter(SessionManager<?>, Object)` method (in `<Prefix>Module.java`)
already returns `new <Prefix>ShellCompleter(sessions)`. No changes to `ShellModule` are needed when
rewriting an existing completer.

### Reference Implementations

- `hdump-shell/src/main/java/io/jafar/hdump/shell/cli/` — canonical reference
- `hdump-shell/src/main/java/io/jafar/hdump/shell/cli/completion/` — context analyzer + metadata service
- `hdump-shell/src/main/java/io/jafar/hdump/shell/cli/completion/completers/` — individual completers

# Hdump Module Reintegration Notes

The following modules are disabled in `settings.gradle` after the merge with `origin/main`:
- `:hdump-parser`
- `:hdump-shell`
- `:jafar-shell`

## What needs to be restored

### 1. CustomByteBuffer extensions (for hdump-parser)

The `CustomByteBuffer` interface (in `parser-core/src/main/java/io/jafar/utils/CustomByteBuffer.java`)
needs these methods added back:

- `long limit()` — buffer size
- `byte get(long offset)` — absolute read at offset
- `int getInt(long offset)` — absolute int read at offset
- `long getLong(long offset)` — absolute long read at offset
- `void close() throws IOException` — resource cleanup

These must also be added to the Java 13 and Java 21 source set variants:
- `parser-core/src/java13/java/io/jafar/utils/CustomByteBuffer.java`
- `parser-core/src/java21/java/io/jafar/utils/CustomByteBuffer.java`

And implementations in all `SplicedMappedByteBuffer` variants:
- `parser-core/src/main/java/io/jafar/utils/SplicedMappedByteBuffer.java`
- `parser-core/src/java13/java/io/jafar/utils/SplicedMappedByteBuffer.java`
- `parser-core/src/java21/java/io/jafar/utils/SplicedMappedByteBuffer.java`

The branch versions of these files (before merge) had these implementations.

### 2. Shell-core abstractions (for hdump-shell and jafar-shell)

The branch had a `shell-core` module (renamed to `jfr-shell-core` on main) with shared interfaces:
- `io.jafar.shell.core.Session` — generic session interface (JFR and hdump sessions)
- `io.jafar.shell.core.SessionManager` — multi-session management
- `io.jafar.shell.core.ShellModule` — plugin interface for shell modules (JFR, hdump)
- `io.jafar.shell.core.QueryEvaluator` — generic query evaluation
- `io.jafar.shell.core.VariableStore` — variable storage with `LazyValue` interface
- `io.jafar.shell.core.OutputWriter` — output abstraction
- `io.jafar.shell.core.render.PagedPrinter` — paged output
- `io.jafar.shell.core.render.TableRenderer` — table rendering
- `io.jafar.shell.core.completion.*` — completion interfaces

On main, `SessionManager` and `VariableStore` live in `jfr-shell/src/main/java/io/jafar/shell/core/`
but without the multi-format abstraction layer. The other interfaces don't exist on main.

These need to either:
- Be added to `jfr-shell-core` as shared interfaces, or
- Be reintroduced as a separate module

### 3. JFRSession changes

The branch made `JFRSession` implement `Session` and added:
- `getFilePath()` (main uses `getRecordingPath()`)
- `getType()` method
- `getAvailableTypes()` delegating to `getAvailableEventTypes()`

### 4. Module-specific files

- `jfr-shell/src/main/java/io/jafar/shell/JfrModule.java` — JFR ShellModule implementation
- `jfr-shell/src/main/java/io/jafar/shell/JfrQueryEvaluator.java` — JFR QueryEvaluator implementation
- `jfr-shell/src/main/resources/META-INF/services/io.jafar.shell.core.ShellModule` — ServiceLoader registration

# Unified TUI Shell Plan

## Goal

Make the full-screen TUI (`--tui` flag) work in the unified `jafar-shell`, supporting
both JFR and heap dump modules (and future modules). Today the TUI lives entirely in
`jfr-shell` and is hardcoded to `JFRSession`.

## Current State

### Files involved (8,871 lines total)

| File | Lines | JFR coupling |
|------|------:|:------------:|
| `TuiShell.java` | 80 | **Heavy** — creates `SessionManager<JFRSession>`, `ParsingContext` |
| `TuiWiring.java` | 98 | **Heavy** — wires JFR `CommandDispatcher`, `ShellCompleter`, hardcodes `"jfr>"` prompt |
| `TuiCommandExecutor.java` | 1288 | **Heavy** — calls `getRecordingPath()`, `getNonPrimitiveMetadataTypes()`, `getMetadataTypeIds()`, `MetadataProvider`, `ConstantPoolProvider`, `ParsingContext` |
| `TuiBrowserController.java` | 728 | **Heavy** — calls `JfrPathParser.parse()`, `JfrPathEvaluator`, `ConstantPoolProvider.loadEntries()` |
| `TuiKeyHandler.java` | 1006 | **Light** — only `SessionManager<JFRSession>` type parameter, sets `outputFormat` |
| `TuiRenderer.java` | 1230 | **Light** — only `SessionManager<JFRSession>` type parameter, calls `getRecordingPath()` |
| `TuiContext.java` | 337 | **Light** — hardcoded `".jfr-shell"` history path |
| `TuiDetailBuilder.java` | 423 | **None** |
| `TuiEventLoop.java` | 74 | **None** |

External JFR-coupled dependencies:
- `CommandDispatcher` (2747 lines) — hardcoded to `JFRSession`, uses JFR parsers/providers directly
- `ShellCompleter` (860 lines) — hardcoded to `JFRSession`

### Key observation

The unified readline `Shell.java` already solves the module-agnostic problem:
- Uses `SessionManager<Session>` (generic interface)
- Routes `open` to the correct `ShellModule`
- Delegates queries to `QueryEvaluator` via the module
- Creates `CommandDispatcher` as an opaque context object passed to module completers

The TUI needs the same pattern but for its richer UI (browser panels, detail views,
key navigation, TamboUI rendering).

## Approach: Adapter Layer

Rather than rewriting all 8,871 lines of TUI code to be generic, introduce a **thin
adapter interface** that modules implement to provide TUI-specific capabilities. The
core TUI framework stays in `jfr-shell` (it depends on TamboUI which is already there),
but is parameterized over `Session` instead of `JFRSession`.

### Phase 1: Extract TUI session adapter interface (into `jfr-shell-core`)

Create `io.jafar.shell.core.TuiAdapter` in `jfr-shell-core`:

```java
public interface TuiAdapter {
    /** Dispatch a command string, writing output via the IO callback. */
    void dispatch(String command, CommandIO io) throws Exception;

    /** Return a JLine completer for the TUI input line. */
    Completer getCompleter();

    /** Return browser data for the "metadata summary" panel, or null if unsupported. */
    List<Map<String, Object>> browseMetadata(Session session) throws Exception;

    /** Return browser data for "constant pool summary" panel, or null. */
    List<Map<String, Object>> browseConstants(Session session, String typeName) throws Exception;

    /** Return browser data for "events summary" panel, or null. */
    List<Map<String, Object>> browseEvents(Session session, String typeName, int limit) throws Exception;

    /** Return the set of browsable categories (e.g., "metadata", "constants", "events"). */
    Set<String> getBrowsableCategories();

    interface CommandIO {
        void println(String s);
        void printf(String fmt, Object... args);
        void error(String s);
    }
}
```

Add `TuiAdapter` factory method to `ShellModule`:

```java
default TuiAdapter createTuiAdapter(SessionManager<?> sessions, Object context) {
    return null; // Module does not support TUI
}
```

### Phase 2: Generalize TUI type parameters

Change **every** `SessionManager<JFRSession>` → `SessionManager<? extends Session>` in:

| File | Change |
|------|--------|
| `TuiWiring.java` | Method parameter type |
| `TuiCommandExecutor.java` | Field + setter type |
| `TuiKeyHandler.java` | Field + constructor type |
| `TuiRenderer.java` | Field + constructor type |
| `TuiBrowserController.java` | Field + constructor type |

Replace `getRecordingPath()` calls with `getFilePath()` (they return the same value
in `JFRSession`; `getFilePath()` is on the `Session` interface):
- `TuiRenderer.java:122,1070`
- `TuiCommandExecutor.java:236,279,295`
- `TuiBrowserController.java:253`

### Phase 3: Route TUI browser/executor through TuiAdapter

This is the largest phase. The JFR-specific logic in `TuiCommandExecutor` and
`TuiBrowserController` must be routed through the adapter.

#### TuiCommandExecutor changes

Current JFR-specific calls to extract behind `TuiAdapter`:

| Line(s) | Current code | Replace with |
|---------|-------------|-------------|
| 235-260 | `isMetadataSummaryCommand()` → `MetadataProvider.loadAllClasses()`, `getNonPrimitiveMetadataTypes()`, `getMetadataTypeIds()` | `adapter.browseMetadata(session)` |
| 278-290 | `isCpSummaryCommand()` → `ConstantPoolProvider.loadEntries()` | `adapter.browseConstants(session, type)` |
| 294-320 | `isEventsSummaryCommand()` → `ParsingContext.create().newUntypedParser()` | `adapter.browseEvents(session, type, limit)` |

General command dispatch already goes through `CommandDispatcher.dispatch()` — replace
with `adapter.dispatch(command, io)`.

#### TuiBrowserController changes

| Line(s) | Current code | Replace with |
|---------|-------------|-------------|
| 253-260 | `ConstantPoolProvider.loadEntries(recording, typeName)` | `adapter.browseConstants(session, typeName)` |
| 351-360 | `JfrPathParser.parse("events/" + typeName)` → `evaluator.evaluateWithLimit()` | `adapter.browseEvents(session, typeName, limit)` |

#### TuiWiring changes

- Accept `TuiAdapter` instead of creating `CommandDispatcher` and `ShellCompleter` directly
- Replace hardcoded `"jfr>"` with prompt derived from active session type
- Get completer from `adapter.getCompleter()` instead of `new ShellCompleter(...)`

### Phase 4: Implement JFR TuiAdapter in `jfr-shell`

Create `io.jafar.shell.JfrTuiAdapter` that wraps the existing `CommandDispatcher`
and JFR provider calls:

```java
public final class JfrTuiAdapter implements TuiAdapter {
    private final CommandDispatcher dispatcher;
    private final ShellCompleter completer;
    private final SessionManager<JFRSession> sessions;

    // browseMetadata → delegates to MetadataProvider + session metadata methods
    // browseConstants → delegates to ConstantPoolProvider
    // browseEvents → delegates to JfrPathParser + JfrPathEvaluator
    // dispatch → delegates to CommandDispatcher.dispatch()
    // getCompleter → returns ShellCompleter
}
```

Wire it in `JfrModule.createTuiAdapter()`.

### Phase 5: Implement Hdump TuiAdapter in `hdump-shell`

Create `io.jafar.hdump.shell.HdumpTuiAdapter` — can start minimal:

```java
public final class HdumpTuiAdapter implements TuiAdapter {
    // dispatch → route to HdumpPath evaluation
    // browseMetadata → list classes summary
    // browseConstants → null (not applicable)
    // browseEvents → null (not applicable — maybe browseObjects?)
    // getCompleter → HdumpShellCompleter
}
```

### Phase 6: Create unified TuiShell in `jafar-shell`

New `io.jafar.shell.unified.UnifiedTuiShell`:

```java
public final class UnifiedTuiShell implements AutoCloseable {
    private final SessionManager<Session> sessions;
    private final Map<String, ShellModule> modules;
    // On session switch: look up module → get TuiAdapter → rewire TUI components
}
```

Add `--tui` to `jafar-shell` `Main.java`, launching `UnifiedTuiShell`.

### Phase 7: Update TuiContext

- Change history path from `".jfr-shell"` → `".jafar-shell"`
- Derive tab names from `session.getType()` + `">"` instead of hardcoded `"jfr>"`

## File Change Summary

| Action | Module | File |
|--------|--------|------|
| **Create** | `jfr-shell-core` | `io.jafar.shell.core.TuiAdapter` |
| **Modify** | `jfr-shell-core` | `ShellModule.java` — add `createTuiAdapter()` default method |
| **Modify** | `jfr-shell` | `TuiWiring.java` — accept `TuiAdapter` + `SessionManager<? extends Session>` |
| **Modify** | `jfr-shell` | `TuiCommandExecutor.java` — route through `TuiAdapter` |
| **Modify** | `jfr-shell` | `TuiBrowserController.java` — route through `TuiAdapter` |
| **Modify** | `jfr-shell` | `TuiKeyHandler.java` — generalize session type |
| **Modify** | `jfr-shell` | `TuiRenderer.java` — `getRecordingPath()` → `getFilePath()` |
| **Modify** | `jfr-shell` | `TuiContext.java` — configurable history path + prompt |
| **Delete** | `jfr-shell` | `TuiShell.java` — replaced by unified version |
| **Create** | `jfr-shell` | `JfrTuiAdapter.java` |
| **Create** | `hdump-shell` | `HdumpTuiAdapter.java` (minimal) |
| **Create** | `jafar-shell` | `UnifiedTuiShell.java` |
| **Modify** | `jafar-shell` | `Main.java` — add `--tui` option |
| **Modify** | `jfr-shell` | `JfrModule.java` — implement `createTuiAdapter()` |
| **Modify** | `hdump-shell` | `HdumpModule.java` — implement `createTuiAdapter()` |

## Execution Order

1. **Phase 1** — Interface definition (low risk, no behavior change)
2. **Phase 2** — Type parameter generalization (mechanical, compile-verifiable)
3. **Phase 3** — Extract JFR logic behind adapter (highest risk — must preserve all TUI behavior)
4. **Phase 4** — JFR adapter implementation (moves extracted code into adapter)
5. **Phase 5** — Hdump adapter (new code, independent)
6. **Phase 6** — Unified TuiShell + `--tui` flag (integration)
7. **Phase 7** — Cosmetic (history path, prompt)

Phases 1-2 can be validated with `./gradlew test` after each.
Phase 3-4 should be done together and validated against existing TUI behavior.
Phases 5-7 are independent of each other.

## Risks

- **TuiBrowserController** has the deepest JFR coupling (inline `JfrPathParser.parse()` +
  `JfrPathEvaluator.evaluateWithLimit()` calls). The `browseEvents` adapter method must
  support the same limit+preview behavior.
- **TuiCommandExecutor** has 3 "summary command" code paths that do JFR-specific I/O
  (metadata listing, CP listing, event type listing). These are TUI-specific browsing
  features that don't exist in the readline shell. The adapter must expose these as
  structured data rather than formatted strings.
- **CommandDispatcher** (2747 lines) stays JFR-specific — it's not generalized, just
  wrapped by `JfrTuiAdapter`. This is intentional: the dispatcher handles JFR-specific
  commands like `types`, `chunks`, `cp`, `metadata` that have no hdump equivalents.
  The adapter abstracts over this difference.

## Non-Goals

- Generalizing `CommandDispatcher` itself (too large, JFR-specific commands have no generic equivalent)
- Generalizing `ShellCompleter` (completion is inherently module-specific)
- Making TamboUI a dependency of `jfr-shell-core` (TamboUI stays in `jfr-shell`)

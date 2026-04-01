# Multi-Format Shell Architecture

This document describes the modular architecture that allows the Jafar shell to support
multiple data formats (JFR recordings, HPROF heap dumps, and future formats) through
a plugin system.

## Overview

The shell uses a plugin-based module system where each data format registers a `ShellModule`
via Java's `ServiceLoader`. Modules provide:

- **Session creation** — opening files and creating typed sessions
- **Query evaluation** — format-specific query languages (JfrPath, HdumpPath)
- **Tab completion** — context-aware completions for the query language
- **TUI adapter** — full-screen browser mode with format-specific categories

## Key Interfaces

### ShellModule (`shell-core`)

The main plugin interface. Each module declares:

- `getId()` / `getDisplayName()` — identification
- `getSupportedExtensions()` / `canHandle(Path)` — file detection (magic bytes preferred)
- `createSession(Path, Object)` — session factory
- `getQueryEvaluator()` — query language bridge
- `getCompleter(SessionManager, Object)` — JLine completer
- `createTuiAdapter(SessionManager, Object)` — TUI adapter (optional)

### QueryEvaluator (`shell-core`)

Bridges the shell's `show` command to format-specific query languages:

- `parse(String)` — parse query string into module-specific AST
- `evaluate(Session, Object)` — evaluate parsed query against session
- `getRootTypes()` / `getOperators()` — discovery for help and completion

### TuiAdapter (`shell-core`)

Adapts format-specific capabilities for the full-screen TUI:

- `detectBrowserCommand(String)` — detects commands like "metadata", "events", "classes"
- `loadBrowseSummary(Session, String)` — loads sidebar summary for a category
- `loadBrowseEntries(Session, String, String, int)` — loads detail entries for a type
- `loadMetadataClasses(Session)` — metadata class details for drill-down
- `getCompleter()` — module-specific completer for the input line
- `getPromptPrefix()` — prompt prefix ("jfr", "hdump")
- `isEventsSummaryAsync()` — whether summary loading should run in background

## Command Routing

```
User input
    │
    ▼
CommandDispatcher
    │
    ├─ Generic commands (open, close, sessions, use, info, set, vars, echo, help, if/elif/else)
    │  → Always handled, regardless of session type
    │
    ├─ JFR-specific commands (events, metadata, constants, chunks, cp, backend)
    │  → Guarded by currentJfrSession() != null
    │  → Return false when session is not JFR
    │
    ├─ show/select
    │  ├─ JFR session → JfrPath evaluation
    │  └─ Non-JFR session → moduleEvaluator.evaluate() fallback
    │
    └─ returns false
         │
         ▼
    TuiAdapter.dispatch() (TUI mode only)
         │
         └─ Module handles unrecognized commands
```

## TUI Browser Mode

The TUI browser provides a sidebar + detail split view for browsing structured data.
Each module declares browsable categories via `TuiAdapter.getBrowsableCategories()`.

### Flow

1. User types a command (e.g., "metadata", "constants", "classes")
2. `TuiCommandExecutor` calls `tuiAdapter.detectBrowserCommand(command)`
3. If a category is detected:
   - For async categories (e.g., JFR events): submit background task
   - For sync categories: call `tuiAdapter.loadBrowseSummary()` directly
4. `TuiBrowserController.enterBrowserMode()` sets up sidebar and detail view
5. Sidebar navigation calls `tuiAdapter.loadBrowseEntries()` for selected type

### JFR Browser Categories

| Category    | Summary                  | Detail entries              |
|-------------|--------------------------|------------------------------|
| `metadata`  | All non-primitive types  | Class fields and annotations |
| `constants` | CP type counts           | Constant pool entries        |
| `events`    | Event type counts (async)| Event instances (limit 500)  |

### HPROF Browser Categories

| Category  | Summary             | Detail entries          |
|-----------|---------------------|--------------------------|
| `classes` | Class names + stats | Object instances          |

## Adding a New Format

1. Create a new Gradle module (e.g., `myformat-shell`)
2. Implement `ShellModule` with file detection, session creation, query evaluator
3. Register via `META-INF/services/io.jafar.shell.core.ShellModule`
4. Optionally implement `TuiAdapter` for browser mode support
5. Add module dependency to `jafar-shell/build.gradle`

The shell will automatically discover the module at startup and route files to it
based on `canHandle(Path)` matching.

## Module Discovery

Modules are loaded via `ShellModuleLoader.loadAll()` which uses `ServiceLoader`.
The `ModuleSessionFactory` routes `open` commands to the correct module based on
file type detection. Modules are sorted by priority (higher = preferred).

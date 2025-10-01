# JFR Shell – Full-Fledged Interactive CLI Plan

Status: revised draft (no Groovy)
Owner: jfr-shell
Scope: CLI-based interactive UI on top of Jafar parser

This plan turns the current prototype into a robust, testable CLI with multi-session management, rich discovery/browsing, and a query expression for selecting/filtering/displaying events and values.

---

## Goals

- Multi-session: open multiple JFR recordings, list/switch/close sessions.
- Discovery: list and search event types per session; generate typed interfaces when needed.
- Browse: recording metadata, chunks, and constant pools with summaries and detail views.
- Query: path-like expression to filter/display events and their values; export in table/json/csv.
- UX: interactive shell with command help, completion, paging; non-interactive one-shot commands as well.
- Quality: unit/integration tests with sample JFRs; clear docs; packaged runnable jar and script.

Non-goals (for now):
- Full JSONPath/JMESPath parity; we add a focused “JFRPath” tailored to JFR.
- Live/remote recordings; only local file inputs.

---

## Current State Snapshot (revised)

- Shell: New pure-Java `Shell` using JLine for REPL and completion, with `SessionManager` + `CommandDispatcher`.
- Sessions: `JFRSession` wraps typed parser, collects event counts, exposes metadata post-scan.
- Type discovery: `TypeDiscovery` scans metadata and can generate typed interfaces using `TypeGenerator`.
- CLI entry: `Main` via picocli; script/execute placeholders; single-session only.

Gaps vs goals:
- No multi-session management, switching, or aliasing.
- No top-level commands for metadata/chunks/constant pools browsing.
- No query language; build a dedicated JfrPath engine (no Groovy).
- Minimal help; no completion, paging, or non-interactive subcommands.

---

## Architecture Overview (no Groovy)

- `SessionManager` (new): holds multiple `JFRSession` instances keyed by ID and optional alias; manages current session.
- `CommandDispatcher` (new): parses commands, routes to command handlers; shared between interactive and non-interactive.
- `Commands` (new package): small, composable handlers (open, sessions, use, metadata, chunks, cp, show, export, etc.).
- `Renderer` (new): tabular/JSON/CSV renderers, with column sizing and optional pager integration.
- `JfrPath` (new): expression parser + evaluator producing streams/iterables of rows/values; compiles to predicates/selectors.
- `Providers` (internal): adapters around Jafar API to expose common models for metadata/chunks/constant pools/events.

Keep existing classes, evolve them minimally:
- `JFRSession`: remain the per-recording handle; add lightweight introspection helpers and references needed by commands.
- `Shell`: thin REPL around `CommandDispatcher` + JLine features; no Groovy runtime.
- `Main`: adds non-interactive subcommands, improves error handling.

---

## Command Set Specification

Session lifecycle
- `open <path> [--alias NAME]`: open a recording; becomes current session.
- `sessions`: list sessions with id, alias, file, status, event count.
- `use <id|alias>`: switch current session.
- `close [<id|alias>|--all]`: close a session or all.
- `info [<id|alias>]`: session summary (file, duration, events processed, handlers, types discovered).

Type discovery
- `types [--search <glob|regex>] [--refresh]`: list available event types, optionally filtered; `--refresh` rescans metadata.

Metadata
- `metadata [--tree|--json] [--filter <path>]`: dump metadata tree (classes, fields, annotations); optional filter path.
- `metadata class <name> [--fields] [--annotations]`: show details of a specific class.

Chunks
- `chunks [--summary|--list] [--range N-M]`: summary or list of chunks (index, start, duration, counts, cp refs).
- `chunk <index> show [--header|--events|--constants]`: detailed view of a single chunk header and relationships.

Constant pools
- `cp [--summary]`: show constant pool sizes by kind.
- `cp <kind> [--range N-M]`: list entries of a kind (e.g., `method`, `class`, `symbol`, `thread`).

Query & display
- `show <expr> [--where <pred>] [--limit N] [--format table|json|csv] [--cols <paths>] [--distinct]`
  - Examples:
    - `show events/jdk.ExecutionSample --limit 10 --cols timestamp,thread/name,cpu`
    - `show events/jdk.FileRead --where 'bytes>1_000_000' --format json`
    - `show events/jdk.ExecutionSample[thread/name~"main"]/stacktrace/frames[0]/method/name`
- `print <expr>`: shorthand to evaluate a path and print the resulting scalar/collection.
- `export [<expr>|last] --to <file> [--format json|csv]`: export last result or an expression result.

Help & misc
- `help [<command>]`: contextual help with examples.
- `version`, `quit|exit`.

Non-interactive
- `jfr-shell sessions ...`, `jfr-shell show ...` via picocli subcommands; return non-zero on errors.

---

## JfrPath (Path-like Expression)

Scope v1
- Roots: `events`, `metadata`, `chunks`, `cp`.
- Navigation: `/` separated segments; dot `.` equivalent; simple index `[n]` and slice `[start:end]`.
- Filters: `[predicate]` where predicate supports `=`, `!=`, `>`, `>=`, `<`, `<=`, `~` (regex), `in`, `contains`.
- Field access: segment names map to event fields; fallback to reflection on typed/untyped values.
- Aggregates (post-filter pipeline): `| count()`, `| sum(path)`, `| groupBy(path)`, `| top(n, by=path)`.

Grammar sketch (informal)
- `path := root ( "/" segment )* ( filter )* ( pipeline )?`
- `segment := name | name index | name slice`
- `filter := "[" predicate "]"`
- `predicate := expr (op expr)`
- `expr := literal | identifier ("/" identifier)*`
- `pipeline := "|" function ("," args)?`

Examples
- `events/jdk.ThreadSleep[time>=10ms]`
- `events/jdk.SocketRead[remoteHost~"10\\.0\\..*"] | count()`
- `events/jdk.ExecutionSample | groupBy(thread/name) | top(10, by=value)`

Evaluation strategy
- Compile to an AST, then to a small plan that the command executes streaming over the parser:
  - When root is `events/<type>`, register a handler only for that type; evaluate predicates per event; project requested columns; stream rows to renderer.
  - For `metadata/chunks/cp`, operate on already-read structures from `JFRSession` providers.

---

## Milestones, Tasks, Acceptance Criteria (updated)

M1 — Session management (DONE)
- [ ] Add `SessionManager` with id/alias, current-session selection.
- [ ] Implement `open`, `sessions`, `use`, `close`, `info` commands.
- [ ] Update `InteractiveJFRShell` to use `CommandDispatcher` and `SessionManager`.
Acceptance: can open multiple files, switch, list, and close; prompts show current alias/id.

M2 — Type discovery refresh + search
- [ ] Extend `JFRSession` to store discovered types and quick refresh.
- [ ] Implement `types [--search] [--refresh]` with glob/regex filtering.
Acceptance: types list stable across sessions; refresh updates immediately.

M3 — Metadata browsing
- [ ] Add `MetadataProvider` to summarize classes, fields, annotations.
- [ ] `metadata` and `metadata class <name>` commands with `--tree|--json`.
Acceptance: can inspect metadata structure and details for any class.

M4 — Chunks + Constant pools
- [ ] Add `ChunkProvider` to summarize chunk headers and relationships.
- [ ] Add `ConstantPoolProvider` to summarize and list entries by kind.
- [ ] Implement `chunks`, `chunk <idx> show ...`, `cp` commands with summaries and pagination.
Acceptance: visible chunk lists and CP summaries on real JFRs.

M5 — JfrPath core (events, filters, projection)
- [x] Define initial AST and parser for v0 grammar (roots, segments, simple filters).
- [x] Implement minimal evaluator for events with type filter and predicates.
- [x] `show` command with `--limit` and table output.
- [x] Attribute projection: `events/<type>/<field/path>` returns only values of the path.
Acceptance: basic examples work; attribute projections display as a single-column table; tests passing.

M6 — Aggregations + pipelines
- [ ] Implement `count`, `sum`, `groupBy`, `top` operators.
- [ ] Render grouped results as table/json.
Acceptance: simple aggregations work; validated on sample datasets.

M7 — UX polish
- [ ] JLine completion for commands, options, session ids/aliases, type names.
- [ ] Built-in pager for long outputs; configurable page size.
- [ ] Contextual `help <command>` with examples.
Acceptance: smooth interactive experience; help texts up to date.

## Progress Log

- 2025-10-01: M1 complete (multi-session, help/completion). JfrPath v0 drafted with parser/evaluator. `show` added with `--limit`. Added attribute projection (`events/<type>/<path>`) rendering single-column values. Contextual `help show` and examples added. All JUnit tests passing.
- 2025-10-01: Improved event detection (recursive superType walk to `jdk.jfr.Event`) so `show events/` completion lists only real events. Added initial non-event show support: `show metadata/<type>` with projection (`/superType`, `/fields`), plus completion for `metadata/` type names. Updated help and tests.
- 2025-10-01: Renamed `types` command to `metadata` (kept `types` as a deprecated alias). Completion and help updated. Default listing shows non-primitive metadata classes, with filters `--events-only` and `--non-events-only`, and a debugging flag `--primitives`. Added `--summary` to print counts only. Header now includes scope and events/non-events breakdown.
- 2025-10-01: Show command: added roots completion `events/`, `metadata/`, `cp/`, `chunks/` (no trailing space) and `chunks/` suggests available chunk IDs (cached). `metadata/` completion suggests all metadata classes.
- 2025-10-01: Metadata view: added annotated `name:Type[]` pairs, multiline non-truncated `fields` column. Enriched metadata with `classAnnotations`, `settings`, and per-field `annotations` (full and simple). Default table hides internal columns (`fieldsByName`, `classAnnotations`, `classAnnotationsFull`, `settings`, `settingsByName`, `fieldCount`). Added `--format json` to `show` to pretty-print full metadata details. Implemented field-level lookup: `show metadata/<type>/<field>` prints that field's metadata (hides `annotationsFull` in table; JSON includes all).
- 2025-10-01: Constant pools: fixed summary counts to reflect real entry counts by reading offsets; `show cp` lists per-type totals. Implemented `show cp/<type>` to list all entries of that CP type by iterating offsets and lazily deserializing entries; supports JSON output.

M8 — Non-interactive subcommands
- [ ] Picocli subcommands for `show`, `metadata`, `chunks`, `types`.
- [ ] Proper exit codes and stdout/stderr behavior; no interactive banner.
Acceptance: usable in scripts; good error messages; CI-friendly.

M9 — Testing & stability
- [ ] Unit tests: JfrPath parser/evaluator; renderers; providers.
- [ ] Integration tests: run commands on sample JFRs (via `get_resources.sh`).
- [ ] Fuzz/edge cases: empty recordings, missing fields, large constant pools.
Acceptance: `./gradlew test` green locally with Java 21 toolchain.

M10 — Docs & packaging
- [ ] Update `JFR-SHELL-USAGE.md` with new commands and examples.
- [ ] Add `docs/jfrpath.md` with grammar and examples.
- [ ] Ensure `shadowJar` builds runnable artifact; update launcher scripts.
Acceptance: end-to-end usage documented and reproducible.

---

## Implementation Notes

- Keep public API minimal inside `jfr-shell`; make helpers package-private; follow Java 21 style.
- Use existing `TypeGenerator` selectively; do not generate types for every custom event by default.
- Evaluate fields using generated types first; fallback to untyped records or reflection for resilience.
- Do not load entire recordings for queries; stream events and apply filters incrementally.
- Renderers should truncate wide columns and support `--no-trunc`.

---

## Testing Plan

- Parser: golden tests for JfrPath strings → AST; error cases with clear diagnostics.
- Evaluator: small synthetic events to validate filters and projections.
- Providers: metadata/chunks/constant pool extraction on small bundled JFRs.
- Commands: end-to-end tests that execute `show`, `types`, `metadata` over the sample JFRs.

Run:
- `./get_resources.sh` to fetch test JFRs into `demo/src/test/resources`.
- `./gradlew test` (parser tests require higher heap; Java 21 toolchain).

---

## Open Questions

- Should we persist session state between runs (recent files, aliases)? Default: no, ephemeral.
- Do we support writing back filtered events to a new JFR? Not planned in v1.
- Where to place non-interactive output files by default? Suggest current directory.

---

## Quick Next Steps (for implementers)

1) Create `io.jafar.shell.core.SessionManager` and wire it into `InteractiveJFRShell`.
2) Introduce `io.jafar.shell.cli.CommandDispatcher` with command registry and help.
3) Implement `open/sessions/use/close/info` commands end-to-end.
4) Add `types` with search and refresh; reuse `TypeDiscovery`.
5) Start `JfrPath` package with tokenizer + minimal parser; integrate `show` with `--limit` and table renderer.

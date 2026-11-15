# Design: live JFR event streaming (`connect` / `tail`) — issue #92

Status: design for Phase 1 (local attach, push tail, per-event filtering), revised
after adversarial review round 1 (docs/scratch/design-review-round1.md — all blocking
and major findings resolved; resolutions marked [R#]).
Scope confirmed with the issue reporter: Phase 1 only; Phases 2 (live aggregation)
and 3 (remote JMX + MCP) are recorded as follow-up issues, not built here.

## Problem

Users want to attach to a running JVM and watch its JFR events live (JEP-349 style),
filtering per event — JFR as structured logging. Today jfr-shell is strictly
file-bound: `JFRSession(Path)` and the `EventSource` SPI both require a finite file.

## Verified platform facts (evidence)

- `jdk.jfr.consumer.EventStream.openRepository(Path)` streams events from a *running*
  target JVM's on-disk repository; usable for external repositories since JDK 17
  (JDK-8262908: subdirectory discovery, partial-chunk tolerance). `openRepository()`
  (own JVM) exists since JDK 14. `openRepository(Process)` **does not exist** — PID →
  repository mapping is ours to implement.
- Repository convention on all three platforms: `<temp base>/<yyyy_MM_dd_HH_mm_ss>_<pid>`
  (Linux/macOS verified by an in-repo reproducer run 2026-11-08; Windows confirmed
  against Oracle's JFR guide example + `jdk/jfr/internal/Repository.java` format).
  The temp base is the HotSpot `os::get_temp_directory()` semantics — hardcoded `/tmp` on
  Linux (a target-side `-XX:AltTempDir` is not discoverable from outside; the
  `--repository` flag covers it), the per-user Darwin temp dir on macOS (HotSpot
  `confstr(_CS_DARWIN_USER_TEMP_DIR)`; `$TMPDIR` primary — it equals the confstr value in
  normal launchd sessions, but HotSpot itself never reads `$TMPDIR` — then this JVM's
  `java.io.tmpdir` default gated to the `/var/folders/` shape, `/tmp` as a documented
  last-resort guess), `%TEMP%` → `%TMP%` → `java.io.tmpdir` on Windows — **not**
  `java.io.tmpdir` in general, which a `-Djava.io.tmpdir` override moves independently.
  [R-M3] [verified-mac]
- `jdk.jfr.repository` is a *system property*, not a perf counter: hsperfdata cannot
  map PID → repository. Attach-free discovery = scan the temp base for
  `yyyy_MM_dd_HH_mm_ss_<pid>` directories and match the trailing PID. `openRepository`'s
  built-in base-dir discovery picks latest-by-mtime, which breaks with multiple JVMs —
  we must pass the exact repository directory.
- Events become visible on flush, including from the in-progress (partial) chunk;
  delivery latency is ~tens of milliseconds (reproducer: 20–50 ms between events).
- The consumer cannot back-pressure the target: the stream reads chunk files the
  target has already written. The real slow-consumer ceiling is **repository wrap** —
  the target deletes/overwrites old chunks — so events can be lost on the target side
  regardless of our queueing. Documented, not fixable from outside. [R-M2]
- Targets recorded with `disk=false` populate the repository only at rotation/dump:
  live tailing degrades to batch-at-rotation. Cannot be detected from outside;
  documentation issue only.
- Target JVM exit removes the repository, which auto-closes the stream (`onClose`
  fires, `awaitTermination` returns). A crashed JVM leaves its repository behind and
  PIDs get reused — so discovery must check liveness, not just directory presence. [R-M4]
- Repo toolchain is JDK 25 everywhere; the JDK 17 API floor is satisfied.

## Requirements

R1 `connect` (no args): list candidate local JVMs — PID, a command-line snippet, and
    whether a JFR repository directory was found for that PID. Unreadable
    (`EACCES`) hsperfdata entries degrade that row to PID-only; they never abort the
    listing.
R2 `connect <pid> [--alias NAME] [--repository <dir>]`: attach — resolve the target's
    repository directory, create a live session, register it in the existing
    `SessionManager` (made current, mirroring `open()`'s alias semantics), and report
    what was attached. `--repository` bypasses discovery (tests, exotic setups).
R3 `tail [filter] [--limit N]`: stream matching events from the live session captured
    at command start until interrupted, the target closes, or N events are emitted.
    Filter is a JfrPath expression (`events/jdk.X[pred] | filter(p) | select(...)`);
    per-event-capable ops only — batch ops are rejected with a clear, testable error
    (that is Phase 2). In script mode `tail` requires `--limit` (output is buffered to
    script end and no interrupt path exists there).
R4 Both UX shapes: interactive commands (readline shell and TUI) and one-shot CLI
    (`jfr-shell ps`, `jfr-shell tail <pid> [filter]`).
R5 File-based workflows unchanged: `open` / `show` / `metadata` / `chunks` / `cp` /
    backend behavior and the `EventSource` SPI contract for file backends are
    untouched for file sessions. A *live* session being current is new state with
    explicitly defined behavior (see "Live session × existing commands").
R6 Graceful interrupt: Ctrl-C ends `tail` without killing the shell (readline), without
    wedging the TUI, and exits **0** in one-shot mode (a real signal handler, not a
    shutdown hook — see Signal handling). Target JVM exit ends `tail` cleanly.

Non-goals (agreed): live/windowed aggregation (Phase 2), remote JMX attach (Phase 3),
MCP tools (Phase 3), `jdk.attach`-based attach as a primary path, writing .jfr files
from `tail`, `show`/query-pipeline support against live sessions (Phase 2 — Phase 1
per-event filtering lives in `tail` only), Windows support beyond best-effort
verification of discovery (same temp-base convention; behavior verified on the
testground where a Windows runner is available, otherwise documented as unverified).

## Architecture

New package `io.jafar.shell.live` in **jfr-shell** (not a backend plugin: the
`EventSource` SPI is file-shaped and the issue triage explicitly rejects forcing a
push stream into it; backends parse finite files, live streaming is a JDK-API consumer).

### Components

1. **`JvmProcesses`** (discovery)
   - `listLocalJvm()`: scan the OS temp base (`/tmp` on Linux, `$TMPDIR` else the gated
     `java.io.tmpdir` fallback on macOS, `%TEMP%`/`%TMP%`/`java.io.tmpdir` on Windows —
     resolved once, documented why) for `hsperfdata_<user>` dirs; for the
     readable ones parse the perf-data prologue/entry structure minimally to extract
     PID (from the filename) and `java.command`. Unknown entries skipped; any parse or
     permission failure degrades that entry, never the listing.
   - `findRepository(int pid)`: match `yyyy_MM_dd_HH_mm_ss_<pid>` dirs against the
     PID, then harden each candidate before accepting it [R-M7]:
     reject symlinks (`Files.isSymbolicLink`), require directory owner == current user,
     cross-check directory mtime against `ProcessHandle.info().startInstant()` where
     available, require liveness (`ProcessHandle.of(pid)` present and alive — stale
     repos of crashed JVMs are common), require chunk-like regular files inside.
     Multiple surviving matches → error listing candidates (ambiguous is safer than
     wrong). Liveness/identity re-checked at attach (TOCTOU window noted, not
     eliminable without attach).
   - No platform-specific branches beyond resolving the temp base.

2. **`LiveJfrSession implements Session`** (jfr-shell-owned)
   - Wraps a started `EventStream` consuming on a dedicated thread into a bounded
     queue of **raw `RecordedEvent`s** (default 1024; conversion is deferred to the
     consumer — converted maps of stack-trace events are orders of magnitude larger
     than the raw event). Slow-consumer policy: **drop-oldest, keep-newest**, drop
     counter visible in `info` and the tail status line. Rationale: tail semantics
     favor the newest events, and the target cannot be back-pressured anyway [R-M2].
     Streams are opened with the default `setReuse(false)`: with reuse enabled the
     JDK rotates a per-type 2-slot `RecordedEvent` cache in place (the javadoc forbids
     keeping a reference past the `onEvent` callback), which would corrupt every
     queued event. Fresh events are safe to retain across threads; only revisit reuse
     if decode-in-`onEvent` is measured to matter. [R-NEW1]
   - `getType()` → `"jfr-live"`; `getFilePath()` → synthetic `Path.of("live-pid-" + pid)`
     (the `Session` interface mandates a path; this renders sensibly in session lists
     and is never opened as a file — guarded commands never touch it).
   - `getAvailableTypes()` populated lazily from `onMetadata` events (API verified
     present on JDK 25).
   - `getStatistics()` → pid, repository dir, events seen, drop count, stream state,
     attach time (rendered by `info`).
   - **Lifecycle** [R-M6]: `close()` is idempotent — stops the consumer thread, closes
     the stream — and does **not** unregister from the manager; unregistration is
     manager-owned (avoids re-entrant double-removal inside `SessionManager.closeById`'s
     monitor). A closed session appends an end-marker to the queue so an in-flight
     `tail` observes closure promptly and exits with a status line.
   - Registration via a new `SessionManager.register(S session, String alias)` that
     mirrors `open()` exactly: alias pre-check and post-create re-check
     (close-on-lost-race included), new session made current [R-m3].

3. **`LiveEventDecoder`** — `RecordedEvent` → `EventSource.Event(typeName, Map)` shape
   compatible with what `JfrPathEvaluator` consumes. The strict field conversion is shared
   with `JdkJfrBackend` (extraction kept byte-identical for the file path). The live
   `decode` additionally: rewrites the built-in `startTime`/`duration` (whose raw flatten
   values are chunk-relative ticks) to absolute epoch nanos / duration nanos, and degrades
   an unconvertible field to a marker instead of failing the stream — the JDK 25 consumer
   throws on `stackTrace`-typed fields for the repo's own fixtures (pre-existing
   `JdkJfrBackend` defect, tracked in a follow-up issue; thread identity needs no special
   handling: `RecordedThread.getFields()` already carries `javaName`/`osThreadId`, which
   corrects the round-1 review's "lossy thread" claim). [R-NEW1] [verified-mac] Called from `TailRunner`, not the queue
   producer.

4. **Per-event filtering** — additions to `JfrPathEvaluator` (shell-core):
   - `public boolean matchesEvent(Query query, Map<String,Object> event)` — evaluates
     `query.eventTypes` (multi-type included) **and** `query.predicates` per event,
     delegating to the existing private `matchesAll`.
   - A per-event pipeline applicator accepting exactly: `filter`, `select`, `len`,
     `upper`/`lower`/`trim`, `abs`/`round`/`floor`/`ceil`, `formatduration`,
     `contains`, `replace`, `asdatetime` (the set `applySingleOp` handles row-locally,
     verified). Anything else (`count`, `sum`, `stats`, `groupBy`, `sortby`, `top`,
     `quantiles`, `head`, `tail`, `distinct`, `toMap`, `timeRange`, `stackprofile`,
     `flamegraph`, `crossref`, `decorate*`, …) throws
     `UnsupportedPipelineOperationException` (new type in `io.jafar.shell.jfrpath`)
     naming the op — the reject list is the test's assertion list. [R-m1]
   - `tail` parses its filter with `JfrPathParser`, requires the `events` root, and
     applies type-match + predicates + per-event ops to each decoded event.
     No filter → all events.

5. **`TailRunner`** — the streaming loop shared by all three surfaces. Inputs: a live
   session (captured at command start; later `use` does not re-target it [R-M6]), a
   per-event matcher, and an `EventSink`:
   - `PrintSink` (readline shell, one-shot CLI): format + println + flush per
     event (readline `IO` already flushes per line). The periodic `listening: …`
     status line goes to stdout in table mode and is suppressed in JSON mode —
     stdout stays pure JSON-lines; the final `tail finished (…)` summary is the
     run's receipt and prints in both modes.
   - `TuiSink` (TUI): does **not** print per event — `io.println` appends to the
     TUI output buffer unboundedly during a run, so instead it keeps a bounded
     ring buffer (last `--limit`, default 500, capped at 10 000) of formatted lines
     and updates the progress-message area periodically (every ~1 s: `N events,
     D dropped`). Wired via the `CommandDispatcher.TuiOutput` marker interface: the
     TUI's `IO` implements the marker, `cmdTail` selects `TuiSink` for marked IOs,
     and the marker survives the dispatcher's error-counting wrapper. At
     completion the ring buffer is printed once via `io.println` and lands in the tab.
     On Ctrl-C cancel, the TUI discards the buffer (existing cancel semantics —
     documented limitation; readline is the streaming surface) [R-M1].
   - Loop: poll queue → decode → match → sink; checks `Thread.interrupted()` and
     `session.isClosed()` between events; exits on end-marker, interrupt, target close,
     or `--limit` reached. Stop-promptly-after-interrupt also avoids widening the
     latent `addOutputLine`/cancel race in the TUI [R-m2].
   - Formats: table line (timestamp, type, key fields) or JSONL via existing
     `--format` conventions.

### `SessionManager` generalization [R-B1]

`Shell`, `Main`, and `JfrTuiAdapter` currently declare `SessionManager<JFRSession>`;
`LiveJfrSession` cannot extend `JFRSession` (its constructor demands a real file and
eagerly scans metadata). Resolution: retype to `SessionManager<Session>` (TuiShell
already uses this type) and narrow to `JFRSession` with `instanceof` where
file-session APIs are needed. Full main-source site list (verified by grep):
- Retype: `Shell.java:30,40`; `Main.java:341–342,634–635`; `JfrTuiAdapter.java:40`
  (its unchecked cast becomes unsafe under `SessionManager<Session>`).
- Narrow: `Shell.executeScript` (`getRecordingPath()`);
  `CommandDispatcher.currentJfrSession()` (already narrows); `ShellCompleter.java:53,61`
  (field/constructor typed `SessionManager<JFRSession>`); `MetadataService.java:20,28`
  (JFRSession-only APIs `getRecordingPath()`, `getAllMetadataTypes()`,
  `getConstantPoolTypes()` — return null/empty for live sessions);
  `JfrModule.java:90` (unchecked cast — must become an `instanceof` check so a live
  session never surfaces as `JFRSession`); `LazyQueryValue.java:39,43`
  (`SessionRef<JFRSession>`, constructed only behind the existing `instanceof` guard
  at `CommandDispatcher.java:2856`).
`CommandDispatcher` already accepts `SessionManager<? extends Session>`. New
`register(S, alias)` on `SessionManager`. Test files construct their own
`SessionManager<JFRSession>` and compile independently — churn is main-source only.
ArchUnit freeze rules re-run after this retyping.

### Live session × existing commands [R-B2]

With a live session current, file-bound commands are **guarded**, not broken:
- `metadata`, `types`, `chunks`, `chunk`, `cp`/`constants`, `backend`, `show`/`select`/
  `events`-root queries, and lazy query variables (`set v = events/...`) →
  "not supported for a live session (use tail)" — one guard at
  `currentJfrSession()`-shaped entry points, before any `getFilePath()` file I/O.
- The `metadata/…` and `constants/…` prefix aliases and the LLM family
  (`ask`/`analyze`/`explain`/`as-query`/`?`) hit the same guard (the LLM path currently
  fails with a bare `IllegalStateException` — the guard replaces that with the
  standard message).
- `help` renders the generic menu for live sessions (`cmdHelp`'s `isJfr` check treats
  a live session as non-file — documented, no change).
- Completion: `MetadataService.getRecordingPath()` returns null for a live session;
  JfrPath/metadata completions degrade to command-name-only (same as no session —
  verified null-safe).
- `info` → live statistics (component 3).
- `sessions` / `use` / `close` → work unchanged (the point of registering).
- `connect` / `tail` → operate on live sessions as designed.
- Session-list renderers display the synthetic `live-pid-<pid>` name; no consumer of
  `getFilePath()` performs file I/O on it once the guards are in.
- `LiveJfrSession.close()` called outside the manager (try-with-resources) would leave
  a manager entry pointing at a closed session — not reachable from any dispatcher
  path today; `sessions`/`info` show closed state. Stated in the class javadoc.

### Surface wiring

- **Readline shell**: `case "connect"` / `case "tail"` in `CommandDispatcher.dispatch`;
  help blocks in `cmdHelp` + `Shell.printHelp()`; names in `CommandCompleter.COMMANDS`.
  A JLine `Terminal.handle(Signal.INT, …)` handler is installed for the duration of
  `tail` (none exists today; today Ctrl-C during a command kills the JVM) and the
  previous handler restored on completion.
- **TUI**: commands stay dispatcher-owned (adapter `dispatch` is a no-op fallback).
  Output via `TuiSink` (above); Ctrl-C already cancels the running command via
  `future.cancel(true)` and `TailRunner` is interrupt-responsive — no TuiCommandExecutor
  changes.
- **One-shot CLI**: new picocli subcommands `ps` (list) and
  `tail <pid> [filter] [--repository DIR] [--limit N] [--format table|json]` in
  `Main.java`. They bypass `executeNonInteractive` (which buffers all output) and use
  `PrintSink` on `System.out`. Backend-validation duplication in `Main`'s
  subcommands is extracted while adding these.
- **Live-streaming capability**: live streaming ships in jfr-shell only — the unified
  `jafar-shell` is an explicit Phase-1 exclusion (issue #92). The unified shell flags its
  dispatcher off (`setLiveStreamingEnabled(false)`), routes `connect`/`tail` to that dispatcher
  so the refusal names the reason (never a bare "Unknown command"), and strips the two words
  from first-word completion — Tab never offers a command the prompt refuses.

### Signal handling [R-M5]

- All three surfaces share ONE mechanism: `TailRunner.installSignal` registers a single
  SIGINT handler via JLine `Signals.register("INT", …)` — a reflective wrapper over
  `sun.misc.Signal.handle` (documented as JDK-internal, accessed reflectively to avoid a
  compile-time dependency) that interrupts the tail thread. The registration object returned
  IS the previously installed handler; `restoreSignal` passes it back to
  `Signals.unregister`, which re-installs it — capture-and-restore of the previous handler
  is preserved (verified against jline 3.21.0).
- A dumb JLine terminal does not intercept SIGINT (jline 3.21.0's `DumbTerminal` never
  registers a native handler — only `PosixSysTerminal` does), so the handler works there
  too: the stream ends and the main thread returns normally → exit 0.
- TUI additionally cancels via `future.cancel(true)` (raw mode keeps ISIG off; Ctrl-C
  arrives as a key event). Shutdown hook kept only as leak protection for non-SIGINT
  exits, not as the Ctrl-C mechanism.

## Risk register

| Risk | Mitigation |
|---|---|
| Repository discovery differs across platforms/configs (`-XX:FlightRecorderOptions=repository=…`) | `--repository` on `connect <pid>` and the one-shot `jfr-shell tail <pid>` (hardened like discovery-found repositories — the escape hatch no longer bypasses the planted-directory defense); discovery failure names the searched temp base and suggests the flag |
| hsperfdata parsing fragile across JDK versions | parser reads only the prologue/entry structure; unknown entries skipped; any failure degrades the row to PID-only, never crashes |
| Unbounded output in TUI | `TuiSink` ring buffer (wired via the `TuiOutput` marker); per-event printing only on line-oriented surfaces |
| Blocking dispatch wedges shell | interrupt-responsive loop + signal handler; TUI already cancels via future |
| Events dropped under slow consumer | bounded raw-event queue, drop-oldest, drop counter in `info` + status line; repository-wrap loss documented as the true ceiling |
| Stale repository / PID reuse | liveness + start-time cross-checks before attach |
| Planted/symlinked repository dirs in shared tmp | symlink rejection, owner check, mtime/start-time cross-check; `EACCES` degrades listing to PID-only (permission behavior verified, not assumed, on the Linux testground) |

## Testing strategy

- Unit: discovery pattern matching + hardening (synthetic dirs: ambiguous PID, stale
  PID, symlink, wrong owner, liveness); hsperfdata parser (fixture bytes, EACCES
  degradation); per-event matcher (accept-list vs reject-list ops, multi-type events);
  `LiveEventDecoder` (RecordedThread fields via a real `RecordingFile` fixture);
  drop-oldest + counter behavior on the bounded queue; `SessionManager.register`
  alias/current semantics; guards ("not supported for live session") per command;
  `LiveJfrSession` end-marker/closure handoff.
- Integration (the R5-critical one): spawn a child JVM with
  `-XX:StartFlightRecording=disk=true` emitting a custom event every ~50 ms, then drive
  one-shot `tail <pid>` end-to-end: events arrive, filter behavior holds, SIGINT exits
  **0** (dumb-terminal handler path), target-JVM kill ends tail with status line.
  Mirrors the triage reproducer.
- TUI: `TuiSink` cap + progress-cadence unit-tested (no new TuiCommandExecutor
  code to test beyond wiring); readline dispatcher cases + help text focused tests.
- Live `getAvailableTypes()` via `onMetadata` covered by the integration test.
- Corrupted-event guard test: with the stream's default `setReuse(false)`, a queued
  `RecordedEvent` decoded after several later events of the same type still carries
  its own values (pins the JDK contract this design depends on).
- Gates: `./gradlew test shadowJar`, spotlessCheck, arch-tests after the
  `SessionManager` retyping.
- Platform verification: macOS (local), Linux + Windows via the workspace testground;
  anything not verified is stated plainly in the PR (R8).

## Documentation

- `doc/cli/Usage.md`: commands, CLI options, examples; `doc/cli/Tutorial.md`: a live
  triage section; note the `disk=false` caveat, repository-wrap loss, the
  `--repository` escape hatch, and guarded commands under a live session.
- Follow-up issues filed for Phase 2 (live aggregation) and Phase 3 (remote + MCP),
  referenced from the PR and this doc.

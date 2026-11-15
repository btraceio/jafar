# Implementation plan: live JFR event streaming (`connect` / `tail`) — issue #92

Companion to `doc/design/jfr-event-streaming.md` (design approved after review rounds
1–2; all blocking/major findings resolved). Plan reviewed for completeness before
implementation. Each task is one commit-sized increment with its own tests; tasks are
ordered by dependency. Verification rule (R5): every task states how the test fails
without the change where applicable, and failure sets are compared **by name** (R6).

## Task 1 — `SessionManager<Session>` generalization + `register` (shell-core, jfr-shell)

Files: `shell-core/.../core/SessionManager.java` (+`register(S, alias)` mirroring
`open()`: alias pre-check, post-create re-check, close-on-lost-race, made current);
retype `jfr-shell/.../Shell.java:30,40`, `Main.java:341,634`, `JfrTuiAdapter.java:40`;
narrow `ShellCompleter.java:53,61`, `cli/completion/MetadataService.java:20,28,44`,
`JfrModule.java:90` (unchecked cast → instanceof), `JfrTuiAdapter.java:40` (same
unchecked-cast treatment — a live session must never surface as `JFRSession` on TUI
paths), `LazyQueryValue.java:39,43` sites.
No behavior change for file sessions.

Tests: new `SessionManagerRegisterTest` (alias conflict, current-pointer, lost-race
close); **before starting**, dump the current `:jfr-shell:test :shell-core:test`
failure set as the R6 baseline; after, compare by name (pure refactor — a compile
failure at a call site is the expected R5 evidence, not a test failure).

## Task 2 — per-event filtering API (shell-core, `JfrPathEvaluator`)

Files: `shell-core/.../jfrpath/JfrPathEvaluator.java` — public
`matchesEvent(Query, Map<String,Object>)` (type + bracket predicates via existing
`matchesAll`), per-event pipeline applicator with the design's accept list
(`filter`, `select`, `len`, `upper/lower/trim`, `abs/round/floor/ceil`,
`formatduration`, `contains`, `replace`, `asdatetime`); new
`io.jafar.shell.jfrpath.UnsupportedPipelineOperationException`.

Tests: `JfrPathPerEventTest` — accept-list ops work per event; every reject-list op
asserted to throw (the reject list is the assertion list); multi-type
`events/a|b` matching; no filter = match-all. Fails-without: reject-list cases fail
(batch ops silently List-typed today).

## Task 3 — discovery (jfr-shell, `io.jafar.shell.live.JvmProcesses`)

`listLocalJvm()`: temp base resolved once (`/tmp` on POSIX, `%TEMP%`/`%TMP%` on
Windows); `hsperfdata_<user>` scan; minimal perf-data parser extracting `java.command`;
EACCES/parse failures degrade row to PID-only. `findRepository(pid)`:
`yyyy_MM_dd_HH_mm_ss_<pid>` match + hardening (symlink reject, owner==current user,
mtime vs `ProcessHandle.info().startInstant()`, liveness, chunk-like files);
multiple survivors → error naming candidates.

Tests: `JvmProcessesTest` with synthetic temp trees (ambiguous, stale-PID, symlink,
wrong-owner, dead-PID; enumerated hardening assertions per case); parser fixture bytes
incl. truncated/foreign entries **and an unreadable entry (EACCES) degrading to
PID-only**; hardening cases each asserted. Fails-without: stale/symlink cases pass
unguarded discovery.

**Seam (blocks Task 5):** `JvmProcesses` is not a static utility. New interface
`io.jafar.shell.live.LiveStreams { List<JvmProcess> listLocalJvm();
LiveJfrSession attach(int pid, Path repository, String alias); }` with the real
implementation delegating to `JvmProcesses` + `LiveJfrSession`; `CommandDispatcher`
gains a settable `LiveStreams` (default real, settable before any `connect` dispatch).
`LiveJfrSession` itself takes an `EventStream` factory (opened on a repository dir)
so tests can drive it from a scripted fake stream without a target JVM. [R-seam]

## Task 4 — `LiveJfrSession` + `LiveEventDecoder` (jfr-shell)

**Task 4a (first commit, behavior-preserving refactor, R10):** extract
`convertEvent`/`convertValue` from `JdkJfrBackend.JdkEventSource` into a reusable
decoder the backend now calls — `:jfr-shell-jdk:test` green by name, no behavior
change. Then **Task 4b**:

`LiveJfrSession`: dedicated consumer thread → bounded raw-`RecordedEvent` queue
(1024), **drop-oldest** + drop counter; `setReuse(false)` explicitly (JDK contract:
reuse rotates a per-type 2-slot cache in place — pin with a test); idempotent
`close()` (no unregister — manager-owned), end-marker on close;
`getAvailableTypes()` from `onMetadata`; `getStatistics()` (pid, repo dir, events
seen, drops, state, attach time); `getFilePath()` = `Path.of("live-pid-" + pid)`.
`LiveEventDecoder`: extracted from `JdkJfrBackend.convertEvent/convertValue` +
`RecordedThread` → `javaName`/`osThreadId`, `startTime`/`duration` decoration.
EventStream opened on the resolved repository dir; `onClose` → session close.

Tests: decoder vs real `RecordingFile` fixture (thread fields asserted);
drop-oldest+counter; end-marker closure handoff; reuse-off retention pin;
`register`-round-trip through the manager; **live `getStatistics()`/`info` content;
`getAvailableTypes()` from a scripted fake stream's `onMetadata` (unit-level, not
IT-only)**. Fails-without: drop policy and thread-field tests fail against unmodified
code paths.

## Task 5 — `TailRunner` + interactive commands (jfr-shell)

`TailRunner` (queue poll → decode → match → sink; interrupt/close/end-marker/limit
exits) with `PrintStreamSink` (per-event println+flush, table/JSONL formats) and
`TuiBufferSink` (bounded ring buffer, periodic progress message via
`System.err` → `ProgressCapturingStream`, single completion print). Dispatcher cases
`connect` / `connect <pid> [--alias] [--repository]` / `tail [filter] [--limit]
[--format]`; help in `cmdHelp` + `Shell.printHelp()`; names in
`CommandCompleter.COMMANDS`. Live-session guards ("not supported for a live session
(use tail)") over `metadata/types/chunks/chunk/cp/backend/show/select/events-root/LLM
family/prefix aliases`. Readline: JLine `Terminal.handle(Signal.INT)` installed for
the duration of tail, previous handler restored after; tail requires `--limit` in
script mode; `tail` binds to the session captured at start.

Tests: dispatcher cases via the Task-3 `LiveStreams` seam (fake discovery + fake
session: connect list/attach/error paths, **`--repository` bypass path,
discovery-failure message naming the temp base and suggesting `--repository`**); tail
against a scripted fake session — no real JVM needed; guard matrix per command
(incl. **`info` showing live stats and the drop counter; `use` mid-tail does not
re-target; closing the captured session terminates tail**); help text assertions;
sink unit tests (ring cap, cadence); script-mode `--limit` requirement. Fails-without:
guards matrix fails (live session today → NPE/"No session open"). Readline signal
install/restore is not unit-testable — covered by the Task 7 R1 manual step.

## Task 6 — one-shot CLI (jfr-shell, `Main.java`)

**Task 6a (first commit, behavior-preserving refactor, R10):** extract the
backend-validation snippet duplicated across `Main`'s subcommands; existing subcommand
tests green by name. Then **Task 6b**:

Picocli subcommands `ps` and `tail <pid> [filter] [--repository DIR] [--limit N]
[--format table|json]`; bypass `executeNonInteractive` buffering (direct
`PrintStreamSink`); SIGINT via `sun.misc.Signal` primary (JDK-internal documented),
JLine system-terminal handler only when a real interactive terminal exists; shutdown
hook as leak protection only; target-exit → status line, exit 0; extract the
duplicated backend-validation snippet while touching `Main`.

Tests: `MainTailCliTest` — arg parsing, filter plumbing, signal-handler install/restore
seam (handler object injected for testability); exit codes. Fails-without: new
subcommands absent (picocli fails on unknown subcommand).

## Task 7 — integration test (the R5-critical one)

`LiveTailIT`: spawn child JVM `-XX:StartFlightRecording=disk=true,dumponexit=true`
emitting a custom event every ~50 ms; drive one-shot `tail <pid>` (and `connect`-less
`ps` listing contains it): events arrive with correct values; JfrPath filter drops
non-matching; SIGINT → exit 0 within a bound; child kill → clean status-line exit.
Also covers live `getAvailableTypes()` (metadata path). Skipped-with-reason when no
attachable JVM can be started (documented, per R8). macOS first; Linux/Windows via
the workspace testground; unverified platforms stated plainly in the PR.

**R1 (type it into the built artifact), Task 7 gate additions:** build the shadow jar
and drive from a terminal: `jfr-shell tail <pid> "events/..."` one-shot with SIGINT →
exit 0; readline `connect` → `tail` → Ctrl-C → prompt intact, handler restored
(second Ctrl-C during another command must still work); TUI `connect`/`tail` cancel
behavior; run with the plugin-dir/backend overrides from Verification.md so the
released-plugin path is exercised.

## Task 8 — docs, follow-ups, PR

`doc/cli/Usage.md` (commands, CLI options, examples), `doc/cli/Tutorial.md` (live
triage section; `disk=false` caveat, repository-wrap loss, `--repository` escape
hatch, guarded commands). Design doc already in `doc/design/`. Follow-up issues for
Phase 2 (live aggregation) and Phase 3 (remote JMX + MCP) filed via the gh shim and
referenced from the PR. **`jafar-shell` (unified shell) is explicitly out of scope
for Phase 1 — the feature ships in jfr-shell; exclusion stated in the PR** (scope
confirmed with the reporter). PR body follows the pr-writing SKILL three-section
structure (Summary / Validation with R5 failing-without evidence / Not-verified,
listing: TUI cancel-discards-buffer behavior, readline signal install/restore beyond
the R1 manual step, Windows/Linux testground outcomes whichever they are), plus
`Closes btraceio/jafar#92`, AI label, `Parity: not applicable — no untyped-parser
change`, and `scripts/check-doc-links.sh` clean. Commits: one per task (4a, 4b, 6a,
6b separate), concise messages, SSH-signed; rebase on `origin/main` before push
(`--force-with-lease`); Draft PR.

## Explicit non-goals (from the confirmed scope)

Live aggregation/windowing (Phase 2), remote JMX attach (Phase 3), MCP tools
(Phase 3), `show`/query-pipeline over live sessions, `jdk.attach` as primary attach,
writing .jfr from `tail`.

## Verification matrix (task → proof)

| Task | Focused suite | Regression gate |
|---|---|---|
| 1 | `SessionManagerRegisterTest` | `:jfr-shell:test :shell-core:test` by-name diff |
| 2 | `JfrPathPerEventTest` | `:shell-core:test` |
| 3 | `JvmProcessesTest` | `:jfr-shell:test` |
| 4 | live session/decoder tests | `:jfr-shell:test` |
| 5 | dispatcher/guard/sink tests | `:jfr-shell:test` |
| 6 | `MainTailCliTest` | `:jfr-shell:test` |
| 7 | `LiveTailIT` + R1 shadow-jar manual steps | full `./gradlew test shadowJar` + spotlessCheck + arch-tests + `scripts/check-doc-links.sh` |
| 8 | docs links check | full build |

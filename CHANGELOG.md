# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **`Values.resolvedDeepCollapsed(Map)` and `ComplexType.isSimpleType()`** (#108). Resolves
  constant-pool references to JFR simple types (`jdk.types.ThreadState`, ...) to their scalar, as
  the typed API does, decided by the recording's metadata rather than a `size() == 1` guess.
  `resolvedDeep` and `getValue()` are unchanged
- **`jafar-shell` scripts across formats.** The unified shell now has the full set of variable and
  scripting commands `jfr-shell` has: map literals (`set cfg = {"min": 1024}`), `merge(...)`,
  query-valued variables, `if`/`elif`/`else`/`endif`, `script <path> [args]` (`$1`,
  `${2:-default}`, `$@`), `invalidate`, `vars --info`, and session-scoped versus `--global`
  variables. A query variable is written in the current session's language — JfrPath, HdumpPath, or
  the pprof/OTLP samples grammar — run on first use by that module's query evaluator exactly as
  `show` runs it, cached, and bound to its session, so one script can hold a JFR count and a heap
  dump count and branch on both. `show ${var}` prints a query variable's rows, `${var}` works inside
  queries (including `--limit ${n}`), the prompt shows `if` nesting depth, and tab completion
  offers these commands and their arguments in every session type. A query variable whose session
  has been closed reports that when read instead of returning stale rows, and closing a session
  drops the cached rows of variables bound to it; scripts nest up to 16 deep, and each script has
  its own `if` blocks: one it leaves open is closed and reported, and a stray `endif` is an error in
  the script instead of closing the caller's block. A script line that reports an
  error stops the script (as the default stop-on-error mode always promised), instead of being
  counted as a success. The parsing, scoping and
  condition rules are one implementation in `shell-core` (`io.jafar.shell.core.scripting`), used by
  both shells. See [Scripting in jafar-shell](doc/cli/Scripting.md#in-jafar-shell)
- **`JFR_SHELL_VERBOSE` / `-Djfr.shell.verbose=true` applies to `jafar-shell script`** as it does
  to `jfr-shell script`: the open/close lines and `Set x = ...` confirmations script mode leaves
  out are printed. The switch is jfr-shell's own (`CommandDispatcher.isVerboseEnabled`), read by
  both
- **Query variables on non-JFR sessions in the TUI.** `set x = <query>` on a heap dump, pprof or
  OTLP session in `--tui` mode used to answer "Variable assignment requires a JFR session"; it now
  creates a lazy variable run by that module's evaluator

### Fixed
- **JFR constant-pool strings print as strings in CSV and JSON, and in every `jafar-shell`
  format.** Result values that the parser hands over as single-entry constants printed as their
  wrapper — `{name=G1New}`, `{cause=G1 Evacuation Pause}`, `{string=java/lang/Object}`: in
  jfr-shell's `--format csv` and `--format json` (`"name": {"name": "G1New"}`), and in every
  `jafar-shell` format, so `gc-analysis.jfrs` and `thread-profiling.jfrs` grouped and listed by
  `{name=G1New}` there. Both shells now unwrap those cells the way jfr-shell's table always has,
  for the results of `events/` and `constants/` queries, including `events/<type>/<field>` value
  lists. `metadata/` and `chunks/` output is unchanged, since a single-entry map there is data
  (`fieldsByName` of a one-field type such as `jdk.types.Symbol`), and so are heap dump, pprof and
  OTLP output and the rows passed to `explain`/`ask`. A script that read `.name.name` or
  `.key.string` from `--format json` output reads `.name` and `.key` now
- **The public untyped parser now honours `UntypedStrategy`** (#124). `UntypedJafarParserImpl.run()`
  always built the generic `EventStream`; the generated, strategy-aware `EventStreamGenerated`
  was reachable only from tests and benchmarks. It is now selected through an
  `UntypedEventStreamFactory` SPI (ServiceLoader) whenever `parser-codegen` is on the classpath,
  which was safe to do only after the generated path was made to match the generic one. Without
  `parser-codegen`, or with `-Djafar.untyped.generated=false`, the generic reader is used as
  before. `UntypedStrategyDispatchTest` observes which reader ran
- **An `if`/`elif` condition that fails to evaluate no longer runs the `else` branch.** Both
  shells printed `Condition evaluation failed: ...` and treated the condition as `false`, so the
  `else` branch ran; in `jfr-shell` the script even ended `Script executed successfully` with exit
  status 0. A failed (or missing) condition is now an error of that line in both shells: no branch
  of the if-block runs (its `endif` still closes it), a script stops there with a non-zero exit,
  and with `--continue-on-error` it resumes after the `endif` with the error counted
- **`jfr-shell` scripts: stray keywords, missing `endif`, and leaking blocks.** `elif`/`else`/
  `endif` without an `if` were printed and counted as successful commands, and a script ending
  inside an `if` was silently accepted; both now fail the script. A script also runs with an
  if-block stack of its own (as in `jafar-shell`), so one that stopped inside a block no longer
  leaves the interactive shell silently skipping everything typed afterwards, and a stray `endif`
  in a script cannot close one of the caller's blocks
- **The `jfr-shell` TUI shows an open if-block.** The command input's title reads
  `Command ...(1, skipping until endif) >` inside a block that is skipping, as the CLI prompt shows
  `...(1)>`; commands typed there used to produce no output and no sign why
- **`Control.abort()` stops the whole parse.** The abort flag was per thread, and chunks are
  parsed on parallel threads, so an abort stopped only its own chunk: on the two-chunk
  `tck-test.jfr` a handler aborting at its 5th event received 40,063 events with a multi-thread
  pool, and 6 with a one-thread pool (the next chunk delivered one event before checking the
  flag). The flag is now shared by every chunk of a run and checked at chunk start, at the
  metadata and checkpoint callbacks (so a chunk mid-way through its constant pools stops there),
  and before an event is decoded,
  in the typed parser and both untyped event streams; the untyped parser also stops calling the
  remaining handlers of an event once one aborts, as the typed parser always did. The contract,
  including the at-most-one in-flight event per other thread, is in the `Control.abort()` Javadoc
- **A failed parse says why.** Chunks are parsed on worker threads, so the cause reached the caller
  several wrappers deep, and both shells print only the outermost message: every corrupted
  recording was reported as a bare `Error occurred while parsing JFR recording`. The message now
  carries the cause, for example `Error occurred while parsing JFR recording: Constant pool
  jdk.types.Symbol, index 16778023: Unexpected string constant id: 127`. The prefix is unchanged
- **`empty()` in script conditions no longer reports every map as empty.** `if empty(m)` was
  true for any map variable — `set m = {"a": 1, "b": 2}` or a `toMap()` query result — because
  the condition evaluator handled only scalars and query results and treated every other value
  as empty, so `if empty(m)` took the empty branch while `${m.size}` printed `2`. A map is now
  empty only when it has no entries, in both jfr-shell and jafar-shell (which share the
  evaluator)
- **`exists()` and `empty()` resolve references the way `${...}` does.** A dotted argument such
  as `empty(cfg.db.host)` was looked up as one variable literally named `cfg.db.host`, which never
  exists, so `empty()` was always true and `exists()` always false while `${cfg.db.host}` printed
  `localhost`; an indexed argument (`exists(rows[1].thread)`) or a hyphenated setting
  (`exists(llm.base-url)`) did not parse at all, and the condition took the `else` branch. The
  argument now uses the same grammar and resolution as `${...}`: nested map fields, `[idx]` rows,
  `.size`, and first-row fields of query results. `llm.*` settings (case-insensitive, hyphens
  allowed) are checked as settings and never split into a variable plus a field, and a malformed
  reference (`cfg..db`, `rows[x]`) is an error instead of silently empty
- **`empty(name)` of a `jafar-shell` `toMap()` variable over no rows is true.** `jafar-shell`
  keeps a `toMap()` query lazy, and its result is one row holding the map, so `empty()` counted
  that row and answered false for a `toMap()` that matched nothing, while `${v.size}` (below) said
  0. It now asks the map, as `jfr-shell` does for the map it stores
- **`${v.size}` of a `toMap()` variable in `jafar-shell` counts entries, as in `jfr-shell`.** The
  unified shell keeps every query variable lazy, and `toMap()` returns one row holding the map, so
  `${v.size}` answered 1: `map-operations.jfrs` printed `GC stats entries: 1` in `jafar-shell` and
  `2` in `jfr-shell` on the same recording. A query ending in `toMap()` is now read as its map —
  `.size`, `.key`, `${v}` — while staying lazy. Whether a query produces a map is decided from the
  query (`QueryEvaluator.producesMap`, one definition shared with `jfr-shell`'s `set`), not from
  the rows: a `top(1)` result is also one row holding a map, and its `${v.size}` stays 1
- **MCP server no longer loses a response when two are sent at once.** The stdio transport emitted
  into its Reactor sinks from whichever thread finished a request, and a sink accepts only one
  producer at a time: a concurrent emit failed with `FAIL_NON_SERIALIZED`, was logged as "Failed
  to enqueue message", and the response was dropped. The client then waited for its timeout.
  Two tool calls in flight together were enough to trigger it. Emission into both sinks is now
  serialized, so every response is written
- **`jafar-shell script <file>` runs in jafar-shell.** On the command line it was handed to
  jfr-shell, which reads only JFR recordings, so a script that opened a heap dump failed with
  "Unknown root: objects". It now runs in the unified shell with jfr-shell's arguments (`-` for
  stdin, positional `$1...`, `--continue-on-error`, `-b/--backend`), quiet output, errors on
  stderr and exit status 1 on failure. It needs no terminal and leaves the interactive history
  untouched. Tables use jafar-shell's layout; a JFR-only script gets the unified shell's semantics
  (the same data in another table layout; see
  [Scripting in jafar-shell](doc/cli/Scripting.md#in-jafar-shell)),
  and `jfr-shell script` keeps jfr-shell's. The `jfr-shell` jbang alias launches jfr-shell directly
  and is unaffected
- **`record` in `jafar-shell`.** `record start [path] | stop | status` saves the commands typed
  at the prompt as a script, for any format, to `~/.jafar-shell/recordings/` by default; the
  recording is saved on exit, and `jafar-shell script <file>` replays it. It is jfr-shell's
  recorder, moved to `shell-core` and shared. It leaves out `help` and `exit`/`quit` as jfr-shell
  does, and a `record` line in an `if` branch that is not running; unlike jfr-shell it keeps
  `script ...`, since the lines a script runs are not recorded and a replay would otherwise skip it
- **`session <id>` in `jafar-shell`**, jfr-shell's alias for `use`, which tab completion offered on
  a recording and the shell refused as an unknown command. Bare `open`, `use`, `session` and `show`
  print their usage instead of "Unknown command"
- **`metadata`, `chunks`, `chunk`, `constants`/`cp` and `backend` work at the `jafar-shell`
  prompt** on a recording, using jfr-shell's implementations; they were taken as malformed queries
  (`metadata --summary` failed with `Trailing characters at position 9`, so `jfr-shell`'s
  `basic-analysis.jfrs` example did not run in `jafar-shell`). In a heap dump, pprof or OTLP
  session, or with no session open, they say they need a JFR session
- **A script line that reports an error fails, in both shells.** A bad query, a missing session or
  file, `use` of an unknown session and the like are reported by printing them, and `ScriptRunner`
  only saw exceptions and unknown commands, so it counted those lines as successes: the script ran
  on past the error, ended with `Script executed successfully`, and `jfr-shell script` exited 0.
  `--continue-on-error` therefore had nothing to do for most failures. Each shell now records the
  errors it reports (`ScriptRunner.ErrorTally`), and a line that reported one fails — fail-fast by
  default, listed under `Script completed with errors:`, exit status 1 — which is what the scripting
  guides already described. This applies to `jfr-shell script`, `script` at either prompt, and
  `jafar-shell script`, and to `ask`/`as-query`/`explain`/`llm`, whose failures (no backend, no
  session, no query in the reply, a query that failed, nothing to explain) now go through a new
  `LlmCommands.Host.error`; `llm status` and dry runs are not failures. A script that relied on running past a failing line needs
  `--continue-on-error` (command line) now
- **`jafar-shell -f script` opens a file named `script`.** The value of `-f`/`--file` (and of a
  cluster such as `-qf`, and of `--backend`/`--plugin-dir`/`--install-plugin`) was taken for the
  first word of the command line, so it ran the script command, or jfr-shell's command line for
  `-f show`. The command is now the first word that is not an option or an option's value
- **Shebang scripts run.** The documented `#!/usr/bin/env -S jbang jfr-shell@btraceio script -`
  never worked as an executable: the kernel runs `... script - ./analyze.jfrs app.jfr`, so `-` read
  stdin (the terminal, or nothing) and `$1` was the script's own path — piped in, `open $1` opened
  the script as a recording. When the path is `-` and the first argument is a file starting with
  `#!`, both `jfr-shell script` and `jafar-shell script` now run that file with the remaining
  arguments as `$1`, `$2`, ...
- **Metadata type lists of multi-chunk recordings could be corrupted.** Chunks are parsed in
  parallel and `JFRSession` filled its type sets from several threads at once without a lock, so a
  set could lose entries from its size count and later fail with "Index N out of bounds for length
  N" (seen as an intermittent `metadata --summary` failure); per-event type counts were updated the
  same way, and the chunk-id and constant-pool-type scans collected from the chunk threads into an
  `ArrayList` and a `HashSet` (chunk ids could also come back out of order). All are now
  thread-safe, and chunk ids are sorted
- **A lazy JFR variable read while another thread invalidated it could return `null`.**
  `LazyQueryValue` kept "evaluated" and the rows in two separate volatile fields, so a read could
  see the flag of one state and the rows of the next; `copy()` could do the same. The cache is now
  one field, and an `invalidate()` or `release()` made while an evaluation is running is no longer
  undone when that evaluation finishes (it publishes only if nothing replaced the slot it started
  from). Shells dispatch commands on one thread, so this needed concurrent access to a variable to
  show
- **`elif` conditions are not evaluated when their branch cannot run**, in both shells. After a
  taken branch, or inside a skipped block, an `elif` was still evaluated: it could run an
  expensive lazy query (a heap dump `objects | count`) for nothing, or print "Condition evaluation
  failed" for a branch that was never going to execute
- **Integers in map literals are integers.** `set cfg = {"port": 5432}` stored `5432.0`, so
  `${cfg.port}` printed `5432.0` and `merge()` results carried doubles, in both shells. A
  conditional expression in the map parser promoted the `long` branch to `double`. Decimals are
  unchanged
- **The shipped example scripts run.** `gc-analysis.jfrs` and `thread-profiling.jfrs` used
  `top(N, by=sum(field))`, which JfrPath does not accept, and `thread-profiling.jfrs` grouped
  `jdk.ThreadAllocationStatistics` by a field it does not have; `map-operations.jfrs` echoed a
  `${...}` it never defined. The same queries were corrected in the scripting, script-execution and
  command-recording guides
- **Published fat jars no longer arrive twice on Maven Central.** Every shadow-jarred module
  (`jafar-shell`, `jfr-mcp`, `jafar-tools`, `jafar-parser`) published the identical fat jar as both
  the main artifact and the shadow plugin's `-all` classifier — measured `0.28.0` cost of that
  was about 77 MiB per release (the shell artifact published ~180 MB per release in total). The
  publication now carries one jar: the `-all` variant stays only where it is a genuinely
  different artifact (a self-contained installer jar for the LLM plugin), never a duplicate of
  the main one
- **The bundled plugin catalog is actually used now.** `PluginRegistry.get` never looked at the
  catalog bundled in the jar — the advertised offline fallback was dead data, so an offline user
  could not install backends at all unless the remote registry fetch happened to succeed. `get`
  now falls back to the bundled catalog, and `canInstallOffline` answers the ask-time install
  gate without any network
- **The plugin system resolves a plugin jar's Maven dependencies.** The installer used to
  download exactly one jar per plugin, silently assuming every plugin is dependency-free; a thin
  jar depending on a real library (now possible: `llm-anthropic` is a plugin) would fail at first
  use with `NoClassDefFoundError`. The installer now collects the dependency graph of the
  plugin's POM (compile and runtime scopes, nearest-wins conflicts, BOM-managed versions) and
  copies every dependency jar into the plugin's storage, where the plugin classloader already
  looks


### Security
- **`llm.api-key` is no longer written or shown in full.** In both shells and the TUI: a line
  setting it is kept out of the history files (`~/.jfr-shell/history`, `~/.jafar-shell/history`),
  and a key line saved there by an earlier version is removed at the next start; a command
  recording writes it as a comment without the value; `set`'s confirmation, `vars` and
  `vars --info` show `<hidden, ends in "TAIL">`. The stored value is unchanged

### Changed
- **`llm.*` settings in `jafar-shell` are scoped like other variables**, as they are in
  `jfr-shell`: set while a session is open they belong to that session; `set --global` keeps them
  for the whole shell. The LLM commands read the session's value first, then the global one
- **`CommandRecorder` moved** from `jfr-shell`'s `io.jafar.shell.cli` to `shell-core`'s
  `io.jafar.shell.core.scripting`; jfr-shell's recordings are unchanged
- **`ScriptRunner`, `VariableSubstitutor`, `ConditionEvaluator` and `ConditionalState` moved** from
  `jfr-shell`'s `io.jafar.shell.cli` to `shell-core`'s `io.jafar.shell.core.scripting`;
  `ScriptRunner` now takes a line dispatcher (`dispatcher::dispatch`) instead of a
  `CommandDispatcher` and an unused `IO`
- **Javadoc jars drop ~4 MiB each.** They were up to 90% javadoc's own bundled DejaVu web
  fonts; excluded at jar-packaging for every published module's docs
- **The MCP server jar drops from 38.2 MiB to 13.2 MiB.**** Four cuts, all content-level (the jar
  stays self-contained for `java -jar` and the E2E harness): the heap tools depend on
  `it.unimi.dsi:fastutil-core` (the subset fastutil itself publishes — the full jar was 51% of
  the server jar); the embedded fastutil copy is then trimmed to the exact static closure of
  what the embedded code loads, derived by jdeps at build time over the runtime classpath and
  closed by actually loading every kept class and walking its hierarchy, field types and method
  signatures (first-generation trims missed the superclass and inner-class chains and
  `NoClassDefFoundError`'d in `hdump_open`/`hdump_summary`; the fixpoint derives on every build,
  so a newly referenced type cannot ship missing — the four-format E2E is the runtime net); the
  shells' jline terminal stack, which nothing the server loads references (its tools use the
  engine classes, and the `ShellModule.getCompleter` adapters are never initialized there); and
  the Maven resolver stack, which stays out of the server because the plugin install machinery
  inside `shell-core` now loads lazily — backend discovery only reads the plugin directory.
  jetty (the `--attach` SSE bridge) and the MCP SDK's jackson/reactor/schema stack remain by
  design
- **No LLM backend ships inside the shell.** The Anthropic backend's SDK alone outweighed the
  rest of the release jar; the OpenAI-compatible backend (`openai` and `ollama` — two profiles
  of one small JDK-HttpClient adapter) never needed the jar at all. Both are standalone
  artifacts on the llm releases now (`llm-anthropic`, `llm-openai`), cataloged and installed at
  the first explicit use: select a backend (`set llm.backend = anthropic | openai | ollama`)
  and the first `ask` prints the same "Downloading from Maven repositories..." step the JFR
  backend selection already has; `llm.backend = auto` never installs anything; air-gapped
  machines install from files (`--install-plugin`, recipe in doc/cli/LlmSetup.md). The
  no-backend configuration remains fully supported — the SPI degrades to a clear message.
  `jafar-shell` drops to a 9.3 MiB artifact whose manifest's `Embedded-Modules` line names every
  io.btrace module and version the build embedded
- **Release versioning is tag-derived** — the project version comes from git tags
  (`gradle/version-from-tag.gradle`), not from a number baked into `build.gradle`. A build exactly
  on `vX.Y.Z` reports `X.Y.Z`; any other build reports `<newest vX.Y.Z tag>-SNAPSHOT`. Releasing
  means cutting a tag; the manual version bumps in `build.gradle`,
  `jafar-gradle-plugin/build.gradle`, the post-release SNAPSHOT bump and the pre-tag
  `jfr-shell-plugins.json` edit are all gone — the release workflow commits the plugin catalog
  update to `main` itself

### Added
- **`ask` — an LLM inside the shell** (`llm-anthropic` and `llm-openai` modules,
  `io.jafar.shell.core.llm` in `shell-core`)
  - `ask <question>` — shortcut `?`, word aliases `analyze` and `investigate` — runs several
    queries, reads each result and concludes; `as-query <question>` is the one-shot form, which
    turns a question into a single query, **prints it**, and runs it. `explain` describes the last
    result; `llm status` and `llm cost` cover setup and cost. Every verb takes `--dry-run` to print
    what would be sent without sending it
  - Wired into `jfr-shell` (JFR recordings) and the unified `jafar-shell`, which is the entry point
    that opens all four formats — they use whichever language the current session needs:
    JfrPath, HdumpPath, or the shared pprof/OTLP samples grammar
  - **Three backends, no privileged provider**: `anthropic` (Anthropic Java SDK), `openai` and
    `ollama` (OpenAI chat-completions over the JDK HTTP client, no provider SDK). `llm.backend`
    selects one; `auto` takes the first that reports ready. Each supplies its own default model, so
    there is no cross-provider default to get wrong
  - **`llm.base-url` reaches anything that speaks the same protocol** — vLLM, LM Studio, Groq,
    Together, OpenRouter, Ollama Cloud — without new code. A loopback endpoint is probed with
    `GET /models` so `llm status` says "reachable" or "cannot reach" instead of hanging later
  - **A local model means nothing leaves the machine.** `set llm.backend = ollama` and the question,
    the type names and the result rows all stay on loopback — the configuration for recordings you
    did not produce, and for environments where a hosted call is not allowed
  - **Both Anthropic authentication modes come from the SDK**: `ANTHROPIC_API_KEY`, or a keyless
    OAuth profile written by `ant auth login`. Jafar adds no auth code, only diagnostics — the SDK
    does not fail fast when credentials are missing, so `llm status` reports which source wins and
    catches the three traps (a stale key shadowing a profile, an empty-but-set key, both credentials
    at once). The OpenAI-compatible backends send no `Authorization` header at all when there is no
    key, because an empty bearer breaks several local servers
  - **The output ceiling adapts to reasoning models.** `llm.max-tokens` stays at 2048 — the size of
    an answer, and the cap on a runaway — but a model that reasons before answering spends that
    budget thinking, hits the ceiling mid-thought and returns no query, billing the full amount for
    nothing. When a reply stops on its token limit without a query, the shell raises the ceiling to
    16384, says so, retries, and remembers it for that model for the session. The trigger is the
    reply's stop reason rather than a list of model names. A ceiling you set yourself is never
    lowered
  - **A failure to find a query now says why.** Both backends read `finish_reason` and nothing
    consumed it, so a reply truncated mid-thought reported only "No query could be extracted from
    the model's reply" — with the token count that would have explained it sitting in the same
    output
  - **A generated query is validated before it runs**: parsed with the same parser that would
    execute it, and on rejection the parser's own error goes back to the model with a request to
    correct itself (`llm.max-retries`, default 1, capped at 3). `ask` prints the correction count
    with the token usage. This is what makes a small local model usable for the job
  - **`ask` no longer offers event types that hold no events.** JFR metadata declares every type the
    JVM registered, so a recording produced with an agent that ships its own sampler lists an empty
    `jdk.ExecutionSample` beside a vendor type carrying thousands of events — and a model told only
    the names picks the one it recognises and queries nothing. Events are now counted once per
    recording, cached across sessions under `$XDG_CACHE_HOME/jafar/event-counts` (keyed on path,
    size and modification time), and types with no events are collapsed into a single line the
    model is told not to query. The prompt also states that a type's package says nothing about its
    relevance. `llm.count-events = false` skips the pass
  - **`ask` tells the model what each event type is for.** A recording documents itself — JFR puts
    `@Label` and `@Description` on event classes — and that text is now sent with the type list, so
    a type is chosen on meaning rather than on a name that shares a word with the question. It sits
    in the cached prompt prefix, being fixed per recording, and stays inside the recording-data
    fence: a custom type is labelled by whoever produced the recording. Event counts are not
    included, because computing them means scanning the recording and `ask` is deliberately
    independent of recording size
  - **`analyze` runs the analyses, not just queries.** `ANALYSIS: diagnose` (also `use`, `tsa`,
    `summary`, `hotmethods`, `exceptions`) reaches the same implementations the MCP server exposes,
    so the model gets the USE and TSA passes, the thresholds and the capability gaps instead of
    rebuilding that judgement out of queries
  - **Fixed: the untyped parser's string wrapper was being redacted wholesale.** A string constant
    arrives as `{string=[B}`, and `string` is in the default redact list — so every wrapped
    constant reaching the model was replaced, class names and group-by keys included, while the
    redaction looked like it was working. The wrapper is now unwrapped before the decision, which is
    taken on the real field name; a wrapped value under a genuinely redacted field is still redacted
  - **`ask <question>` — an investigation, not a translation.** `as-query` turns a question into
    one query; `ask` runs several, reads each result and decides what to look at next, then
    concludes. Every query is printed as it runs, with the rows it returned underneath — the same
    rows the model was given, capped at `llm.max-rows` — and the sequence is written to a
    re-runnable `.jfrs` transcript, so a conclusion produced by a model leaves behind evidence a
    human can check. A following `explain` describes the last result it looked at. Bounded by
    `llm.max-steps` (6) and `llm.max-total-tokens` (200000); rows are redacted and truncated on
    every step, and `llm.confirm` turns it off, since a loop cannot show a query it has not decided
    on yet. It speaks the same line-prefixed text protocol as `as-query` rather than a provider's
    tool-calling API, so it works on every backend including a small local model
  - **`Finding` moved from `jfr-mcp` to `shell-core`** (`io.jafar.shell.core.findings`), so the
    shell and the MCP server share one output shape and a shell investigation can merge with an
    MCP one
  - **The model asks what fields a type has instead of guessing.** JFR is self-describing, so an
    event's fields are whatever the recording declares — unknowable from the type name, and for a
    custom event unknowable at all. A reply may be `FIELDS: <types>`, answered with those types'
    fields and the types those fields lead to, so `sampledThread/javaName` is read rather than
    invented. One extra round trip and ~1,200 characters, against ~9,800 tokens to send every
    type's fields up front. Capped at 8 types and one round
  - **The model never sees raw events.** It composes a query and the shell runs it, so a 900 MB
    recording costs the same as a 2 MB one. The query-language reference is the cacheable prompt
    prefix
  - **Egress control**: result rows are redacted by field name before leaving the process (paths,
    addresses, hosts, messages, string values), truncated to `llm.max-rows`, and `--dry-run` on
    either verb prints the exact bytes a real call would send without sending them
  - **Recording content is treated as untrusted input**: thread names, exception messages and heap
    string values are attacker-controllable when the recording came from a third party, so they are
    fenced in explicit data markers and the tool surface is read-only
  - Optional at runtime: the SPI is in `shell-core` with no new dependencies and backends are
    discovered via `ServiceLoader`, so a build without `llm-anthropic` and `llm-openai` carries no
    provider dependency at all and every other command is unchanged
  - Settings via `set`: `llm.enabled`, `llm.backend`, `llm.model`, `llm.base-url`, `llm.api-key`,
    `llm.max-tokens`, `llm.max-rows`, `llm.max-retries`, `llm.timeout`, `llm.confirm`, `llm.redact`,
    `llm.redact-fields`. `set` had to learn about them: it rejected every dotted name, since
    `${a.b}` means field access in an expression, so `set llm.backend = ollama` answered
    *"Invalid variable name"*. A setting's value is now stored as literal text rather than
    evaluated — a bare word was being read as a query (*"Unknown root: ollama"*) and a bare
    integer coerced to a double, so `set llm.max-rows = 20` stored `20.0` and silently fell back
    to the default. A name starting with `llm.` that is not a setting is reported as a typo with
    the real names listed
  - **A settings file**, `~/.config/jafar/llm.properties` (also `$JAFAR_LLM_CONFIG` or
    `$XDG_CONFIG_HOME/jafar/`), using the same key names `set` uses. An environment variable is a
    poor home for a long-lived credential — every child process inherits it, it appears in crash
    dumps and CI logs, and exporting it inline puts it in shell history. `llm status` names the
    file, warns when it is readable by anyone else, and reports which layer each setting came
    from, so a stale environment variable shadowing the file is visible rather than baffling.
    `llm.api-key` now reaches the Anthropic backend too — it previously worked only for the
    OpenAI-compatible ones, because that backend asked the SDK alone, so a key sitting in the
    settings file produced "No credentials found". A configured key takes precedence over
    `ANTHROPIC_API_KEY`, which may be left over from something else in the same terminal
  - **Tab completion and help**: `ask`, `as-query`, `explain` and `llm` complete as commands in
    both shells, `llm` completes its subcommands, `set llm.` completes every setting with
    descriptions, `help` lists them as subjects (including in the interactive shell's own `help`,
    which listed none of them), and `help ask` carries worked examples. A test reads
    `LlmConfig.java` and fails if a setting it reads is not offered, so the list cannot drift
  - Docs: [LlmSetup](doc/cli/LlmSetup.md), [AskTutorial](doc/cli/AskTutorial.md),
    [LlmPrivacy](doc/cli/LlmPrivacy.md), [WhenToUseWhich](doc/mcp/WhenToUseWhich.md), and
    [the handoff](doc/plans/llm-in-the-shell-handoff.md) describing the seams left for an agentic
    mode
  - `jafar-shell` has no `set` command yet, so settings there come from `JAFAR_LLM_*` environment
    variables. The whole path — including the correction loop — is verified in both built shells
    against a real recording and a real local HTTP server, but no hosted provider has been called
    from this repository; see the handoff, section 6
- **`jafar-perf` Claude Code plugin** - methodology layer over the MCP server, published from
  [btraceio/jafar-perf-box](https://github.com/btraceio/jafar-perf-box)
  - Nine skills: `triage`, `cpu`, `latency`, `gc`, `memory-leak`, `heap-diff`, `compare`, `jfrpath`, `report`
  - Seven agents: `perf-lead` coordinator, `perf-engineer`, and five specialists with narrow tool allowlists
  - Bundles `.mcp.json`, so installing the plugin registers the MCP server too
  - Kept in its own repository because `/plugin marketplace add` clones the marketplace repository:
    the plugin is 160 KB of Markdown and this repository is ~18 MB, 9.6 MB of it binary JFR test
    recordings
- **`jfr_compare` MCP tool** - compares a candidate recording against a baseline
  - Event counts normalised to per-second rates using each recording's own observed span; stack frames
    compared as a share of that recording's samples, so different sampling intervals stay comparable
  - Reports a `comparability` block (different profiler event types, durations differing by more than
    3x, low sample counts) rather than silently producing a plausible-looking number
  - Changes below a configurable noise floor (`minDeltaPct`, default 1.0 percentage points) are withheld
- **Unified findings model** (`io.jafar.mcp.findings.Finding`) - `jfr_diagnose`, `jfr_use`, `jfr_tsa`,
  `jfr_compare`, `pprof_use`, `otlp_use` and `hdump_report` now all return a `findings` array with a
  stable `id`, `severity`, `category`, `title`, `evidence`, `action` and follow-up `query`. Findings from
  different tools de-duplicate and merge (`Findings.merge`). Findings derived from heuristics — the
  keyword-inferred thread states in the pprof and OTLP tools — record `heuristic=true`.
- **MCP prompts and resources** - the server now advertises both capabilities
  - Prompts: `triage`, `compare`, `leak-hunt`, `latency` (surfaced as `/mcp__jafar__<name>` in Claude Code)
  - Resources: `jafar://sessions`, `jafar://help/jfrpath`, `jafar://help/hdumppath`, `jafar://help/tools`
- **JfrPath duration unit suffixes** - `ns`, `us`, `ms`, `s` in numeric literals, converting to
  nanoseconds (`events/jdk.GCPhasePause[duration>10ms]`). These were already documented in the MCP
  `jfr_help` output and in `doc/mcp/Tutorial.md`, but the parser rejected them. No minute suffix: `m`
  already means mebibytes. Size suffixes are unchanged.

### Changed
- **`jfr_diagnose` runs the analyses it previously only recommended** - it now executes the USE and TSA
  passes in-process and merges their findings. New `depth` parameter (`quick` skips both). The response
  keeps `recommendations` and moves the old human-readable strings to `headlines`; `findings` is now the
  structured array, matching `hdump_report`. New `capabilityGaps` lists what the recording cannot answer
  (for example allocation profiling not enabled), stated separately from findings.
- **`JfrQueryEvaluator` moved from `jfr-shell` to `shell-core`** (same package and FQN, no import changes)
  so that consumers without the interactive CLI can evaluate JfrPath against a JFR session.
- **`AGENTS.md` is now an entry point rather than a manual.** It was 544 lines, of which one section
  was 241; the areas it covered now live in `doc/agents/` and it links to them. New
  `doc/agents/Verification.md` records how to know a change works in this repository — eight rules,
  each with the case file that produced it, drawn from bugs that shipped or nearly shipped while
  their tests were green.

### Fixed
- **`groupBy` on a field the event type does not have returned zero rows and no complaint.** An
  empty result reads exactly like "this recording has no such events", so the reader moves on
  rather than fixing the name. It now counts the events the key was offered and, when none of them
  yielded a key, names the key, the count and the fields the type does have:
  `groupBy: key 'gcType' matched nothing in 218 events of jdk.GarbageCollection. Available:
  [cause, duration, eventThread, gcId, longestPause, name, startTime, sumOfPauses]`. A group-by
  over a type with no events at all is still an empty result, so nothing that returns rows today
  can start failing
- **`sortBy(value)` and `top(n, by=value)` now read the aggregate column of a `groupBy` result.**
  `groupBy` names its output `key` and the aggregate after the function, so
  `groupBy(name, agg=sum, value=sumOfPauses) | sortBy(value)` was rejected with
  `field 'value' not found. Available: [sum, key]` — even though `groupBy`'s own `sortBy=`
  argument already spells that column `value`. `top` had the same gap and failed silently instead:
  an unresolved path yields null for every row, so the sort kept the input order and returned the
  first n rows as the top n. Both `top(10, by=value)` examples in the model-facing language
  reference were affected. The alias applies only where there is no real column of that name, so a
  recording's own `value` field is never shadowed
- **The MCP server reported the wrong version in its handshake.** `serverInfo.version` was a
  literal `"0.10.0"` that was never updated, so every release from 0.10.0 onwards - 0.26.2
  included - told clients it was 0.10.0, and anything gating on it was misled. The version is now
  read from the jar manifest (`Implementation-Version`, added to the shadow jar), which cannot go
  stale; outside a jar it reports `unknown` rather than a number that might be wrong
- `JfrQueryEvaluator.evaluate` now accepts a raw query string as well as a parsed
  `JfrPath.Query`, matching what the `QueryEvaluator` interface documents and what the Hdump, pprof
  and OTLP evaluators already did. It previously threw `Expected JfrPath.Query`, so a caller holding
  only the query text had to know which implementation it had
- **Heap-to-JFR correlation now works over MCP** - `hdump_query` was passed a bare `SessionResolver`, so
  `join(session=..., root="jdk.ObjectAllocationSample", by=class)` failed with "Cross-type join requires a
  CrossSessionContext" and the correlation was reachable only from `jafar-shell`. The server now supplies
  an `McpCrossSessionContext` spanning the heap and JFR registries.
- **Allocation correlation produced only null columns** - `AllocationAggregator` read
  `objectClass.name` as a plain string, but the untyped parser wraps string constants
  (`{objectClass: {name: {value: {string: "[B"}}}}`), so every real recording aggregated to an empty
  map and the heap-to-JFR join filled `allocCount`, `allocWeight`, `allocRate`, `topAllocSite` and
  `survivalRatio` with nulls for every class. Allocation-site extraction had the same problem with
  wrapped frame and type names. Verified end to end against a real recording and heap dump:
  `byte[]` now correlates to 3494 allocation samples with `topAllocSite` resolved. The existing
  tests missed this because they all fed a flattened `objectClass.name` string shape the parser
  never emits; regression tests now cover the real shape.
- **`by=class` was wrong in the documented cross-type join examples** - on the `classes` root the
  join key field is `name` (`by=class` applies to the `objects` root), so the documented queries
  silently matched nothing. The examples now let the key be inferred.
- **Documentation understated the MCP server** - `jfr-mcp/README.md` and `doc/mcp/Tutorial.md` listed 13
  JFR-only tools; the server registers 37 across JFR, HPROF, pprof and OTLP. `AGENTS.md` omitted the
  `hdump_*` family.

- **go-parser module** - Pure Go port of the untyped JFR parser (`github.com/btraceio/jafar/go-parser`)
  - Standalone Go module in `go-parser/`, kept out of the Gradle build; no external dependencies
  - Same value model as the Java untyped API: events as `map[string]any`, lazy per-chunk
    constant-pool resolution, `@Timestamp`/`@Timespan` tick normalisation
  - Parser only - no JfrPath, no shell, no CLI; the typed API is not ported
  - Benchmark suite mirroring the JMH untyped-parser workloads, plus the constant-pool
    resolution axis those do not isolate
  - Allocation work: memoised deep constant-pool resolution, metadata decoded straight into
    its target objects instead of an intermediate element tree, block-allocated constant-pool
    references, and an opt-in `Options.ReuseValues` that recycles the objects an event decodes
    into (93% fewer bytes and 1.76x faster on the reference recording), and a per-chunk
    decoded-string cache (Jafar's `CachedStringParser`, widened to a small table)
  - Benchmarks discover whatever recordings are present, including the larger ones
    `./get_resources.sh` downloads, and pick their workload event types per recording
  - Released as `go get github.com/btraceio/jafar/go-parser@vX.Y.Z`, versioned in step with the
    Java artifacts; the release workflow creates the `go-parser/vX.Y.Z` tag a subdirectory module
    needs, validating the module before tagging since a Go module version is immutable once
    published
- **pprof-parser module** - Standalone pprof profile parser extracted from `pprof-shell`
  - Public API in `io.jafar.pprof.api` (`PprofParser`, `PprofProfile`); wire decoding in `io.jafar.pprof.internal`
  - `pprof-shell`, `jfr-mcp`, and `jfr2pprof` now consume the parser API instead of shell internals
- **otlp-parser module** - Standalone OTLP profiling parser extracted from `otlp-shell`
  - Public API in `io.jafar.otlp.api` (`OtlpParser`, `OtlpProfile`); wire decoding in `io.jafar.otlp.internal`

### Changed
- **Modular structure / API-SPI separation** for the pprof and HPROF format support
  - Heap dump analysis operations (dominator tree, retained sizes, class-instances index)
    promoted to the `io.jafar.hdump.api.HeapDump` interface; `hdump-shell` no longer
    references `io.jafar.hdump.impl` classes
  - Protobuf wire-format decoding (`ProtoUtil`) moved from `shell-core` to `parser-core`
    (`io.jafar.utils`), removing the parser-on-shell dependency for pprof/OTLP readers
- **Backend plugin versioning** - Plugins now follow main project version
  - `jfr-shell-jdk` and `jfr-shell-jafar` sync with main project version
  - API compatibility enforced via japicmp for breaking change detection

### Fixed
- **CI dependency resolution** - `jfr-shell` now depends on the released `dev.tamboui:*:0.2.0`
  instead of the expired `0.2.0-SNAPSHOT`, which is no longer resolvable from any snapshot repository
- **jfr-mcp publishing** - Fixed Maven Central artifact to use shadowJar
  - Added mavenPublishing configuration to publish fat JAR as main artifact
  - jfr-mcp now includes all dependencies and proper Main-Class manifest
- **JBang catalog update** - Fixed workflow to preserve dev/snapshot entries
  - Changed from sed global replacement to jq precise JSON updates
  - `jfr-mcp` alias updated to releases, `jfr-mcp-dev` stays on SNAPSHOT
  - `jafar-shell-latest` alias updated to releases, `jafar-shell` stays on java file

## [0.10.0] - 2026-02-14

### Added
- **JFR Shell backend plugin system** - Extensible architecture for custom JFR backends (#48)
  - Plugin discovery and loading with version management
  - Plugin-specific backends with configurable settings
  - Remote plugin registry support with fallback to local registry
  - Command-line plugin management (`--plugin`, `--list-plugins`)
  - Comprehensive plugin API for third-party extensions
- **MCP server for AI-assisted JFR analysis** - Model Context Protocol server integration (#53, #54)
  - Natural language queries for JFR analysis
  - JfrPath query generation and execution
  - Session management with multiple concurrent recordings
  - Built-in diagnostic tools (USE method, TSA, flamegraphs, hotmethods)
  - Integration with Claude and other AI assistants
  - Comprehensive documentation and tutorials
- **Advanced map operations** - Enhanced variable system with map support (#38, #39)
  - `toMap()` function for converting lists to maps
  - `merge()` function for combining maps
  - Map variable support with nested operations
- **Time range support** - Query filtering by time ranges (#46)
  - Time range syntax for filtering events by timestamp
  - Duration-based queries
- **Comprehensive test coverage** - Extensive JFR Shell testing (#45)
  - Unit tests for shell commands and operations
  - Integration tests for complex workflows
  - Fuzzy testing for robustness (#46)

### Changed
- **JFR Shell completion system** - Major improvements to code completion (#47)
  - Context-aware completion for JfrPath queries
  - Field name completion from metadata
  - Function and operator suggestions
  - File path completion
- **Syntax highlighting** - Enhanced visual feedback in shell (#46)
  - Keyword highlighting
  - String and number coloring
  - Operator and function highlighting
- **Documentation restructure** - Organized documentation by component (#54)
  - Separate directories for parser, CLI, MCP, JBang
  - Improved navigation and discoverability
  - PascalCase naming convention for all docs

### Fixed
- **Array iteration** - Fixed array handling in JfrPath queries (#42)
- **Conditional flow** - Corrected parameter handling in shell conditionals (#43)
- **CI workflows** - Fixed protected branch handling and catalog updates (#49)
- **Plugin registry tests** - Prevented remote registry fetches during testing (#50, #51)

## [0.6.0] - 2026-01-04

### Added
- **Expression support in select()** - Computed field expressions with arithmetic, string operations, and functions (#37)
  - Arithmetic operators: `+`, `-`, `*`, `/`
  - String concatenation: `+`
  - String templates: `"${field} text ${expr}"` for cleaner string interpolation
  - Built-in functions: `if()`, `upper()`, `lower()`, `substring()`, `length()`, `coalesce()`
  - Expression AST with BinExpr, FuncExpr, FieldRef, Literal, StringTemplate
  - Examples: `select(bytes / 1024 as kb)`, `select("${path} (${bytes} bytes)" as info)`
- **Multi-event type queries** - Query multiple event types simultaneously (#37)
  - Pipe-separated syntax: `events/(Type1|Type2|Type3)`
  - Works with all query operations (filters, aggregations, select)
  - Tab completion for multiple event types
  - Examples: `events/(jdk.FileRead|jdk.FileWrite) | count()`
- **CSV output format** - Export-friendly format with session-level setting (#37)
  - `set output csv` to set CSV as default format
  - CSV format with proper escaping and quoting
  - Override per-query with `--format` flag
- **Nested field projection** - Better handling of nested fields in select() (#37)
  - Nested fields preserve parent structure in output
  - Leaf segment used as column name for flat projections
  - Tab completion for nested fields in select()

### Changed
- **JFR Shell UX improvements** - Various completion and pager fixes (#37)
  - Fixed completion for `decorateByTime()` and `decorateByKey()` functions
  - Fixed script command hanging on pager prompts
  - Added filesystem completion for `record start` command
  - Added type IDs to metadata views

## [0.5.0] - 2026-01-03

### Added
- **JFR Shell scripting support** - Full scripting capabilities for automated JFR analysis (#34)
  - Variable system with type inference (numbers, strings, lists, maps, booleans, null)
  - Conditional execution (`if/elif/else/endif`) with boolean expressions and nested conditions
  - Command recording (`record/endrecord`) for creating reusable command sequences
  - Script execution with `.jfrs` files and embedded example scripts
  - Variable substitution using `$variable` or `${variable}` syntax
  - Non-interactive mode enhancements for scripting and CI integration
  - Example scripts: `basic-analysis.jfrs`, `gc-analysis.jfrs`, `thread-profiling.jfrs`
  - Comprehensive scripting documentation and tutorials

### Changed
- **JfrPath completion** - Enhanced completion for scripting variables and control flow
- **Shell state management** - Added variable store and conditional state tracking

## [0.4.0] - 2025-12-28

### Added
- **Build-time handler generation** - New annotation processor for compile-time handler generation as alternative to runtime bytecode generation (#33)
  - 85% allocation reduction with equivalent throughput
  - ServiceLoader auto-discovery of generated factories
  - Thread-local caching for reduced allocations
  - Backward compatible with runtime generation fallback
  - New `jafar-processor` module with annotation processor
  - New `HandlerFactory<T>` interface for factory pattern
- **Event decoration query language** - JFR Shell now supports joining and correlating events (#32)
  - `decorateByTime()` for temporal event joins on same thread
  - `decorateByKey()` for correlation by matching keys
  - Decorator fields accessible via `$decorator.` prefix
  - Memory-efficient lazy evaluation

### Changed
- **JFR Shell completion framework redesign** - Reduced ShellCompleter from 1300+ lines to ~220 lines using Strategy pattern (#32)
  - 12 specialized completers for different contexts
  - Centralized CompletionContextAnalyzer for testable context detection
  - MetadataService with caching for metadata lookups
  - Support for chunk IDs, metadata subprops, nested filter paths
  - 41 comprehensive completion tests

### Fixed
- **Metadata search** - Improved error reporting for metadata scan failures
- **Tilde expansion** - Fixed path expansion for user home directory

## [0.3.12] - 2025-12-17

### Changed
- **JBang Java requirement** - Updated to require Java 25+ to match jafar-shell compilation target

## [0.3.11] - 2025-12-17

### Changed
- **Removed JitPack publishing** - All artifacts now published exclusively to Maven Central and GitHub Packages
- **Artifact naming consistency** - Renamed `jfr-shell` to `jafar-shell` for consistency with other artifacts
- **JBang catalog improvements** - Updated to use Maven Central instead of JitPack, automatic updates on release

## [0.3.10] - 2025-12-17

### Changed
- **Publishing system migration** - Switched from legacy OSSRH (nexus-publish plugin) to new Sonatype Central Portal (vanniktech maven-publish plugin)
- **Automatic signing** - All artifacts are now automatically signed with GPG
- **Complete POM metadata** - All publications now include required Maven Central metadata (license, developers, SCM)

## [0.3.9] - 2025-12-17

### Fixed
- **Maven Central compliance** - Added sources and javadoc JARs to jfr-shell (required for Maven Central validation)
- **Sonatype publishing** - Fixed close and release to run in same Gradle invocation

## [0.3.8] - 2025-12-17

### Fixed
- **Sonatype publishing** - Added automatic close and release of staging repositories to Maven Central

## [0.3.7] - 2025-12-17

### Changed
- **JFR Shell publishing** - Now published to Maven Central (io.btrace:jfr-shell) for public JBang access
- **JFR Shell groupId** - Changed from io.jafar to io.btrace to match other artifacts and enable Sonatype publishing
- **JBang catalog** - Updated to use Maven Central coordinates (io.btrace:jfr-shell)

## [0.3.6] - 2025-12-17

### Added
- **Automated release workflow** - Complete CI/CD pipeline for publishing releases (#32)
  - Single-command release process triggered by version tags
  - Automatic publishing to Maven Central, GitHub Packages, and JitPack
  - Automatic JBang catalog updates
  - GitHub Release creation with changelog extraction

### Changed
- **Gradle toolchain auto-provisioning** - Added Foojay resolver for automatic JDK downloads
  - Fixes JitPack builds requiring multiple Java versions
  - Enables seamless multi-module builds with different Java requirements

### Fixed
- **GitHub Packages publishing** - Use specific publication task to avoid Sonatype credential conflicts
- **CI workflow separation** - Snapshot publishing (main branch) now separate from release publishing (tags)

## [0.3.0] - 2024-12-16

### Added
- **JFR Shell** - New interactive CLI tool for exploring and analyzing JFR recordings with JfrPath query language (#31)
  - JfrPath query language for navigating events, metadata, chunks, and constant pools
  - Tab completion, command history, and multi-session management
  - Aggregations (groupBy, top, sum, stats) and filtering with boolean expressions
  - Non-interactive mode for scripting and CI integration
  - JBang distribution support for easy installation
  - Standalone jlink distribution with bundled JRE
- **Comprehensive tutorials** - Added detailed tutorials for Typed API, Untyped API, and JFR Shell (#31)
- **Demo applications** - Added DatadogProfilerDemo showcasing both typed and untyped API usage patterns (#31)
- **JBang publishing workflow** - Automated GitHub Actions workflow for publishing to JitPack (#31)

### Changed
- **TypeGenerator naming convention** - Generated type interfaces now include the full namespace to prevent collisions. For example, `jdk.ExecutionSample` generates `JFRJdkExecutionSample` and `datadog.ExecutionSample` generates `JFRDatadogExecutionSample`. This ensures distinct interfaces when multiple events share the same simple name across different namespaces (#30)

### Fixed
- **Circular type resolution in TypeGenerator** - Fixed infinite recursion when generating types with circular references (#29)
- **Irregular attribute names** - Fixed handling of field names containing dots (e.g., `_dd.trace.operation`) by sanitizing them to valid Java identifiers (#29)
- **Type filters in Java 21 TypeGenerator** - Added support for event type filters in Java 21 version of TypeGenerator (#29)
- **Simple type constant resolution** - Fixed resolution of simple type constants in untyped parser's constant pool accessor (#29)
- **String type skipping** - Fixed type skipping for strings and simple types in constant pools (#29)

## [0.2.0] - 2024-12-14

### Added
- **Iterator-style API** - New `ParsingContext.iterateEvents()` method for streaming JFR events without loading entire file into memory (#25)
- **Metadata fingerprinting** - Handler class reuse across parsing sessions with same metadata fingerprint for improved performance (#26)

### Changed
- **Multi-tier untyped parser optimization** - Adaptive strategy system automatically switches between optimized and generic parsers based on event complexity (#27)

### Fixed
- **Typed parser simple type unwrapping** - Fixed null values for simple types like `jdk.types.Symbol` by implementing transitive closure for type filtering (#28)

## [0.1.0] - 2024-12-04

### Added
- **Core typed parser API** - Define Java interfaces annotated with `@JfrType` and `@JfrField` for type-safe JFR event parsing
- **Core untyped parser API** - Parse JFR events as `Map<String, Object>` without pre-defining interfaces
- **Multi-Release JAR support** - Single JAR optimized for Java 8, 9, 13, and 21 runtimes
- **Dual constant pool access** - Access both resolved values and raw CP indices for the same field via `@JfrField(raw = true)`
- **Chunk-based parallel processing** - Parse large JFR files efficiently using multi-threaded chunk processing (#5)
- **Control API** - Abort parsing early, access chunk metadata, and query stream position from event handlers
- **ParsingContext reuse** - Reuse parsing context across multiple files for improved performance
- **Gradle plugin** - Automatically generate JFR type interfaces from JFR recordings (`io.jafar.jafar-gradle-plugin`)
- **Scrubbing tool** - Redact sensitive field values from JFR recordings
- **JMH benchmarks** - Performance benchmarking infrastructure for parser optimization
- **CI/CD pipeline** - GitHub Actions workflows testing on JDK 8 and 21
- **Synthetic test generation** - Generate JFR test files programmatically for edge case validation
- **ChunkInfo API** - Expose chunk ID and metadata to typed and untyped parsers
- **Time conversion utilities** - Convert JFR ticks to wall-clock timestamps (#21)

### Changed
- **Optimized handler code generation** - Reworked ASM-based bytecode generation for better performance (#7)
- **Optimized metadata parsing** - Reduced allocation and improved parsing speed for JFR metadata sections
- **Optimized constant pool loading** - Lazy rehydration of constant pools for memory efficiency
- **Optimized string parsing** - Improved performance for string deserialization
- **Optimized deserialization** - Reduced allocations during event deserialization
- **More flexible string parsing** - Enhanced robustness for various string encodings
- **Improved time conversion API** - More user-friendly tick-to-timestamp conversion (#21)
- **Consistent Control usage** - Unified Control object handling across typed and untyped APIs
- **Java 8 compatibility** - Parser module now targets Java 8 bytecode (#20)

### Fixed
- **parseLongSWAR overflow** - Fixed NumberFormatException for negative long values in optimized string parsing (Dec 3, 2024)
- **Null pointer protection** - Added safety checks for malformed JFR files (Dec 2, 2024)
- **Erroneous skipper bytecode** - Fixed incorrect bytecode generation in skip paths (Dec 1, 2024)
- **Local variable index tracking** - Corrected LVT indices in recursive skip bytecode generation (Dec 1, 2024)
- **Recursive type generation** - Fixed infinite loops in Gradle plugin for recursive JFR types (Dec 1, 2024)
- **Raw pointer support** - Fixed handling of raw constant pool pointers (Aug 17, 2024)
- **Untyped parser thread safety** - Create new ParsingContext per chunk to avoid race conditions (#17)
- **BytePackingTest** - Fixed test failures for byte packing utilities (Aug 12, 2024)

### Security
- **Large file handling** - Added support for very large JFR files (multi-GB)
- **Thread safety improvements** - Enhanced concurrency safety in parser internals

## Version History

This is the first public release of JAFAR.

[Unreleased]: https://github.com/btraceio/jafar/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/btraceio/jafar/releases/tag/v0.2.0
[0.1.0]: https://github.com/btraceio/jafar/releases/tag/v0.1.0

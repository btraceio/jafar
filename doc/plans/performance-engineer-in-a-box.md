# Performance Engineer in a Box: Ideation

Status: ideation, no decision taken. Four alternatives, ordered from conservative to
groundbreaking. Each one builds on the previous one; picking a later tier implies doing the
earlier ones first.

Scope of the question: Jafar already exposes a lot of analysis capability (four parsers, four
shells, one MCP server with 36 tools). What is missing is the *methodology layer* that turns those
tools into an agent that behaves like a performance engineer: knows which question to ask next,
which tool answers it, what counts as evidence, and how to report. Claude Code's plugin format
(skills + agents + hooks + bundled MCP config) is the natural packaging for that layer.

## 1. Current state, with evidence

### 1.1 What exists

| Capability | Where | Notes |
|---|---|---|
| MCP server, 36 tools over JFR / HPROF / pprof / OTLP | `jfr-mcp/src/main/java/io/jafar/mcp/JafarMcpServer.java:565-604` | Tools only. No MCP prompts, no resources: `McpServerFactory.java:19` declares `tools(true).logging()` and nothing else. |
| Generic query tools (`jfr_query`, `hdump_query`, `pprof_query`, `otlp_query`) | `jfr/JfrSessionTools.java:138`, `hdump/HdumpTools.java:187`, `pprof/PprofTools.java:188`, `otlp/OtlpTools.java:187` | The escape hatch. All intelligence comes from the model composing JfrPath / HdumpPath / SamplesPath. |
| Opinionated JFR analyses | `jfr/JfrAnalysisTools.java` | `jfr_use` (:1423), `jfr_tsa` (:2306), `jfr_diagnose` (:2963), `jfr_stackprofile` (:3163), `jfr_hotmethods` (:1236), `jfr_exceptions` (:712), `jfr_flamegraph` (:102), `jfr_callgraph` (:468), `jfr_summary` (:1005). |
| Heap health report with structured findings | `hdump/HdumpTools.java:387`, findings shape at `:450-462`; rules in `hdump-shell/.../HeapReportGenerator.java` | The only place with a real `Finding` record (severity, category, title, description, retainedSize, affectedObjects, action, follow-up query). |
| Six heap leak detectors + graph-based clusters, duplicates, ages, waste, cacheStats, whatif | `hdump-shell/src/main/java/io/jafar/hdump/shell/leaks/`, `.../hdumppath/ClusterDetector.java`, `SubgraphFingerprinter.java`, `CollectionWasteAnalyzer.java`, `CacheStatsAnalyzer.java` | Roadmap items 01-04 under `doc/roadmaps/heapdump/` are all marked implemented. |
| Heap-to-JFR allocation correlation | `hdump-shell/.../HdumpPathEvaluator.java` (`applyCrossTypeJoin`, ~:1589), `shell-core/.../AllocationAggregator.java` | Works in `jafar-shell` only. See gap G3. |
| Event decoration (time overlap and key joins) | `shell-core/.../jfrpath/JfrPath.java:558-600`; `doc/cli/JFRPath.md:482-577` | The mechanism behind the three markdown cookbooks (`monitor-contention.md`, `gc-impact.md`, `request-tracing.md`). |
| Headless scripting with params, conditionals, exit codes, stdin | `jfr-shell/src/main/java/io/jafar/shell/Main.java:481` (`script`), `:617-626` (stdin), `:642-659` (exit codes) | `.jfrs` scripts are the reproducible-evidence format Jafar already has. |
| Command recording into `.jfrs` | `jfr-shell/.../CommandRecorder.java`, `doc/cli/CommandRecording.md` | "Record an investigation, replay it" already exists for humans. |
| HTML flamegraph rendering | `jfr-shell/.../FlameGraphHtmlRenderer.java` | Self-contained file; usable as a report attachment. |
| Tool-selection guidance for the model | `jfr/JfrHelpProvider.java:399` (`getToolsHelp`) | The seed of a methodology skill, currently reachable only by calling `jfr_help topic=tools`. |
| Example analyses | `jfr-shell/src/main/resources/examples/` (4 `.jfrs`, 3 `.md`); `doc/cli/Tutorial.md` "Real-World Examples" (7 scenarios) | Canned recipes exist but are not discoverable by an agent. |

### 1.2 What is missing (gaps referenced below as G1..G8)

- **G1: No skills, agents, or plugin.** There is no `.claude/` directory, no `skills/`, no
  `agents/`, and no Claude-related workflow under `.github/workflows/` (grep for
  claude/anthropic/copilot returns nothing). `CLAUDE.md` is a redirect to `AGENTS.md`.
- **G2: Findings are not a shared model.** `jfr_diagnose` emits findings and recommendations as
  strings (`JfrAnalysisTools.java:3032-3129`); `jfr_use` insights (`generateUseInsights`, :2163)
  and `jfr_tsa` insights (`generateTsaInsights`, :2710) each have their own ad-hoc shape; only
  `hdump_report` has a typed `Finding`. An agent cannot merge, rank, or de-duplicate across them.
- **G3: Heap-to-JFR correlation is unreachable over MCP.** `HdumpTools.java:293` passes
  `heapSessionRegistry.asResolver()`, a bare `SessionResolver`. `HdumpPathEvaluator.java:1593-1596`
  throws "Cross-type join requires a CrossSessionContext" unless it gets one. The feature that
  `doc/roadmaps/heapdump/03-jfr-heap-correlation.md` calls "Jafar's unique differentiator" is
  invisible to agents.
- **G4: No recording-to-recording comparison.** `JfrPathParser.java` has no `join`, `diff`, or
  `compare`; only HdumpPath has `join`. "Is this build slower than the last one, and where?" cannot
  be answered by a single tool call.
- **G5: No live-JVM interaction.** Nothing in the repo starts a recording, dumps a heap, or
  attaches to a process (no `jcmd`, `JFR.start`, or `VirtualMachine.attach` usage). The agent
  can only analyse files that already exist.
- **G6: `jfr_diagnose` is shallow.** It reads `jfr_summary` JSON and applies four fixed thresholds
  (`JfrAnalysisTools.java:3032-3110`). It *recommends* `jfr_use` and `jfr_tsa` rather than running
  them (:3066, :3109, :3129).
- **G7: Docs understate the surface.** `jfr-mcp/README.md:50-66` and `doc/mcp/Tutorial.md:20-34`
  list 13 JFR tools; the server registers 36 across four formats. `AGENTS.md:364-372` is the only
  accurate list and it also omits `hdump_*`.
- **G8: `jafar-shell` lacks scripting.** `jafar-shell/.../unified/Shell.java` wires `open`,
  `sessions`, `use`, `close`, `info`, `show`, `checkLeaks`, `modules`; the `set`/`vars`/`if`
  machinery from `jfr-shell`'s `CommandDispatcher` is not connected. Cross-format investigations
  cannot be scripted end-to-end.

## 2. Design axes

Each alternative is a point on four axes:

1. **Where the judgement lives.** In the Java server as heuristics (deterministic, testable,
   cheap), or in the model guided by skills (flexible, explains itself, costs tokens).
2. **Packaging.** Skill files only; skills plus subagents; a plugin with bundled `.mcp.json` and
   hooks; a standalone Agent SDK application.
3. **Trigger.** A human asks; a CI event; a schedule.
4. **Loop closure.** Diagnose only; diagnose and propose a code change; diagnose, change,
   re-measure, and decide.

Claude Code packaging facts used below, from the official docs: plugin layout with
`.claude-plugin/plugin.json`, `skills/`, `agents/`, `hooks/hooks.json`, and a root `.mcp.json`
(https://code.claude.com/docs/en/plugins-reference.md); skill frontmatter including
`context: fork`, `agent`, `allowed-tools`, `disable-model-invocation`
(https://code.claude.com/docs/en/skills.md); subagent frontmatter including `tools`, `model`,
`skills`, `memory`, `maxTurns`, and the ability to allow MCP tools by `mcp__server__tool` name
(https://code.claude.com/docs/en/subagents.md); MCP prompts surfacing as `/mcp__server__prompt`
slash commands and resources as `@` mentions (https://code.claude.com/docs/en/mcp-quickstart.md);
marketplace distribution from a GitHub repo via `.claude-plugin/marketplace.json`
(https://code.claude.com/docs/en/plugin-marketplaces.md).

## 3. Alternative A (conservative): Guided Analyst plugin

**Thesis.** The tools are good enough. What the model lacks is a methodology and a map. Ship that
as markdown, change no Java.

**Deliverables.**

- `plugins/jafar/` in this repo, published through `.claude-plugin/marketplace.json` so users run
  `/plugin marketplace add btraceio/jafar` and `/plugin install jafar@btraceio`.
- `.mcp.json` bundling the server exactly as `README.md:526` documents it:
  `jbang jfr-mcp@btraceio --stdio`. Installing the plugin registers the server; no separate
  `claude mcp add`.
- Skills, one per question a performance engineer is asked. Each is a playbook: what to run first,
  what thresholds mean, what to run next given the answer, and what to write down. Drawn from
  content already in the repo:

  | Skill | Source material to lift |
  |---|---|
  | `jafar:triage` | `jfr_diagnose` step order, `JfrHelpProvider.getToolsHelp` (:399), `doc/mcp/Tutorial.md:476-567` workflows |
  | `jafar:cpu` | `hotmethods` vs `flamegraph` vs `stackprofile` vs `callgraph` decision table (`JfrHelpProvider.java:399+`) |
  | `jafar:latency` | USE then TSA then `decorateByTime(jdk.JavaMonitorWait ...)` (`examples/monitor-contention.md`) and `decorateByKey` request tracing (`examples/request-tracing.md`) |
  | `jafar:gc` | `examples/gc-analysis.jfrs`, `examples/gc-impact.md` |
  | `jafar:memory-leak` | `hdump_report` then `hdump_query` detectors, `clusters`, `duplicates`, `pathToRoot()`; the inline cheat sheet at `HdumpTools.java:210-273` |
  | `jafar:heap-diff` | `join(session=...)` from `doc/roadmaps/heapdump/01-heap-diff.md` |
  | `jafar:jfrpath` | `doc/cli/JFRPath.md`; a reference file, `disable-model-invocation: false`, so the model can consult syntax without a tool round-trip |
  | `jafar:report` | A fixed report template: symptom, evidence (tool + arguments + numbers), interpretation, recommendation, confidence, next steps; every claim cites the exact query that produced it |

- One subagent, `agents/perf-engineer.md`, with `tools` restricted to the `mcp__jafar__*` tools
  plus `Read`/`Grep` for correlating frames to source, `skills` preloading `triage` and
  `report`, `maxTurns` bounded. Its instructions require that each conclusion names the tool call
  it rests on.
- Doc fixes for G7 so the model's own reading of the docs matches the server.

**What the user gets.** "Open this recording and tell me why p99 doubled" produces a structured
report with cited evidence, using the current server. Non-JFR formats work through the same
skills because the tool families are near-symmetric (`pprof_use`, `pprof_tsa`, `otlp_use`).

**Cost and risk.** Markdown only; days of work. Risk is quality drift: skills describe thresholds
that live in Java (`jfr_diagnose` uses avg pause > 100 ms), and the two can diverge. Mitigation is
to reference the tool output fields rather than restate the numbers.

**Does not fix.** G2 through G6, G8.

## 4. Alternative B (moderate): Findings model, specialists, and the missing joins

**Thesis.** Make the server emit evidence an agent can reason over, and split the work across
specialist subagents with narrow tool allowlists. This is the platform investment the later tiers
depend on.

**Java changes.**

1. **Unified `Finding` record** in `jfr-mcp` (or `shell-core`), modelled on
   `HeapReportGenerator.Finding`: severity, category, title, evidence (tool, arguments, numbers),
   `action`, follow-up `query`, and a stable `id` so findings can be de-duplicated across tools.
   `jfr_diagnose`, `jfr_use`, `jfr_tsa`, `pprof_use`, `otlp_use`, and `hdump_report` all emit it.
   Closes G2.
2. **`jfr_diagnose` runs the analyses it currently only recommends** (G6): USE and TSA in-process,
   with time windows, and merges their findings.
3. **`CrossSessionContext` over MCP** (G3): one shared registry facade so `hdump_query` can
   resolve a JFR session for `join(session=..., root=jdk.ObjectAllocationSample, by=class)`.
4. **`jfr_compare`** (G4): baseline vs candidate recording. First version is per-metric deltas
   of what `jfr_summary`, `jfr_hotmethods`, `jfr_use`, and `jfr_tsa` already compute, plus
   per-frame self-time deltas from `jfr_stackprofile`. A JfrPath `join(session=...)` for events
   can follow, mirroring the HdumpPath operator.
5. **MCP prompts and resources.** Prompts such as `triage`, `compare`, `leak-hunt` appear in Claude
   Code as `/mcp__jafar__triage`. Resources: `jafar://sessions` (open sessions and their types),
   `jafar://help/jfrpath`, `jafar://help/hdumppath`, `jafar://examples/<name>` for the `.jfrs`
   scripts. This puts the methodology next to the tools for every MCP client, not only Claude
   Code.
6. **`jfr_script`**: run a `.jfrs` script (bundled example or user-supplied) through the existing
   `ScriptRunner`, return the per-command results. Turns recipes into one call and gives the agent
   a reproducible artefact to attach to its report.

**Skills and agents.**

- Specialist subagents, each with only the tools it needs and one preloaded skill:
  `cpu-analyst` (`jfr_hotmethods`, `jfr_stackprofile`, `jfr_flamegraph`, `jfr_callgraph`),
  `concurrency-analyst` (`jfr_tsa`, `jfr_query` with the lock and park decorations),
  `memory-analyst` (allocation flamegraphs, GC stats, `hdump_*`, the cross join),
  `io-analyst` (`jfr_use resources=io`, `FileRead`/`SocketRead` queries),
  `heap-analyst` (`hdump_report`, detectors, clusters, `pathToRoot`).
- A `perf-lead` agent that runs `jfr_diagnose`, decides which specialists to dispatch (allowed
  via `tools: Agent(cpu-analyst, ...)`), collects `Finding` lists, ranks and de-duplicates by
  `id`, and writes the report using `jafar:report`.
- `jafar:compare` skill wrapping `jfr_compare` with a fixed "what changed, where, how confident"
  template.

**What the user gets.** Same entry point as A, but the report merges evidence from several
analyses without the model re-parsing free text, heap findings are attributed to allocation
sites, and "before vs after" is one call.

**Cost and risk.** A few weeks of Java plus the plugin. `jfr_compare` needs care around
recordings with different durations and sampling rates; report per-second and per-sample rates,
not raw counts. The Finding refactor touches the four largest tool classes; the existing test tiers
in `jfr-mcp/TESTING.md` cover the response shapes.

## 5. Alternative C (ambitious): Closed-loop performance engineer

**Thesis.** A performance engineer does not stop at a report. They find the code, change it,
measure again, and only then claim a win. Jafar can be the measurement half of that loop, and
Claude Code is already the code-change half.

**Additional Java changes.**

1. **`jfr_record` and `hdump_capture`** (G5): start, stop, and dump a recording on a local JVM
   via the attach API or `jcmd` (`JFR.start`, `JFR.dump`, `GC.heap_dump`), with settings
   presets (`profile`, allocation on, sampling interval). Gated behind an explicit server flag
   because it changes the target process. This is the capability that lets the agent produce its
   own evidence rather than wait for a file.
2. **Regression detection in `jfr_compare`**: per-frame and per-metric deltas with a noise floor
   estimated from the baseline's own time buckets (`jfr_stackprofile` already produces them,
   `JfrAnalysisTools.java:3216+`), so the tool says "significant" or "within noise", not just a
   number.
3. **Source mapping helper**: given a frame (`class.method:line`), return candidate files in the
   working tree. The model can do this with `Grep`, but a deterministic mapper avoids wrong
   matches on overloaded names.

**Skills, agents, and hooks.**

- `perf-fix` agent: takes one `Finding`, locates code, proposes a minimal change in a worktree
  (`isolation: worktree`), runs the project's benchmark or a scripted load with `jfr_record`,
  calls `jfr_compare` baseline vs candidate, and reports the delta with both `.jfr` files and a
  `.jfrs` script that reproduces the comparison. It never claims improvement without a
  `jfr_compare` result marked significant.
- `perf-regression-gate` workflow for CI: a GitHub Action that runs the benchmark with JFR on
  the PR and on the base, uploads both recordings, and invokes the agent (Claude Code Action or
  Agent SDK) to comment on the PR with attributable regressions and the query that shows each.
  The `bench/**` branch convention in `AGENTS.md:121-132` is a precedent for exactly this kind of
  gated benchmark run.
- Hooks in `hooks/hooks.json`: a `PostToolUse` hook on `jfr_compare` that persists the result
  JSON under the plugin data dir, so a `Stop` hook can refuse to end a `perf-fix` turn that
  claims a win without a stored significant comparison. This encodes the "prove it" rule
  mechanically.
- `memory: project` on the specialist agents so recurring hot frames, known-benign findings, and
  past fixes accumulate across sessions.

**What the user gets.** "Fix the top CPU finding in this recording" ends in a diff, two
recordings, a script, and a measured delta. In CI, a performance regression is reported on the PR
with the frame that regressed, before merge.

**Cost and risk.** Months, and the value depends on the target project having a runnable load
or benchmark. `jfr_record` is a security-relevant tool and must be opt-in. The Stop hook rule is
strict on purpose; teams can disable it, but the default should make unverified claims
impossible.

## 6. Alternative D (groundbreaking): Continuous JVM performance SRE

**Thesis.** Recordings do not only come from developers; production emits them continuously
(JFR repositories, continuous profilers exporting pprof or OTLP, heap dumps on OOM). Jafar
already parses every one of those formats in one runtime. Point an agent at the stream and let it
keep a model of each service's performance, notice drift, investigate, and open the issue with
the evidence attached.

**What has to be built.**

1. **Ingestion.** A `jafar-agent` process (Agent SDK, self-hosted) that watches sources: a
   directory or object store of JFR files, an OTLP profiles receiver (the `otlp-parser` already
   decodes `ProfilesData`), a pprof drop folder. New recordings become sessions automatically.
   JFR streaming (`jdk.jfr.consumer.EventStream`) can feed a rolling window rather than whole
   files.
2. **Per-service baseline store.** Not thresholds, distributions: per-endpoint self-time by
   frame, thread-state mix, allocation rate by class, GC pause quantiles, each keyed by build
   and time. `jfr_compare` from tier B is the primitive; the store is what turns it into "compared
   to the last 30 builds of this service".
3. **Hypothesis engine.** When drift is detected, the model composes JfrPath and HdumpPath
   queries as experiments (the decorations and joins are the instrument), records each query and
   result as a `.jfrs` transcript, and stops when a finding is supported or refuted. The
   transcript is the audit trail.
4. **Self-extension.** Findings that recur become detectors: the agent writes a `.jfrs` script,
   or for heap patterns a `LeakDetector` implementation
   (`hdump-shell/.../leaks/LeakDetector.java`), and opens a PR to this repo with a test recording.
   The repo's own contribution rules apply; a human merges.
5. **Multi-agent roles.** `observer` (cheap model, runs on schedule, only compares),
   `investigator` (full toolset, runs when observer flags drift), `fixer` (tier C `perf-fix`,
   opens PRs against the service repo), `reviewer` (independent verification of a fixer's claim
   using only the two recordings and the script). Roles are separate agent definitions with
   separate tool allowlists.
6. **Edge collector.** The Go parser (`go-parser/`, library only today) is the natural basis for
   a small collector that pre-aggregates on the host and ships summaries, keeping raw recordings
   local until an investigator asks for one.

**What the user gets.** An issue that says: "Since build 412, `OrderService.reprice` self time
rose from 3.1% to 9.4% of CPU on the checkout endpoint; the extra time is under
`HashMap.resize`; the heap dump from the 03:12 OOM shows 1.8 GB retained by `PriceCache`,
allocated at `reprice:118`; here are the two recordings, the dump, and the script that shows it."

**Cost and risk.** A product, not a feature. Needs storage, scheduling, secrets for the target
repos, and a policy for what the agent may change unattended. Baseline modelling on noisy
production profiles is the hard research problem; the rest is plumbing that Jafar's pieces already
cover.

## 7. Comparison

| | A: Guided Analyst | B: Findings + specialists | C: Closed loop | D: Continuous SRE |
|---|---|---|---|---|
| Java changes | none | Finding model, diagnose depth, MCP cross-session, `jfr_compare`, prompts/resources, `jfr_script` | plus `jfr_record`, `hdump_capture`, noise-aware compare, source mapper | plus ingestion, baseline store, collector |
| Plugin content | 8 skills, 1 agent, `.mcp.json` | plus 5 specialists, `perf-lead`, `compare` skill | plus `perf-fix`, CI workflow, hooks, project memory | standalone Agent SDK app with 4 roles |
| Trigger | human | human | human or CI | schedule and events |
| Loop closure | report | merged report with attribution | verified fix | detect, investigate, fix, review, extend |
| Gaps closed | G1, G7 | G2, G3, G4, G6 | G5 | all, plus G8 if scripts span formats |
| Rough size | days | weeks | months | quarters |
| Main risk | skills drift from code | compare semantics across dissimilar recordings | needs a runnable workload; recording tool is sensitive | baseline noise; unattended change policy |

## 8. Recommendation

Do A now; it is cheap, it makes the existing 36 tools usable by an agent, and it fixes the
documentation gap that currently misleads any model reading the repo. Do B as the next release
theme; the `Finding` model and `jfr_compare` are the two pieces every later tier needs, and the
MCP cross-session fix (G3) exposes the feature the heap roadmap calls the differentiator. Take
`perf-regression-gate` from C as a standalone third step, because it produces value without the
sensitive `jfr_record` tool: CI can produce the recordings. Treat D as the direction that
decides which of B's primitives to invest in, not as a project to start.

## 9. Things to fix regardless of tier

- `jfr-mcp/README.md:50-66` and `doc/mcp/Tutorial.md:20-34`: list all 36 tools and four formats.
- `HdumpTools.java:293`: pass a `CrossSessionContext` so the documented heap-to-JFR join works
  over MCP.
- `jafar-shell/.../unified/Main.java` reports `version = "0.10.0"` while `build.gradle:7` is
  `0.27.0-SNAPSHOT`.
- `CHANGELOG.md`: newest released entry is `[0.10.0] - 2026-02-14`; the shells for heap dumps,
  pprof, and OTLP were never announced.

# MCP server (`jfr-mcp`)

Tools, prompts, resources, the findings contract, and the plugin repository it feeds.

## MCP Server (`jfr-mcp`)
The `jfr-mcp` module exposes analysis capabilities as an MCP (Model Context Protocol) server, allowing AI agents (Claude, etc.) to analyze JFR recordings, pprof profiles, and OTLP profiles.

JFR tools: `jfr_open`, `jfr_close`, `jfr_list_types`, `jfr_query`, `jfr_help`, `jfr_summary`, `jfr_diagnose`, `jfr_compare`, `jfr_flamegraph`, `jfr_callgraph`, `jfr_hotmethods`, `jfr_exceptions`, `jfr_use`, `jfr_tsa`, `jfr_stackprofile`.

Cross-format tools: `profile_compare` (a JFR recording or a pprof profile against either).

Heap dump tools: `hdump_open`, `hdump_close`, `hdump_query`, `hdump_summary`, `hdump_report`, `hdump_help`.

pprof tools: `pprof_open`, `pprof_close`, `pprof_query`, `pprof_summary`, `pprof_flamegraph`, `pprof_use`, `pprof_hotmethods`, `pprof_tsa`, `pprof_help`.

OTLP profiling tools: `otlp_open`, `otlp_close`, `otlp_query`, `otlp_summary`, `otlp_flamegraph`, `otlp_use`, `otlp_help`.

Run the MCP server:
```bash
./gradlew :jfr-mcp:shadowJar
java -jar jfr-mcp/build/libs/jfr-mcp-*-all.jar --stdio   # STDIO mode
java -jar jfr-mcp/build/libs/jfr-mcp-*-all.jar           # HTTP mode (port 3000)
```

MCP prompts (analysis playbooks, surfaced as `/mcp__jafar__<name>` in Claude Code): `triage`, `compare`, `leak-hunt`, `latency`.
MCP resources: `jafar://sessions`, `jafar://help/jfrpath`, `jafar://help/hdumppath`, `jafar://help/tools`.

**Analysis tools emit structured findings.** `jfr_diagnose`, `jfr_use`, `jfr_tsa`, `jfr_compare`,
`pprof_use`, `otlp_use` and `hdump_report` all return a `findings` array of
`io.jafar.mcp.findings.Finding` maps (`id`, `severity`, `category`, `title`, `description`,
`source`, `evidence`, `action`, `query`). The `id` is stable, so findings from different tools
de-duplicate and merge — see `Findings.merge`. When adding a tool that makes a judgement, emit
findings in this shape rather than inventing another one.

**`jfr_compare` and `profile_compare` are adapters; the comparison is in `shell-core`.**
`io.jafar.shell.core.compare.ProfileCompare` holds the statistics (the noise floor, the sample
minimums, the z threshold), the sampler policy and the notes, and builds the rows and findings for
any two `RecordingProfile`s. `JfrCompare` reads a JFR recording into one (every sample weighs 1;
the Datadog `weight` field is not read) and delegates; `PprofProfileReader` (`pprof-shell`) reads a
pprof profile, and `ShellModule.profileReader()` is how `jfr-shell compare` finds it.
`JfrCompareTools` resolves session ids and adds `sessionId`/`alias` to each side;
`ProfileCompareTools` takes a `{format, sessionId}` object per side, because each format's
registry numbers its sessions from 1. `jfr-shell compare` prints the same result headless. The
core also computes inclusive (total-time) frame shares on request. `jfr_compare` does not request
them, so its response is unchanged; adding them there would change the MCP contract and needs its
own change. Read recordings through the server's injected evaluator
(`JfrAnalysisTools.querySource`), as the other core analyses do: `JfrCompareThinDataTest`
substitutes it to script exact sample counts.

Things a cross-format change must keep (design: [cross-format-compare.md](../plans/cross-format-compare.md)):

- **Weights give shares, counts give significance.** A pprof column in nanoseconds is a weight;
  the z test needs observations, from a `count` column or `value / period`. When neither exists the
  profile says `countsKnown = false` and no row is significant. Feeding weights to the z test
  inflates z by orders of magnitude.
- **The frame key is built in `FrameNames`, for both readers.** `stableName` first (its `/0x...`
  pattern needs the slashed form), then a JFR class name's `/` become `.`; a pprof name is dotted
  only when the whole name is a Java internal name, because Go functions and native paths contain
  `/`. `method` values in `jfr_compare` are therefore dotted (`java.lang.String.indexOf`).
- **The sampler policy is `CompareOptions.MixedSamplers`.** `jfr_compare` passes `NOTE` (two JFR
  event types of different kinds get the event-type note, as they always did); `profile_compare`
  and `jfr-shell compare` pass `REFUSE`, or `ALLOW` on request. Allocation against CPU or wall is
  refused under every policy.
- **pprof records no truncation.** Its profiles say `truncationKnown = false`; inclusive moves are
  still judged, with a note, and the description omits `truncatedStacks`.
- **A JFR side's description keeps exactly `jfr_compare`'s keys.** Other formats add `format`,
  `sampleUnit`, `samplerKind`, `totalWeight`, `sampleCountKnown` and `sampleTypeChosen`.
- **The round trip is the net.** `CrossFormatCompareTest` (`jafar-shell`) converts `tck-test.jfr`
  with jfr2pprof and requires identical frame keys and counts on both sides. It found two key bugs
  the unit tests could not: jfr2pprof rendered a class-less frame as `.sendto`, and the recording
  stores hidden-class addresses after a dot (`Handler$$Lambda.0x00007fd6a823c220`).

**`startTime` and `endTime` of `jfr_use` and `jfr_tsa` are nanoseconds from the recording's
start.** `JfrAnalyses.resolveTimeWindow` adds them to the start of the earliest chunk before
filtering, and takes a value at or after that start as an epoch timestamp already. The result's
`timeWindow` echoes the arguments and reports `recordingStartEpochNanos`, `startTimeEpochNanos` and
`endTimeEpochNanos`. Until this was written the arguments were compared with event start times
unconverted, and the tests only checked that `timeWindow` echoed them, not that anything was
selected.

See [jfr-mcp/README.md](../../jfr-mcp/README.md) and [doc/mcp/Tutorial.md](../../doc/mcp/Tutorial.md) for full documentation.

## Claude Code Plugin (`btraceio/agent-plugins`, a separate repository)
A Claude Code plugin turns the MCP server into a guided performance analyst: methodology skills
(`triage`, `cpu`, `latency`, `gc`, `memory-leak`, `heap-diff`, `compare`, `jfrpath`, `report`) and
subagents (`perf-lead` plus five specialists). It bundles `.mcp.json`, so installing it registers
the MCP server too.

**It lives in [btraceio/agent-plugins](https://github.com/btraceio/agent-plugins) (as
`plugins/jafar-perf`), not here.** Adding a marketplace clones its repository, and this one carries
several megabytes of binary test recordings a plugin user has no use for. That split has a cost, and
it is the one thing to remember:

> **When changing an MCP tool's name, parameters or response shape, update the affected skill files
> in `btraceio/agent-plugins`.** They name tools and parameters explicitly, they are not covered
> by this repository's tests, and stale guidance sends an agent down a path that no longer works.
> That repository's weekly `Tool drift` workflow (`scripts/check-tool-references.js`) catches a
> renamed or removed tool after a release, but not a changed parameter.

# jafar-perf — performance engineer in a box

A Claude Code plugin that turns the [Jafar MCP server](../../jfr-mcp/README.md) into a guided
JVM performance analyst.

The MCP server already exposes 37 analysis tools. What it does not carry is the *methodology*:
which question to ask next, which tool answers it, what counts as evidence, and how to report.
This plugin is that layer.

## Install

```
/plugin marketplace add btraceio/jafar
/plugin install jafar-perf@btraceio
```

The plugin bundles `.mcp.json`, so installing it also registers the `jafar` MCP server
(`jbang jfr-mcp@btraceio --stdio`). [JBang](https://www.jbang.dev) must be on your PATH; it
fetches the server on first use. No separate `claude mcp add` is needed.

## What is in it

### Skills

Invoked automatically when the work matches, or explicitly as `/jafar-perf:<name>`.

| Skill | Covers |
|---|---|
| `triage` | First step on any unfamiliar artifact: what it contains, what is anomalous, where to go next |
| `cpu` | Hot methods, call paths, convergence points, attributing samples to work |
| `latency` | Contention, parking, executor queue saturation, blocking I/O, per-endpoint attribution |
| `gc` | Pause distribution as a fraction of wall clock, heap behaviour, allocation hotspots |
| `memory-leak` | Retained sizes, dominators, GC root paths, leak detectors, heap-to-JFR correlation |
| `heap-diff` | Proving growth with two dumps instead of inferring it from one |
| `compare` | Before/after regression checks with a stated noise floor |
| `jfrpath` | Syntax reference for JfrPath, HdumpPath and SamplesPath |
| `report` | The output format and the evidence discipline every finding must meet |

### Agents

| Agent | Role |
|---|---|
| `perf-lead` | Triages, dispatches the specialists the evidence justifies, merges and ranks their findings |
| `perf-engineer` | General-purpose analyst for a single artifact, end to end |
| `cpu-analyst` | CPU-bound analysis |
| `concurrency-analyst` | Thread states, contention, queues |
| `memory-analyst` | GC and allocation |
| `heap-analyst` | Heap dumps and retention |
| `io-analyst` | File and socket I/O |

Specialists carry narrow tool allowlists, so each one works within its dimension rather than
wandering across the whole surface.

## Using it

Point it at an artifact and ask:

> Analyse `/tmp/recording.jfr` and tell me why p99 latency doubled after the last deploy.

For a broad investigation, ask for the lead agent, which fans out to specialists and merges
their findings:

> Use perf-lead to review `/tmp/recording.jfr`.

For a regression check, open both recordings and compare:

> Compare `/tmp/before.jfr` against `/tmp/after.jfr` and tell me what regressed.

## The standard these skills enforce

Every skill in this plugin pushes the same discipline, because it is what separates a
performance report from a guess:

- **Every claim names the tool call that produced it.** If you cannot cite the call and the
  numbers, the claim does not go in the report.
- **Rates, not counts.** Absolute counts are meaningless without the recording's duration and
  misleading across recordings of different lengths.
- **Sampling is not measurement.** Sampled data is labelled as sampled, and frames below the
  noise floor are not findings.
- **Absence of evidence is reported as such.** "No allocation hotspots found" is wrong when
  allocation profiling was never enabled; `jfr_diagnose` returns `capabilityGaps` for exactly
  this reason, and they belong in the report.
- **No claimed improvement without a measured comparison.**

## Without Claude Code

The methodology is also available from the server itself, so other MCP clients get it too:
prompts (`triage`, `compare`, `leak-hunt`, `latency`) and resources (`jafar://sessions`,
`jafar://help/jfrpath`, `jafar://help/hdumppath`, `jafar://help/tools`). The skills here go
further — they carry the interpretation rules and the failure modes — but the prompts cover
the sequence.

---
name: triage
description: First step for any unfamiliar JFR recording, pprof profile, OTLP profile, or heap dump. Establishes what the artifact contains, what is anomalous, and which specialised playbook to run next. Use when the user says "analyse this recording", "what is wrong with this JVM", "why is this slow", or hands over a .jfr/.hprof/.pprof/.otlp file without a specific question.
allowed-tools: mcp__jafar__jfr_open mcp__jafar__jfr_summary mcp__jafar__jfr_diagnose mcp__jafar__jfr_list_types mcp__jafar__hdump_open mcp__jafar__hdump_summary mcp__jafar__hdump_report mcp__jafar__pprof_open mcp__jafar__pprof_summary mcp__jafar__otlp_open mcp__jafar__otlp_summary
---

# Triage

Establish the shape of the problem before investigating it. Never open with a flamegraph:
a flamegraph of a recording that is 90% idle wastes a turn and misleads.

## 0. Identify the artifact

| Extension / magic | Tool family | Notes |
|---|---|---|
| `.jfr` | `jfr_*` | Java Flight Recording |
| `.hprof`, `.hdump` | `hdump_*` | Java heap dump |
| `.pprof`, `.pb.gz` | `pprof_*` | pprof profile (async-profiler, Go, Rust) |
| `.otlp` | `otlp_*` | OpenTelemetry profiles |

All four families share the same session model: `*_open` returns a session id, every other
tool defaults to the most recently opened session, `*_close` releases it. You may hold
sessions of several types at once — that is what makes correlation possible (see
`heap-diff` and the `join` operator).

## 1. Open and summarise

```
jfr_open   path=/abs/path/recording.jfr
jfr_summary
```

`jfr_summary` is a single pass over the recording. Read three things from it:

- `totalEvents` and `totalEventTypes` — is this a real workload or a 200-event smoke test?
- `topEventTypes` — the profile of the profile. A recording dominated by
  `jdk.ObjectAllocationSample` is a different investigation from one dominated by
  `jdk.ExecutionSample`.
- `highlights` — pre-computed `gc`, `exceptions` and `cpu` blocks.

## 2. Diagnose

```
jfr_diagnose
```

Returns `findings[]` and `recommendations[]`, and runs the USE and TSA analyses in-process so
the resource and thread-state picture arrives with the first call. Treat its output as a
*routing decision*, not a conclusion — it applies fixed thresholds and knows nothing about
your service's normal behaviour.

Read `capabilityGaps` before you believe a negative result. "ALLOCATION PROFILING: Not
enabled in this recording" means you cannot conclude anything about allocation, not that
allocation is fine.

## 3. Route

| What triage shows | Go to |
|---|---|
| High CPU sample count, hot leaf methods | `cpu` |
| Threads blocked, parked, or in monitor waits; queue saturation | `latency` |
| High GC pressure, high allocation rate, growing heap | `gc` |
| A heap dump, `OutOfMemoryError`, or memory that never comes back | `memory-leak` |
| Two recordings / two dumps of the same workload | `compare` or `heap-diff` |
| A specific question the built-in tools do not answer | `jfrpath` |

Run more than one when triage flags more than one. They are independent.

## 4. Establish the denominator

Before quantifying anything, know the recording's wall-clock duration. Every absolute count
in a JFR recording is meaningless without it — 10,000 exceptions in 30 seconds and 10,000
exceptions in 4 hours are different problems.

```
jfr_query query="events/jdk.ExecutionSample | timerange()"
```

Report rates, not raw counts, in anything the user reads.

## 5. Sampling is not measurement

`jdk.ExecutionSample` and `jdk.ObjectAllocationSample` are samples. A method with 3 samples
out of 20,000 is noise. Percentages below roughly 1% of total samples should not drive a
recommendation unless the sample count is very large. `jfr_stackprofile` marks frames with
a `category` field for this reason — prefer frames it calls `hotspot` or `steady-hotspot`.

## 6. Hand off

Write down, before moving on: the artifact path, its duration, the total event count, and
the two or three findings worth pursuing. The `report` skill defines the format. Every
subsequent claim must trace back to a tool call recorded here.

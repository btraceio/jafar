---
name: cpu
description: Find where CPU time goes in a JFR recording, pprof profile or OTLP profile, and attribute it to call paths and threads. Use when triage shows high execution-sample counts, when the user asks "why is the CPU pegged", "what is the hot method", "where is the time going", or asks for a flamegraph or profile of CPU usage.
allowed-tools: mcp__jafar__jfr_hotmethods mcp__jafar__jfr_stackprofile mcp__jafar__jfr_flamegraph mcp__jafar__jfr_callgraph mcp__jafar__jfr_query mcp__jafar__jfr_list_types mcp__jafar__pprof_hotmethods mcp__jafar__pprof_flamegraph mcp__jafar__otlp_flamegraph
---

# CPU analysis

## Pick the right tool

Four tools answer four different questions. Choosing wrong costs a turn and produces a
misleading answer.

| Question | Tool | Returns |
|---|---|---|
| Which methods burn CPU? | `jfr_hotmethods` | Flat ranked list of **leaf** frames with sample counts and percentages |
| How does the code reach them? | `jfr_flamegraph` | Aggregated stack paths, folded or tree |
| Which frames are hot, when, and on which threads? | `jfr_stackprofile` | Frames with self/total percentages, time buckets, per-thread counts, `hotspot` classification |
| Which function is the convergence point? | `jfr_callgraph` | Caller→callee edges with `inDegree` |

Start with `jfr_hotmethods`. It is one pass and it tells you whether the profile is
concentrated (one method at 40%) or flat (nothing above 3%). Those two shapes need opposite
follow-ups:

- **Concentrated** → `jfr_flamegraph direction=bottom-up` to find who calls the hot method.
- **Flat** → `jfr_flamegraph direction=top-down` or `jfr_callgraph`, because the cost is in a
  path, not a leaf. A framework that costs 30% spread over 50 leaves is invisible to
  `hotmethods` and obvious in a top-down view.

## Event type selection

The analysis tools auto-detect the execution-sample event type and prefer a Datadog
profiler's type over the JDK's when both are present. Check what you actually have:

```
jfr_list_types filter=ExecutionSample
```

`jdk.ExecutionSample` (JDK) and `datadog.ExecutionSample` (Datadog) have different sampling
intervals. Never compare sample counts across recordings that used different profilers —
see the `compare` skill.

## Native versus Java

`jfr_hotmethods` returns a `categoryBreakdown` with `native` and `java` counts, and each
method carries a `type`. A profile that is 60% native frames is usually one of: JIT
compilation, GC threads, or a JNI-heavy library. Set `includeNative=false` to see the Java
picture alone, then compare the two totals.

## Confirming a hotspot is real

A frame is worth reporting when all three hold:

1. Its self percentage is above the noise floor — roughly 1% of total samples, higher if the
   recording is short. `jfr_stackprofile` applies this and labels frames `hotspot`.
2. It is *steady*, not a spike. `jfr_stackprofile` returns `timeBuckets[]` per frame; a frame
   present in one bucket out of ten is an event, not a hotspot. The `steady-hotspot`
   category means it persisted.
3. It is not an artifact of one thread doing something unrepresentative. Check
   `threadCounts{}` in the same output.

```
jfr_stackprofile buckets=10 minPct=1.0
```

## Attributing CPU to work

Raw hotness rarely answers "why". Attribute samples to the request or endpoint that caused
them using event decoration:

```
jfr_query query="events/jdk.ExecutionSample | decorateByKey(datadog.Endpoint, key=localRootSpanId, decoratorKey=localRootSpanId, fields=endpoint) | groupBy($decorator.endpoint)"
```

For time-overlap correlation instead of a key join, use `decorateByTime` — see the
`latency` skill for the same technique applied to locks.

## Mapping frames to source

Once a frame is confirmed, find it in the working tree with `Grep` before recommending a
change. A method name alone is not a location: overloads, lambdas (`lambda$foo$0`), and
synthetic accessors all collapse in profiler output. Quote the file and line you found, and
say so if you could not find it.

## What not to conclude

- CPU samples during a GC pause are attributed to whatever thread was running; they do not
  mean the sampled method is expensive. Cross-check with the `gc` skill.
- A high sample count on `Unsafe.park`, `Object.wait` or socket reads is *not* CPU cost —
  those threads are not running. That is a `latency` question, not a CPU one.
- pprof and OTLP profiles infer thread state from function-name keywords, not from real
  state transitions. Their `tsa` and `use` output is heuristic and must be labelled as such
  in any report.

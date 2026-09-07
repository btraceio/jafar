---
name: gc
description: Analyse garbage collection pressure, pause times, heap sizing and allocation hotspots in a JFR recording. Use when triage reports high GC pressure, when the user asks about GC pauses, heap growth, allocation rate, OutOfMemoryError risk, or which code allocates the most.
allowed-tools: mcp__jafar__jfr_query mcp__jafar__jfr_use mcp__jafar__jfr_flamegraph mcp__jafar__jfr_list_types mcp__jafar__jfr_summary
---

# GC and allocation analysis

Two distinct questions live here. Answer them in order, because the second explains the
first:

1. **Is GC hurting?** Pause time as a fraction of wall clock, and pause distribution.
2. **Why is GC running?** Allocation rate and the code producing it.

## 1. Is GC hurting?

`jfr_summary` already carries a `highlights.gc` block with total collections, average pause
and total pause. Turn it into a fraction:

```
jfr_query query="events/jdk.GCPhasePause | stats(duration)"
jfr_query query="events/jdk.ExecutionSample | timerange()"
```

**Total pause ÷ wall clock** is the number that matters. 200 ms of pause in a 5-minute
recording is 0.07% and irrelevant no matter how alarming 200 ms sounds; 200 ms in a
2-second recording is 10% and dominant.

Then look at the distribution, not the mean. A mean of 20 ms hides a 900 ms outlier that is
the actual p99 complaint:

```
jfr_query query="events/jdk.GCPhasePause | quantiles(0.5, 0.9, 0.99, path=duration)"
jfr_query query="events/jdk.GCPhasePause | top(10, by=duration)"
```

## 2. Which collector, which phase?

```
jfr_query query="events/jdk.GarbageCollection | groupBy(name, agg=count)"
jfr_query query="events/jdk.GCPhasePause | groupBy(name, agg=sum, value=duration) | top(10, by=value)"
```

Young collections that are frequent but short are usually healthy — that is the collector
doing its job. Old/full collections, or concurrent-mode failures, are the signal. G1's
`Remark` and `Cleanup` phases are stop-the-world even though the cycle is "concurrent".

Which collector is in use, and its flags:

```
jfr_query query="events/jdk.ActiveSetting[name~\".*(GC|Heap).*\"] | select(name, value)"
```

## 3. Heap behaviour over time

```
jfr_query query="events/jdk.GCHeapSummary | select(startTime, heapUsed, when) | sortBy(startTime, asc=true)"
```

Read the *post-GC* used size (`when = "After GC"`). A sawtooth that returns to the same
floor is healthy churn. A floor that climbs monotonically across the recording is
retention — stop here and switch to the `memory-leak` skill, because no GC tuning fixes a
leak.

## 4. Why is GC running — allocation

Allocation profiling must be enabled or this section is unanswerable. Confirm first:

```
jfr_list_types filter=Alloc
```

- `jdk.ObjectAllocationSample` — sampled, cheap, available in the `profile` settings.
- `jdk.ObjectAllocationInNewTLAB` / `OutsideTLAB` — older, higher overhead, more detail.

If neither is present, say "allocation profiling was not enabled in this recording" and
recommend `-XX:StartFlightRecording:settings=profile` for the next one. Do not guess at
allocation from GC counts.

By class:

```
jfr_query query="events/jdk.ObjectAllocationSample | groupBy(objectClass/name, agg=sum, value=weight) | top(20, by=value)"
```

By allocation site — this is the actionable one, because it names the code:

```
jfr_flamegraph eventType=jdk.ObjectAllocationSample direction=bottom-up format=folded
```

`weight` on a sampled allocation event is an *estimate* of bytes represented by the sample,
not the bytes of that one object. Report it as an estimated rate (MB/s), never as an exact
total.

## 5. What is running during GC

```
jfr_query query="events/jdk.ExecutionSample | decorateByTime(jdk.GCPhase, fields=name) | groupBy($decorator.name, agg=count)"
```

Useful for separating application cost from collector cost when a profile looks unexpectedly
hot in JVM-internal frames.

## Recommendations worth making

In rough order of expected value:

1. **Reduce allocation** at the top sites found in step 4. This is the only fix that helps
   every collector and every heap size.
2. **Right-size the heap** when post-GC used is close to max and collections are frequent.
   Cite the `GCHeapSummary` numbers.
3. **Change collector or pause target** only with pause-distribution evidence from step 1,
   and only when allocation is already understood.

Never recommend a flag without the measurement that motivates it. "Try G1" is not a finding.

---
name: compare
description: Decide whether a candidate JFR recording regressed against a baseline, and attribute the change to a frame or a metric. Use for before/after checks, "is this build slower", bisecting a performance regression, verifying that a fix actually helped, or any question involving two recordings of the same workload.
allowed-tools: mcp__jafar__jfr_open mcp__jafar__jfr_compare mcp__jafar__jfr_hotmethods mcp__jafar__jfr_stackprofile mcp__jafar__jfr_query mcp__jafar__jfr_summary
---

# Comparing two recordings

The claim "this is slower" is only worth making with two measurements and a stated noise
floor. `jfr_compare` provides both.

## Run it

```
jfr_open path=/abs/path/before.jfr alias=before
jfr_open path=/abs/path/after.jfr  alias=after
jfr_compare baselineSessionId=before candidateSessionId=after
```

Optional: `eventType` to pin the execution-sample type, `minDeltaPct` to set the noise
floor in percentage points (default 1.0), `limit` for how many changed frames to return.

## Read `comparability` first

Before any number, the result tells you whether the comparison is sound. It flags:

- **Different execution-sample event types** — the two recordings used different profilers
  (`jdk.ExecutionSample` versus `datadog.ExecutionSample`). Frame shares remain roughly
  comparable; sample counts are not comparable at all.
- **Durations differing by more than 3×** — rates are normalised, but a much shorter
  recording may simply have missed periodic work such as a full GC or a cache refresh.
- **Fewer than ~1000 samples on either side** — per-frame shares are noisy; small moves mean
  nothing.

If any of these fire, say so in your answer and weaken the conclusion accordingly. A
regression claim that ignores a comparability warning is worse than no claim.

## What the numbers mean

**`metrics`** are per-second rates, computed with each recording's own observed span as the
denominator. Compare `baselineRate` to `candidateRate`; `baselineCount` and
`candidateCount` are shown for transparency, not for comparison.

**`frames`** are shares of execution samples, in percentage points:

- `baselineSelfPct` → `candidateSelfPct`, with `deltaPct` the difference in points.
- `direction` is `regression` when the share grew, `improvement` when it shrank.
- Frames moving less than `minDeltaPct` are omitted deliberately. Do not go hunting for
  smaller moves and present them as findings.

The single most common error to avoid: **a share is not a duration**. A frame growing from
3% to 9% of samples means the profile's shape changed. If total CPU work also fell, that
frame may be no slower in absolute terms — it just became a bigger slice of a smaller pie.
Cross-check the rates before calling a share change a slowdown.

## Attribute the change

`jfr_compare` names the frame. Finding out *why* takes one more step:

```
jfr_stackprofile sessionId=after buckets=10
jfr_stackprofile sessionId=before buckets=10
```

Compare the call paths reaching the changed frame, and its `timeBuckets` — a frame that
regressed only in the last two buckets points at state that accumulated (a growing
collection, a filling cache), not at a code path that got slower.

Then locate the code with `Grep` and state the file and line.

## When nothing changed

The tool returns an explicit "no regression above the noise floor" finding. Report exactly
that. It is not the same as "the two builds perform identically": a change smaller than
sampling noise is invisible to this method, and you should say so rather than implying
equivalence.

## Verifying a fix

Same workload, same duration, same profiler settings, same JVM flags — otherwise the
comparison measures your test setup rather than the fix. Then:

1. Record the baseline before the change.
2. Apply the change, record again with identical settings.
3. `jfr_compare` and read the frame you expected to move.

A fix is confirmed when the frame you targeted shrank *and* the comparability block is
clean. If the targeted frame did not move but something else did, you have learned that
your model of the problem was wrong — report that, rather than claiming a win from an
unrelated improvement.

---
name: report
description: The output format and evidence discipline for any performance finding produced with the Jafar tools. Use whenever writing up an analysis, summarising an investigation, answering "what did you find", or handing conclusions to another person or agent.
---

# Reporting a performance finding

A performance report is an argument, and an argument needs evidence. The reader must be able
to re-run every number you quote. That is the whole standard.

## Format

Report findings ranked by impact, each in this shape:

> **Symptom** — what the user or the system observes.
>
> **Evidence** — the exact tool call and the numbers it returned.
>
> **Interpretation** — what the numbers mean, and why this explanation rather than another.
>
> **Recommendation** — the specific change, at a named location.
>
> **Confidence** — high / medium / low, and what would raise it.

Keep it short. Three well-evidenced findings beat twelve speculative ones.

## The evidence rule

Every quantitative claim names the tool call that produced it:

> `jdk.JavaMonitorEnter` on `com.example.SessionCache` accounts for 41.2 s of blocked time
> across a 300 s recording (13.7% of wall clock).
> Evidence: `jfr_query query="events/jdk.JavaMonitorEnter | groupBy(monitorClass, agg=sum, value=duration) | top(10, by=value)"` → `SessionCache` 41,203,441,000 ns; recording duration from `timerange()` = 300.4 s.

If you cannot name the call, you cannot make the claim. Delete it or go and measure it.

## Rates, not counts

Absolute counts are meaningless without the recording duration, and misleading when
comparing recordings of different lengths. Convert:

- events → events per second
- durations → percentage of wall clock, or of the thread's own time
- allocation → MB/s
- samples → percentage of total samples

State the denominator you used.

## Confidence, honestly

| Level | When |
|---|---|
| **High** | Direct measurement of the thing itself, large sample, corroborated by a second independent tool |
| **Medium** | Strong single-tool signal, or an inference from a well-understood mechanism |
| **Low** | Heuristic, small sample, correlation in time only, or a known-approximate source |

Things that force *at most* medium confidence, and must be said out loud:

- Sampled data (execution samples, allocation samples) — you have a sample, not a census.
- `decorateByTime` correlations — concurrency in time is not causation.
- pprof and OTLP thread states — inferred from function-name keywords, not real states.
- Retained sizes from an approximate dominator computation.
- Any recording where the relevant profiling was not enabled — see below.

## Absence of evidence

When the recording cannot answer the question, say so explicitly and separately from the
findings. "No allocation hotspots found" is wrong if allocation profiling was off; the true
statement is "allocation profiling was not enabled in this recording, so allocation was not
assessed", plus how to enable it next time.

`jfr_diagnose` reports these as capability gaps. Carry them into the report rather than
silently dropping them.

## Reproducibility

End with the exact steps, so the reader can reproduce the result:

```
jfr_open path=/abs/path/recording.jfr
jfr_diagnose
jfr_query query="events/jdk.JavaMonitorEnter | groupBy(monitorClass, agg=sum, value=duration) | top(10, by=value)"
```

For a regression claim, both artifacts and the comparison call are the reproduction — see
the `compare` skill.

## What a recommendation must contain

Not "reduce allocations" but: the file and line, the change, and the expected effect with
its basis.

> `OrderService.reprice` (`src/main/java/com/example/OrderService.java:118`) allocates a new
> `HashMap` per call inside the pricing loop; it accounts for 34% of sampled allocation
> weight. Hoisting it out of the loop, or presizing it, should remove most of that share.
> Expected effect is on allocation rate and young-GC frequency, not on p99 directly —
> confirm with a before/after `jfr_compare`.

If you did not locate the code, say that you did not, and give the frame instead of
inventing a path.

## Never

- Do not report a number you did not measure in this session.
- Do not present a threshold breach as a diagnosis. `jfr_diagnose` applies fixed thresholds
  that know nothing about this service's normal behaviour; a breach is a lead.
- Do not claim an improvement without a measured comparison. "This should be faster" is a
  hypothesis, and must be labelled as one.

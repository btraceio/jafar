---
name: latency
description: Investigate response-time problems that are not CPU-bound — lock contention, thread parking, executor queue saturation, blocking I/O, and per-endpoint latency attribution. Use when the user reports slow requests, p99 spikes, timeouts, deadlock suspicion, or when triage shows threads blocked rather than running.
allowed-tools: mcp__jafar__jfr_tsa mcp__jafar__jfr_use mcp__jafar__jfr_query mcp__jafar__jfr_list_types mcp__jafar__jfr_stackprofile mcp__jafar__pprof_tsa
---

# Latency analysis

Latency problems are usually *waiting*, and waiting is invisible to CPU profiling. A thread
blocked on a monitor produces no execution samples; the flamegraph looks healthy while the
p99 is ruined.

## 1. Where is the time spent not running?

```
jfr_tsa correlateBlocking=true
```

Thread State Analysis returns:

- `stateDistribution` — the share of thread time in each state. This is the headline number.
- `threadProfiles` and `topThreadsByState` — which threads, not just how many.
- `correlations` — monitor classes and executor queues implicated in blocking.
- `insights.problematicThreads[]` — each with its own `recommendation`.

Read `stateDistribution` first. If most time is `RUNNABLE`, this is a CPU problem — switch to
the `cpu` skill. If it is dominated by blocked, waiting or parked states, continue here.

## 2. Which resource is saturated?

```
jfr_use resources=all
```

The USE method (Utilization, Saturation, Errors) applied to CPU, memory, threads and I/O.
Each resource carries an `assessment`; `insights.bottlenecks[]` names the saturated ones as
`cpu_saturation`, `memory_pressure`, `thread_contention` or `queue_saturation`.

`queue_saturation` is the one most often missed: an executor whose queue depth grows means
requests wait before any code runs for them. No amount of method optimisation fixes it.

Narrow the window when the recording spans a mix of load levels:

```
jfr_use startTime=<ns> endTime=<ns> resources=threads
```

## 3. Which lock?

Monitor contention shows up as `jdk.JavaMonitorEnter` (blocked acquiring) and
`jdk.JavaMonitorWait` (waiting on a condition). They mean different things — do not merge
them.

```
jfr_query query="events/jdk.JavaMonitorEnter | groupBy(monitorClass, agg=sum, value=duration) | top(10, by=value)"
```

To find *what code* was contending, correlate execution samples with the wait window on the
same thread:

```
jfr_query query="events/jdk.ExecutionSample | decorateByTime(jdk.JavaMonitorWait, fields=monitorClass,duration) | groupBy($decorator.monitorClass, agg=count) | top(10, by=count)"
```

`decorateByTime` joins events that overlap in time **on the same thread** (thread path
defaults to `eventThread/javaThreadId`). Rows where `$decorator.monitorClass` is null were
sampled outside any wait — that is the uncontended baseline, and it belongs in the report
as the comparison.

## 4. Parking and sleeping

`jdk.ThreadPark` covers `LockSupport.park`, which is what every `java.util.concurrent` lock,
queue and future uses. Group by the parked class to tell a healthy idle pool from a stalled
one:

```
jfr_query query="events/jdk.ThreadPark | groupBy(parkedClass/name, agg=sum, value=duration) | top(10, by=value)"
```

A thread pool parked on its own work queue is idle and healthy. A request thread parked on a
`CompletableFuture` or a connection pool is a latency bug.

## 5. Per-endpoint attribution

When the recording carries request context (a Datadog profiler's `datadog.Endpoint`, or your
own event type), attribute waiting to the endpoint that suffered it:

```
jfr_query query="events/jdk.JavaMonitorEnter | decorateByKey(datadog.Endpoint, key=localRootSpanId, decoratorKey=localRootSpanId, fields=endpoint) | groupBy($decorator.endpoint, agg=sum, value=duration)"
```

`decorateByKey` is a correlation-key join, not a time join — use it whenever a shared id
exists, because it is both cheaper and exact.

## 6. Blocking I/O

```
jfr_query query="events/jdk.SocketRead[duration > 10ms] | groupBy(address, agg=sum, value=duration) | top(10, by=value)"
jfr_query query="events/jdk.FileRead[duration > 10ms] | groupBy(path, agg=count) | top(10, by=count)"
```

Filters accept duration literals (`10ms`, `1s`) and size units. Slow I/O to one address is a
dependency problem, not a JVM problem — say so plainly rather than proposing JVM tuning.

## What not to conclude

- A high *count* of monitor events is not contention; a high *summed duration* relative to
  the recording's wall clock is. Always divide by the recording duration.
- JFR's monitor events have a duration threshold (commonly 10 ms or 20 ms depending on
  settings). Contention below the threshold is invisible, so absence of events is not
  absence of contention. Check `jdk.ActiveSetting` if the threshold matters to the
  conclusion.
- `jfr_tsa` correlations are associations in time, not proof of causation. Report them as
  "concurrent with", and prove causation with a code path or a fix that measurably helps.

# pprof Analysis Tutorial

This tutorial teaches you how to use JAFAR's pprof analysis capabilities for exploring CPU,
allocation, and lock profiles in the gzip-compressed protobuf format used by
[async-profiler](https://github.com/async-profiler/async-profiler), Go, and other profilers.

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Obtaining a pprof Profile](#obtaining-a-pprof-profile)
3. [Opening a Profile](#opening-a-profile)
4. [Understanding the Data Model](#understanding-the-data-model)
5. [Basic Queries](#basic-queries)
6. [Filtering Samples](#filtering-samples)
7. [Aggregation and Statistics](#aggregation-and-statistics)
8. [Flame Graph Data](#flame-graph-data)
9. [Common Analysis Patterns](#common-analysis-patterns)
10. [Tips and Best Practices](#tips-and-best-practices)

## Prerequisites

- JAFAR shell installed (see [main README](../../README.md))
- A pprof file (`.pprof` or `.pb.gz`)

## Obtaining a pprof Profile

### async-profiler (Java)

```bash
# CPU profile, 30 seconds, pprof output
asprof -d 30 -f profile.pb.gz <pid>

# Allocation profile
asprof -e alloc -d 30 -f alloc.pb.gz <pid>

# Wall-clock profile
asprof -e wall -d 30 -f wall.pb.gz <pid>
```

### Go runtime

```go
import (
    "os"
    "runtime/pprof"
)

// CPU profile
f, _ := os.Create("cpu.pprof")
pprof.StartCPUProfile(f)
// ... run workload ...
pprof.StopCPUProfile()

// Heap profile
f, _ := os.Create("heap.pprof")
pprof.WriteHeapProfile(f)
```

Or via the `net/http/pprof` endpoint:

```bash
curl -s http://localhost:6060/debug/pprof/profile?seconds=30 > cpu.pprof
curl -s http://localhost:6060/debug/pprof/heap > heap.pprof
```

### Datadog Continuous Profiler

pprof files can be downloaded from the Datadog UI under any profile in the
"Download profile" menu.

## Opening a Profile

Pass the file directly on the command line:

```bash
jafar-shell profile.pb.gz
```

Or start the shell and open it interactively:

```bash
jafar-shell
jafar> open profile.pb.gz
```

When a profile opens, the shell displays a summary:

```
Opened pprof profile: profile.pb.gz
  format:      pprof
  samples:     45,230
  locations:   18,774
  functions:   6,421
  mappings:    3
  sampleTypes: cpu (nanoseconds), alloc_objects (count)
  duration:    30.00 s
  collectedAt: 2024-03-15T12:00:00Z
```

The prompt changes to indicate the active session:

```
pprof[profile.pb.gz]>
```

## Understanding the Data Model

Every pprof profile contains a list of **samples**. Each sample records:

- One **value** per declared sample type (e.g. `cpu` in nanoseconds, `alloc_objects` as a count)
- A **stack trace** — an ordered list of frames from leaf (hottest) to root
- Zero or more **labels** — string or numeric key-value pairs attached by the profiler

In PprofPath queries, each sample becomes a row with these fields:

| Field | Type | Description |
|-------|------|-------------|
| `<value type name>` | `long` | One field per sample type (e.g. `cpu`, `alloc_objects`, `samples`) |
| `stackTrace` | `List<Map>` | Frames: `{name, filename, line}`, leaf-first |
| `thread` | `String` | Thread name (from label, if present) |
| `goroutine` | `long` | Goroutine ID (from label, if present) |
| *other labels* | `String`/`long` | Any profiler-specific labels |

Value type names come directly from the profile's `sampleType` declarations. The `stats` command
shows what types are present for the currently open profile.

## Basic Queries

All queries start with `samples` — the only root in PprofPath.

### Count all samples

```
pprof> show samples | count()
Result: 45230
```

### View raw samples

```
pprof> show samples | head(5)
```

### Profile statistics

```
pprof> show samples | stats(cpu)

| field | count | sum         | min  | max        | avg    |
|-------|-------|-------------|------|------------|--------|
| cpu   | 45230 | 30000000000 | 1000 | 2048000000 | 663250 |
```

### List distinct threads

```
pprof> show samples | distinct(thread)

| thread                     |
|----------------------------|
| main                       |
| ForkJoinPool-1-worker-1    |
| GC Thread#0                |
```

### Inspect a stack frame

`stackTrace` is a list of frame maps. Access individual frames with a `/`-delimited path:

```
# Leaf function name of the first 10 samples
pprof> show samples | select(stackTrace/0/name) | head(10)
```

## Filtering Samples

Add a `[predicate]` after `samples` to restrict which samples are included.

### Filter by thread

```
pprof> show samples[thread='main'] | count()
```

### Filter by value threshold

```
# Only samples with more than 10ms of CPU time
pprof> show samples[cpu > 10000000] | count()
```

### Filter by leaf function (regex)

```
pprof> show samples[stackTrace/0/name ~ 'HashMap.*'] | count()
```

### Boolean logic

```
# Samples on the main thread with >5ms CPU
pprof> show samples[thread='main' and cpu > 5000000] | top(10, cpu)
```

Supported operators:

| Operator | Meaning |
|----------|---------|
| `=` or `==` | Equal |
| `!=` | Not equal |
| `>`, `>=`, `<`, `<=` | Numeric or lexicographic comparison |
| `~` | Regex match |
| `and` / `&&` | Logical AND |
| `or` / `\|\|` | Logical OR |

String literals can be single-quoted, double-quoted, or unquoted (bare word).

## Aggregation and Statistics

### Top N hot functions by CPU

```
pprof> show samples | groupBy(stackTrace/0/name, sum(cpu)) | head(10)

| stackTrace/0/name                       | sum_cpu      |
|-----------------------------------------|--------------|
| java.util.HashMap.get                   | 4,123,456,789 |
| io.netty.channel.DefaultChannelPipeline | 2,987,654,321 |
| sun.nio.ch.SelectorImpl.select          | 1,876,543,210 |
```

### Top N samples sorted by value

```
# Top 10 samples by CPU, descending (default)
pprof> show samples | top(10, cpu)

# Top 10 samples by CPU, ascending
pprof> show samples | top(10, cpu, asc)
```

When no field is given, `top` sorts by the first declared sample type:

```
pprof> show samples | top(10)
```

### Group by thread

```
pprof> show samples | groupBy(thread, sum(cpu)) | head(10)

| thread                  | sum_cpu       |
|-------------------------|---------------|
| main                    | 12,000,000,000 |
| ForkJoinPool-1-worker-1 | 8,500,000,000  |
| GC Thread#0             | 3,200,000,000  |
```

### Group by thread — count samples

```
pprof> show samples | groupBy(thread)

| thread                  | count |
|-------------------------|-------|
| main                    | 18234 |
| ForkJoinPool-1-worker-1 | 14021 |
```

### Statistics for a value field

```
pprof> show samples | stats(cpu)
pprof> show samples | stats(alloc_objects)
```

### Sort results

```
pprof> show samples | groupBy(thread, sum(cpu)) | sortBy(sum_cpu, asc)
```

### Select specific fields

```
pprof> show samples | select(cpu, thread, stackTrace/0/name) | head(20)
```

### Last N samples

```
pprof> show samples | tail(5)
```

## Flame Graph Data

`stackprofile()` aggregates samples into a folded stack format suitable for
any flame graph renderer (e.g. Brendan Gregg's `flamegraph.pl`, speedscope,
FlameGraph.js).

```
pprof> show samples | stackprofile()

| stack                                          | cpu          |
|------------------------------------------------|--------------|
| main;processRequest;readDatabase;executeQuery  | 5,432,100,000 |
| main;processRequest;serializeResponse          | 2,109,876,543 |
| ForkJoinPool-1-worker-1;compute;sort           | 1,234,567,890 |
```

Each row contains:
- `stack` — semicolon-separated frame names, root first
- The value field (defaults to the first sample type, `cpu` in this example)

Specify a different value type:

```
pprof> show samples | stackprofile(alloc_objects)
```

### Filter before profiling

```
# Flame graph for the main thread only
pprof> show samples[thread='main'] | stackprofile()

# Exclude GC threads
pprof> show samples[thread != 'GC Thread#0'] | stackprofile(cpu)
```

Pipe the output to a flame graph tool using the shell's non-interactive mode:

```bash
jafar-shell show profile.pb.gz "samples | stackprofile()" | flamegraph.pl > flamegraph.svg
```

## Common Analysis Patterns

### CPU hot spots

```
# Top 10 leaf functions by total CPU time
pprof> show samples | groupBy(stackTrace/0/name, sum(cpu)) | head(10)

# Top 10 caller functions (one frame above leaf)
pprof> show samples | groupBy(stackTrace/1/name, sum(cpu)) | head(10)
```

### Thread breakdown

```
pprof> show samples | groupBy(thread, sum(cpu)) | head(20)
```

### Allocation hot spots (async-profiler alloc profile)

```
# Top allocating functions by object count
pprof> show samples | groupBy(stackTrace/0/name, sum(alloc_objects)) | head(10)

# Top allocating functions by bytes
pprof> show samples | groupBy(stackTrace/0/name, sum(alloc_space)) | head(10)

# Per-thread allocation
pprof> show samples | groupBy(thread, sum(alloc_space)) | head(10)
```

### Lock / contention analysis (async-profiler lock profile)

```
# Top locks by hold time
pprof> show samples | groupBy(stackTrace/0/name, sum(lock_duration)) | head(10)

# Top locks by count
pprof> show samples | groupBy(stackTrace/0/name, sum(lock_count)) | head(10)
```

### Goroutine analysis (Go profiles)

```
pprof> show samples | distinct(goroutine)
pprof> show samples | groupBy(goroutine, sum(cpu)) | head(10)
```

### Narrow to one component

```
# Only samples touching your package (regex on any frame is not directly supported,
# but you can filter on the leaf frame)
pprof> show samples[stackTrace/0/filename ~ 'com/example/.*'] | groupBy(stackTrace/0/name, sum(cpu)) | head(20)
```

### Multi-session comparison

Open two profiles and switch between them:

```
pprof> open before.pb.gz
Opened session #1: before.pb.gz

pprof> open after.pb.gz
Opened session #2: after.pb.gz

pprof> use 1
pprof> show samples | groupBy(stackTrace/0/name, sum(cpu)) | head(10)

pprof> use 2
pprof> show samples | groupBy(stackTrace/0/name, sum(cpu)) | head(10)
```

## Tips and Best Practices

### Know your value types first

Run `info` after opening a profile to see which sample types are available:

```
pprof> info
```

Then use `stats` to get a sense of scale before writing queries:

```
pprof> show samples | stats(cpu)
```

### Filter early

Predicates applied on `samples[...]` run before pipeline operators, so they reduce
the dataset efficiently:

```
# Efficient: filter first
pprof> show samples[thread='main'] | groupBy(stackTrace/0/name, sum(cpu)) | head(10)

# Less efficient: groupBy everything, then discard
pprof> show samples | groupBy(thread, sum(cpu)) | filter(thread = main) | head(10)
```

### Tab completion

Press `Tab` at any point to complete operator names and field names:

```
pprof> show samples | g<TAB>      # → groupBy(
pprof> show samples | groupBy(<TAB>  # → field suggestions
```

### Non-interactive mode

Run a single query and exit — useful for scripts:

```bash
jafar-shell show profile.pb.gz "samples | groupBy(stackTrace/0/name, sum(cpu)) | head(20)"
```

## Next Steps

- Read the [PprofPath Reference](../pprof-path-reference.md) for complete query syntax
- Explore the [JFR Shell Tutorial](Tutorial.md) for JFR analysis
- See [Heap Dump Tutorial](hdump-shell-tutorial.md) for heap dump analysis

# JFR Shell Tutorial

This tutorial teaches you how to use JFR Shell, an interactive CLI for exploring and analyzing Java Flight Recorder files. JFR Shell provides a powerful query language (JfrPath) and REPL environment for rapid JFR analysis.

## Table of Contents
1. [Installation](#installation)
2. [Quick Start](#quick-start)
3. [Basic Commands](#basic-commands)
4. [JfrPath Query Language](#jfrpath-query-language)
5. [Event Analysis](#event-analysis)
6. [Metadata Exploration](#metadata-exploration)
7. [Aggregations and Statistics](#aggregations-and-statistics)
8. [Advanced Queries](#advanced-queries)
9. [Event Decoration and Joining](#event-decoration-and-joining)
10. [Multi-Session Management](#multi-session-management)
11. [Non-Interactive Mode](#non-interactive-mode)
12. [Real-World Examples](#real-world-examples)

## Installation

### Option 1: JBang (Recommended)

The easiest way to run JFR Shell:

```bash
# One-time setup
curl -Ls https://sh.jbang.dev | bash -s - app setup

# Run directly
jbang jfr-shell@btraceio recording.jfr

# Or install permanently
jbang app install jfr-shell@btraceio
jfr-shell recording.jfr
```

### Option 2: Build from Source

```bash
git clone https://github.com/btraceio/jafar.git
cd jafar
./gradlew :jfr-shell:shadowJar
java -jar jfr-shell/build/libs/jfr-shell-*.jar recording.jfr
```

### Option 3: Standalone Distribution

```bash
./gradlew :jfr-shell:jlinkDist
jfr-shell/build/jlink/bin/jfr-shell recording.jfr
```

## Quick Start

Launch JFR Shell and open a recording:

```bash
$ jfr-shell recording.jfr

JFR Shell - Interactive JFR Analysis
Type 'help' for commands, 'exit' to quit

Opened session #1: recording.jfr
jfr>
```

Or start without a file and open later:

```bash
$ jfr-shell

jfr> open recording.jfr
Opened session #1: recording.jfr

jfr>
```

Now you can start querying!

## Basic Commands

### Session Management

```bash
# Open a recording
jfr> open /path/to/recording.jfr
Opened session #1: recording.jfr

# Open with alias
jfr> open /path/to/recording.jfr --alias prod
Opened session #1 (prod): recording.jfr

# List sessions
jfr> sessions
  #1* (prod): recording.jfr [45 event types, 3 chunks]

# Switch sessions
jfr> use 2
Switched to session #2

# Close session
jfr> close prod
Closed session #1 (prod)

# Session info
jfr> info
Session #1: recording.jfr
  Events: 45 types
  Chunks: 3
  Size: 125 MB
```

### Help System

```bash
# General help
jfr> help

# Command-specific help
jfr> help show
jfr> help metadata

# JfrPath syntax help
jfr> help jfrpath
```

### Output Format

```bash
# Table format (default)
jfr> show events/jdk.FileRead --limit 5

# JSON format
jfr> show events/jdk.FileRead --limit 5 --format json

# Tree format (for nested data)
jfr> show metadata/jdk.types.StackTrace --tree --depth 2
```

## JfrPath Query Language

JfrPath is a concise path-based query language for JFR data.

### Basic Syntax

```
<root>/<path>[filters]/<projection> | <aggregation>
```

### Roots

Four query roots available:

```bash
# Events - actual recorded data
events/jdk.ExecutionSample

# Metadata - type structure information
metadata/java.lang.Thread

# Chunks - recording chunk information
chunks

# Constant pools
cp/jdk.types.Symbol
```

### Path Navigation

Navigate nested structures with `/`:

```bash
# Simple field
show events/jdk.FileRead/path

# Nested field
show events/jdk.ExecutionSample/thread/name

# Deep nesting
show events/jdk.ExecutionSample/stackTrace/frames/0/method/name
```

### Filters

Use `[...]` to filter results:

```bash
# Numeric comparison
show events/jdk.FileRead[bytes>1000000]

# String equality
show events/jdk.ExecutionSample[state="RUNNABLE"]

# Regex match
show events/jdk.FileRead[path~"/tmp/.*"]

# Nested field
show events/jdk.ExecutionSample[thread/name~"main"]

# Multiple conditions
show events/jdk.FileRead[bytes>1000 and path~".*\\.log"]
```

### Operators

- `=` - Equal
- `!=` - Not equal
- `>`, `>=`, `<`, `<=` - Numeric comparison
- `~` - Regex match
- `and`, `or`, `not` - Boolean logic

### Projections

Extract specific fields:

```bash
# Single field
show events/jdk.FileRead/path

# Nested field
show events/jdk.ExecutionSample/thread/name

# Array element
show events/jdk.ExecutionSample/stackTrace/frames/0
```

## Event Analysis

### Counting Events

```bash
# Count all events of a type
jfr> show events/jdk.ExecutionSample | count()
Result: 45234

# Count with filter
jfr> show events/jdk.FileRead[bytes>1048576] | count()
Result: 127
```

### Listing Events

```bash
# List first 10 events
jfr> show events/jdk.FileRead --limit 10

# List with specific fields
jfr> show events/jdk.FileRead/path --limit 10

# Filter and list
jfr> show events/jdk.FileRead[bytes>=1000000] --limit 5
| startTime           | duration | path              | bytes    |
+---------------------+----------+-------------------+----------+
| 2024-01-15 10:23:41 | 1234567  | /tmp/data.txt     | 5242880  |
| 2024-01-15 10:23:42 | 2345678  | /var/log/app.log  | 10485760 |
```

### Finding Patterns

```bash
# Find file reads from /tmp
jfr> show events/jdk.FileRead[path~"/tmp/.*"] --limit 10

# Find high-latency operations
jfr> show events/jdk.SocketRead[duration>10000000] --limit 10

# Find specific thread activity
jfr> show events/jdk.ExecutionSample[thread/name="ForkJoinPool-1"] --limit 10
```

## Metadata Exploration

### Listing Event Types

```bash
# List all event types
jfr> metadata --events-only

# Search for event types
jfr> metadata --search "jdk.File*" --events-only

# List JDK events
jfr> metadata --search "jdk.*" --events-only
```

### Exploring Type Structure

```bash
# Show type structure
jfr> show metadata/jdk.types.StackTrace
{
  "name": "jdk.types.StackTrace",
  "fields": [
    {"name": "truncated", "type": "boolean"},
    {"name": "frames", "type": "jdk.types.StackFrame[]"}
  ]
}

# Tree view
jfr> show metadata/jdk.types.StackTrace --tree --depth 2
jdk.types.StackTrace
  ├─ truncated: boolean
  └─ frames: jdk.types.StackFrame[]
       ├─ method: jdk.types.Method
       ├─ lineNumber: int
       └─ bytecodeIndex: int

# List field names
jfr> show metadata/jdk.types.Method/fields/name
["type", "name", "descriptor", "modifiers", "hidden"]
```

### Type Relationships

```bash
# Show supertype
jfr> show metadata/java.lang.Thread/superType

# Show all fields
jfr> show metadata/java.lang.Thread/fields
```

## Aggregations and Statistics

### Grouping

```bash
# Group by field
jfr> show events/jdk.ExecutionSample | groupBy(thread/name)
| key                | count |
+--------------------+-------+
| main               | 15234 |
| ForkJoinPool-1     | 8923  |
| GC Thread#0        | 4521  |

# Group with aggregation
jfr> show events/jdk.FileRead | groupBy(path, agg=sum, value=bytes)
| path              | sum       |
+-------------------+-----------+
| /var/log/app.log  | 524288000 |
| /tmp/data.txt     | 104857600 |
```

### Top-N

```bash
# Top 10 files by bytes read
jfr> show events/jdk.FileRead | top(10, by=bytes)
| path                    | bytes     |
+-------------------------+-----------+
| /data/large-file.bin    | 104857600 |
| /logs/app.log           | 52428800  |

# Top threads by samples
jfr> show events/jdk.ExecutionSample | groupBy(thread/name) | top(10, by=count)
```

### Statistics

```bash
# Basic stats
jfr> show events/jdk.FileRead/bytes | stats()
| min  | max      | avg      | stddev   | count |
+------+----------+----------+----------+-------+
| 0    | 10485760 | 52428.5  | 123456.7 | 5432  |

# Stats on filtered data
jfr> show events/jdk.FileRead[path~"/tmp/.*"]/bytes | stats()

# Quantiles
jfr> show events/jdk.FileRead/bytes | quantiles(0.5, 0.9, 0.99)
| p50   | p90     | p99      |
+-------+---------+----------+
| 4096  | 102400  | 1048576  |

# Sketch (combined stats + quantiles)
jfr> show events/jdk.FileRead/bytes | sketch()
```

### Summing

```bash
# Sum bytes read
jfr> show events/jdk.FileRead/bytes | sum()
Result: 524288000

# Sum with filter
jfr> show events/jdk.FileRead[path~"/tmp/.*"]/bytes | sum()
```

## Advanced Queries

### Complex Filters

```bash
# Boolean logic
jfr> show events/jdk.FileRead[bytes>1000 and path~".*\\.log"] --limit 10

# Function-based filters
jfr> show events/jdk.FileRead[len(path)>50] --limit 10

# Exists check
jfr> show events/jdk.ExecutionSample[exists(stackTrace)] --limit 10

# String functions
jfr> show events/jdk.FileRead[starts_with(path, "/tmp/")] --limit 10
jfr> show events/jdk.FileRead[contains(path, "temp")] --limit 10
jfr> show events/jdk.FileRead[matches(path, ".*\\.log")] --limit 10

# Range check
jfr> show events/jdk.FileRead[between(bytes, 1000, 10000)] --limit 10
```

### List Matching

```bash
# Any frame matches
jfr> show events/jdk.ExecutionSample[any:stackTrace/frames[matches(method/name, ".*Foo.*")]]

# All items match
jfr> show events/jdk.GCHeapSummary[all:heapSpace/committedSize>1000000]

# None match
jfr> show events/jdk.ExecutionSample[none:stackTrace/frames[lineNumber<0]]
```

### Interleaved Filters

Apply filters at different path levels:

```bash
# Filter before projection
jfr> show events/jdk.GCHeapSummary[when/when="After GC"]/heapSpace

# Filter after projection
jfr> show events/jdk.GCHeapSummary/heapSpace[committedSize>1000000]

# Both
jfr> show events/jdk.GCHeapSummary[when/when="After GC"]/heapSpace[reservedSize>2000000]
```

### Chained Aggregations

```bash
# Group then top
jfr> show events/jdk.ExecutionSample | groupBy(thread/name) | top(10, by=count)

# Group by multiple levels
jfr> show events/jdk.ExecutionSample | groupBy(state) | top(5, by=count)

# Filter, group, top
jfr> show events/jdk.FileRead[bytes>1000] | groupBy(path) | top(10, by=count)
```

## Event Decoration and Joining

Event decoration (also known as event joining or enrichment) allows you to correlate and combine information from different JFR event types. This powerful feature enables advanced analysis scenarios like request tracing, contention analysis, and GC impact assessment.

### Introduction

JFR recordings contain many different event types (execution samples, I/O operations, GC events, etc.). These events are related but captured independently. Event decoration lets you combine information from related events to gain deeper insights.

### When to Use Decoration

Use decoration when you need to:
- **Correlate events by time**: "What was the application doing during this GC phase?"
- **Correlate events by identifier**: "Which endpoint generated these execution samples?"
- **Enrich analysis**: Add context from one event type to another
- **Cross-reference**: Link events that share common attributes

### Decoration Concepts

#### Two Decoration Types

1. **Time-Based (`decorateByTime`)**: Correlates events that overlap in time on the same thread
2. **Key-Based (`decorateByKey`)**: Correlates events sharing a correlation key (e.g., thread ID, request ID)

#### Decorator vs Primary Events

- **Primary Event**: The main event type you're analyzing (e.g., `jdk.ExecutionSample`)
- **Decorator Event**: The event type providing additional context (e.g., `jdk.JavaMonitorWait`)
- **Decorated Event**: The result - primary event with decorator fields accessible

#### The `$decorator.` Prefix

Decorator fields are accessed using the `$decorator.` prefix to avoid naming conflicts:

```bash
# Primary event field
stackTrace

# Decorator event field
$decorator.monitorClass
```

### Time-Based Decoration

#### Syntax

```bash
events/PrimaryType | decorateByTime(DecoratorType, fields=field1,field2,...)
```

#### How It Works

1. **Pass 1**: Collect all decorator events with their time ranges (`startTime` to `startTime + duration`)
2. **Pass 2**: For each primary event, find decorators with overlapping time ranges on the same thread
3. **Result**: Primary events wrapped with matching decorator information

#### Time Overlap Logic

Events overlap if their time ranges intersect:

```
primaryStart < decoratorEnd AND primaryEnd > decoratorStart
```

For events without duration, duration is treated as 0 (point in time).

#### Example: Monitor Contention

Find execution samples that occur during monitor waits:

```bash
jfr> show events/jdk.ExecutionSample | decorateByTime(jdk.JavaMonitorWait, fields=monitorClass,duration)
```

**Interpretation**:
- Events with `$decorator.monitorClass`: Sample occurred during a lock wait
- Events with null `$decorator.monitorClass`: Sample occurred outside any lock wait

#### Thread Filtering

By default, decoration requires matching thread IDs:
- Primary event: `eventThread/javaThreadId`
- Decorator event: `eventThread/javaThreadId`

Custom thread paths can be specified:

```bash
jfr> show events/jdk.ExecutionSample | decorateByTime(jdk.JavaMonitorWait,
                                                       fields=monitorClass,
                                                       threadPath=sampledThread/javaThreadId,
                                                       decoratorThreadPath=eventThread/javaThreadId)
```

#### Common Time-Based Use Cases

| Primary Event | Decorator Event | Use Case |
|--------------|-----------------|----------|
| `jdk.ExecutionSample` | `jdk.JavaMonitorWait` | Thread contention analysis |
| `jdk.ExecutionSample` | `jdk.GCPhase` | CPU activity during GC |
| `jdk.ObjectAllocationSample` | `jdk.GCPhase` | Allocations during GC |
| `jdk.FileRead` | `jdk.JavaMonitorEnter` | I/O during lock acquisition |
| `jdk.SocketWrite` | `jdk.ThreadPark` | Network I/O during thread parking |

### Key-Based Decoration

#### Syntax

```bash
events/PrimaryType | decorateByKey(DecoratorType,
                                   key=primaryKeyPath,
                                   decoratorKey=decoratorKeyPath,
                                   fields=field1,field2,...)
```

#### How It Works

1. **Pass 1**: Collect all decorator events and index by their correlation key
2. **Pass 2**: For each primary event, compute its correlation key and lookup matching decorator
3. **Result**: Primary events wrapped with matching decorator information

#### Correlation Keys

Correlation keys are extracted from event fields using path expressions:

```bash
# Simple path
key=eventThread/javaThreadId

# Nested path
key=stackTrace/frames/0/method/name
```

#### Example: Request Tracing

Correlate execution samples with request context using thread IDs:

```bash
jfr> show events/jdk.ExecutionSample | decorateByKey(RequestStart,
                                                      key=sampledThread/javaThreadId,
                                                      decoratorKey=thread/javaThreadId,
                                                      fields=requestId,endpoint)
```

**Result**: Execution samples now have `$decorator.requestId` and `$decorator.endpoint` fields.

#### Common Key-Based Use Cases

| Primary Event | Decorator Event | Correlation Key | Use Case |
|--------------|-----------------|-----------------|----------|
| `jdk.ExecutionSample` | Custom `RequestStart` | Thread ID | Request tracing |
| `jdk.FileRead` | `jdk.ThreadStart` | Thread ID | Thread metadata |
| `jdk.SocketWrite` | Custom `ConnectionOpen` | Connection ID | Network tracing |
| Any event | Custom `TransactionBegin` | Transaction ID | Distributed tracing |

### Accessing Decorator Fields

#### In Projections

```bash
# Project both primary and decorator fields
jfr> show events/jdk.ExecutionSample | decorateByTime(jdk.JavaMonitorWait, fields=monitorClass)
  | top(10, by=$decorator.monitorClass)
```

#### In Aggregations

```bash
# Group by decorator field
jfr> show events/jdk.ExecutionSample | decorateByTime(jdk.GCPhase, fields=name)
  | groupBy($decorator.name)

# Sum with decorator field
jfr> show events/jdk.ObjectAllocationSample | decorateByTime(jdk.GCPhase, fields=name)
  | groupBy($decorator.name, agg=sum, value=allocationSize)
```

#### Handling Null Values

If no decorator matches a primary event, `$decorator.*` fields are `null`:

```bash
# Count events with and without decorators
jfr> show events/jdk.ExecutionSample | decorateByTime(jdk.JavaMonitorWait, fields=monitorClass)
  | groupBy(exists($decorator.monitorClass))
```

### Real-World Decoration Examples

#### Example 1: Identify Contention Hotspots

**Goal**: Find which locks cause the most thread contention.

```bash
# Step 1: Count samples by monitor class
jfr> show events/jdk.ExecutionSample | decorateByTime(jdk.JavaMonitorWait, fields=monitorClass)
  | groupBy($decorator.monitorClass, agg=count)
  | top(10, by=count)
```

**Interpretation**: Monitors with high sample counts are contention hotspots.

#### Example 2: Endpoint Performance Profile

**Goal**: Understand CPU usage by API endpoint.

```bash
# Assuming custom RequestStart event with 'endpoint' field
jfr> show events/jdk.ExecutionSample | decorateByKey(RequestStart,
                                                      key=sampledThread/javaThreadId,
                                                      decoratorKey=thread/javaThreadId,
                                                      fields=endpoint)
  | groupBy($decorator.endpoint, agg=count)
  | top(10, by=count)
```

**Interpretation**: Endpoints with high sample counts consume more CPU.

#### Example 3: GC Impact on Allocations

**Goal**: Measure allocation pressure during different GC phases.

```bash
jfr> show events/jdk.ObjectAllocationSample | decorateByTime(jdk.GCPhase, fields=name)
  | groupBy($decorator.name, agg=sum, value=allocationSize)
```

**Interpretation**: High allocations during concurrent phases may affect GC efficiency.

#### Example 4: I/O During Lock Contention

**Goal**: Identify if file I/O happens during lock waits (anti-pattern).

```bash
jfr> show events/jdk.FileRead | decorateByTime(jdk.JavaMonitorWait, fields=monitorClass,duration)
```

**Interpretation**: File reads with non-null `$decorator.monitorClass` occur during lock waits.

#### Example 5: Execution Samples During GC Phases

```bash
# During GC phases
jfr> show events/jdk.ExecutionSample | decorateByTime(jdk.GCPhase, fields=name,duration)

# Group samples by GC phase
jfr> show events/jdk.ExecutionSample | decorateByTime(jdk.GCPhase, fields=name)
  | groupBy($decorator.name)

# Allocations during GC
jfr> show events/jdk.ObjectAllocationSample | decorateByTime(jdk.GCPhase, fields=name)
  | groupBy($decorator.name, agg=sum, value=allocationSize)

# File I/O during GC
jfr> show events/jdk.FileRead | decorateByTime(jdk.GCPhase, fields=name)
  | groupBy($decorator.name, agg=sum, value=bytes)
```

#### Example 6: Thread Behavior During GC

```bash
# Threads active during concurrent mark
jfr> show events/jdk.ExecutionSample | decorateByTime(jdk.GCPhase, fields=name)
  | groupBy(sampledThread/javaName, $decorator.name)

# Lock contention during GC
jfr> show events/jdk.JavaMonitorWait | decorateByTime(jdk.GCPhase, fields=name)
  | groupBy($decorator.name, agg=count)
```

### Best Practices

#### Choosing Decoration Type

Use **decorateByTime** when:
- Events have temporal relationships (start/end times)
- You care about "what happened during this period"
- Both event types have thread context
- Example: Samples during GC, I/O during locks

Use **decorateByKey** when:
- Events share explicit identifiers
- Temporal overlap doesn't matter
- You have custom correlation IDs
- Example: Request tracing, transaction tracking

#### Field Selection

Only request fields you need:

```bash
# Good: Only request needed fields
fields=requestId,endpoint

# Avoid: Requesting all fields (more memory)
fields=requestId,endpoint,userId,headers,queryParams,cookies
```

#### Memory Considerations

Decoration requires collecting all decorator events in memory:

- **Small decorator sets** (< 100K events): No issue
- **Large decorator sets** (> 1M events): May consume significant memory
- **Mitigation**: Use filters to reduce decorator event count

```bash
# Filter decorators before decorating
jfr> show events/jdk.ExecutionSample | decorateByTime(jdk.JavaMonitorWait[duration>1000000],
                                                       fields=monitorClass)
```

#### Thread Safety

Decoration is thread-safe by default:
- Time-based: Only matches events on the same thread
- Key-based: Specify appropriate thread path as correlation key

For cross-thread correlation, use custom correlation IDs instead of thread IDs.

### Troubleshooting

#### All decorator fields are null

**Possible causes**:
1. No decorator events in recording
2. Decorator events don't overlap with primary events
3. Thread IDs don't match (time-based)
4. Correlation keys don't match (key-based)

**Debug**:
```bash
# Check decorator event count
jfr> show events/DecoratorType | count()

# Check thread IDs
jfr> show events/PrimaryType/threadPath
jfr> show events/DecoratorType/decoratorThreadPath
```

#### Performance is slow

**Possible causes**:
1. Very large decorator event set (> 1M events)
2. Complex correlation key extraction

**Mitigations**:
- Filter decorators to reduce set size
- Use simpler correlation keys
- Consider sampling primary events

#### Unexpected decorator matches

**Possible causes**:
1. Time range overlap logic (overlaps are inclusive)
2. Multiple decorators match (first is used)
3. Thread ID collision

**Debug**:
- Check event timestamps and durations
- Examine thread IDs for collisions
- Use `--limit` to inspect sample results

### Summary

Event decoration enables powerful cross-event analysis:

- **`decorateByTime`**: Time-based correlation on same thread
- **`decorateByKey`**: Identifier-based correlation
- **`$decorator.`**: Access decorator fields
- **Memory-efficient**: Lazy field access, selective field extraction
- **Flexible**: Works with any event types

See the [JfrPath Reference](jfrpath.md) for complete syntax details and [examples/](../jfr-shell/src/main/resources/examples/) for more use cases.

## Multi-Session Management

Work with multiple recordings simultaneously:

```bash
# Open multiple recordings
jfr> open prod-recording.jfr --alias prod
Opened session #1 (prod)

jfr> open test-recording.jfr --alias test
Opened session #2 (test)

# List sessions
jfr> sessions
  #1  (prod): prod-recording.jfr [45 event types]
  #2* (test): test-recording.jfr [52 event types]

# Switch between sessions
jfr> use prod
Switched to session #1 (prod)

jfr> show events/jdk.ExecutionSample | count()
Result: 15234

jfr> use test
Switched to session #2 (test)

jfr> show events/jdk.ExecutionSample | count()
Result: 8923

# Close specific session
jfr> close prod

# Close all
jfr> close --all
```

## Non-Interactive Mode

Execute queries without entering the shell - perfect for scripts and CI:

### Single Query

```bash
# Count events
$ jfr-shell show recording.jfr "events/jdk.ExecutionSample | count()"
45234

# Group analysis
$ jfr-shell show recording.jfr "events/jdk.FileRead | groupBy(path) | top(10, by=count)"
| path              | count |
+-------------------+-------+
| /var/log/app.log  | 1523  |
| /tmp/data.txt     | 892   |
```

### JSON Output

```bash
# JSON for programmatic processing
$ jfr-shell show recording.jfr "events/jdk.FileRead | top(5, by=bytes)" --format json
[
  {"path": "/data/large.bin", "bytes": 10485760},
  {"path": "/logs/app.log", "bytes": 5242880}
]

# Parse with jq
$ jfr-shell show recording.jfr "events/jdk.FileRead | top(5, by=bytes)" --format json | jq '.[0].path'
"/data/large.bin"
```

### Scripting

```bash
#!/bin/bash
# analyze-jfr.sh - Automated JFR analysis

RECORDING=$1

echo "Event Summary:"
jfr-shell show "$RECORDING" "events/jdk.ExecutionSample | count()"

echo "Top Threads:"
jfr-shell show "$RECORDING" "events/jdk.ExecutionSample | groupBy(thread/name) | top(10, by=count)"

echo "Top Files:"
jfr-shell show "$RECORDING" "events/jdk.FileRead | groupBy(path, agg=sum, value=bytes) | top(10, by=sum)"

# Exit code 0 for success, 1 for errors
```

### Metadata Queries

```bash
# List event types
$ jfr-shell metadata recording.jfr --events-only

# Search for types
$ jfr-shell metadata recording.jfr --search "jdk.File*"

# Show type structure
$ jfr-shell show recording.jfr "metadata/jdk.types.StackTrace" --tree
```

### Chunk Information

```bash
# List chunks
$ jfr-shell chunks recording.jfr

# Chunk summary
$ jfr-shell chunks recording.jfr --summary
```

## Real-World Examples

### Example 1: Find Memory Leaks

```bash
# Find top allocating classes
jfr> show events/jdk.ObjectAllocationSample | groupBy(objectClass/name, agg=sum, value=weight) | top(20, by=sum)

# Allocation hot spots
jfr> show events/jdk.ObjectAllocationSample/stackTrace/frames/0/method/type/name | groupBy() | top(10, by=count)

# Allocation rate by thread
jfr> show events/jdk.ObjectAllocationSample | groupBy(thread/name, agg=sum, value=weight) | top(10, by=sum)
```

### Example 2: Analyze Thread Contention

```bash
# Find blocked threads
jfr> show events/jdk.ThreadPark[parkedClass!=null] | groupBy(parkedClass/name) | top(10, by=count)

# Lock contention by monitor
jfr> show events/jdk.JavaMonitorEnter | groupBy(monitorClass/name) | top(10, by=count)

# Thread parking duration
jfr> show events/jdk.ThreadPark/duration | stats()
```

### Example 3: I/O Performance Analysis

```bash
# Total bytes read
jfr> show events/jdk.FileRead/bytes | sum()

# Slow file operations
jfr> show events/jdk.FileRead[duration>10000000] | top(20, by=duration)

# I/O by file
jfr> show events/jdk.FileRead | groupBy(path, agg=sum, value=bytes) | top(20, by=sum)

# Socket I/O statistics
jfr> show events/jdk.SocketRead/duration | stats()
jfr> show events/jdk.SocketRead | groupBy(address) | top(10, by=count)
```

### Example 4: CPU Profiling

```bash
# Top hot methods
jfr> show events/jdk.ExecutionSample/stackTrace/frames/0/method/type/name | groupBy() | top(20, by=count)

# Thread activity
jfr> show events/jdk.ExecutionSample | groupBy(thread/name) | top(10, by=count)

# Thread state distribution
jfr> show events/jdk.ExecutionSample | groupBy(state) | top(5, by=count)

# Stack depth analysis
jfr> show events/jdk.ExecutionSample[len(stackTrace/frames)>50] | count()
```

### Example 5: GC Analysis

```bash
# GC events after collection
jfr> show events/jdk.GCHeapSummary[when="After GC"]/heapUsed | stats()

# Heap growth trend
jfr> show events/jdk.GCHeapSummary[when="After GC"]/heapUsed --limit 100

# GC pause times
jfr> show events/jdk.GarbageCollection/sumOfPauses | stats()

# Most common GC causes
jfr> show events/jdk.GarbageCollection | groupBy(cause) | top(10, by=count)
```

### Example 6: Exception Analysis

```bash
# Top exception types
jfr> show events/jdk.ExceptionStatistics | groupBy(throwables/name) | top(10, by=count)

# Exception hot spots
jfr> show events/jdk.ExceptionStatistics/stackTrace/frames/0/method/type/name | groupBy() | top(10, by=count)

# High-frequency exceptions (possible control flow)
jfr> show events/jdk.ExceptionStatistics | groupBy(throwables/name) | top(10, by=count)
```

### Example 7: Database Query Analysis

```bash
# Find slow database operations (via socket reads)
jfr> show events/jdk.SocketRead[address~".*postgres.*" and duration>100000000] --limit 20

# Database I/O statistics
jfr> show events/jdk.SocketRead[address~".*postgres.*"]/duration | stats()

# Most active database connections
jfr> show events/jdk.SocketRead[address~".*postgres.*"] | groupBy(address) | top(10, by=count)
```

## Tips and Tricks

### Tab Completion

JFR Shell provides comprehensive tab completion throughout the query language. Press Tab at any point to see available completions:

**Command and Root Completion:**
```bash
jfr> sh<TAB>                    # Commands: show, sessions
jfr> show <TAB>                 # Roots: events/, metadata/, cp/, chunks/
jfr> show chunks/<TAB>          # Chunk IDs: chunks/1, chunks/2, chunks/3
```

**Event Type and Field Path Completion:**
```bash
jfr> show events/jdk.Exe<TAB>
jdk.ExecutionSample  jdk.ExecuteVMOperation

jfr> show events/jdk.ExecutionSample/<TAB>
startTime  duration  stackTrace  sampledThread  state

jfr> show events/jdk.ExecutionSample/sampledThread/<TAB>
javaThreadId  osThreadId  javaName  osName  group
```

**Metadata Subproperty Completion:**
```bash
jfr> show metadata/jdk.ExecutionSample/<TAB>
fields  settings  annotations
```

**Filter Completion (inside [...]):**
```bash
jfr> show events/jdk.FileRead[<TAB>
# Field names: path, bytes, duration, startTime
# Filter functions: contains(, exists(, startsWith(, endsWith(

jfr> show events/jdk.FileRead[bytes<TAB>
# Operators: ==, !=, >, >=, <, <=, ~, contains, startsWith, endsWith, matches

jfr> show events/jdk.FileRead[bytes > 1000 <TAB>
# Logical operators: &&, ||
```

**Pipeline Operator Completion:**
```bash
jfr> show events/jdk.ExecutionSample | <TAB>
count()  sum(  groupBy(  top(  stats(  quantiles(  sketch(  select(
decorateByTime(  decorateByKey(  len(  uppercase(  lowercase(

jfr> show events/jdk.ExecutionSample | groupBy(<TAB>
# Field names for function parameters
```

**Option Completion:**
```bash
jfr> show events/jdk.FileRead --<TAB>
--limit  --format  --tree  --depth  --list-match
```

Completion is context-aware and suggests only valid options for the current position in the query.

### Command History

- Use ↑/↓ arrows to navigate command history
- History is preserved across sessions

### Output Limiting

Always use `--limit` when exploring:

```bash
# Good - limits output
jfr> show events/jdk.ExecutionSample --limit 10

# Bad - may output millions of events
jfr> show events/jdk.ExecutionSample
```

### Saving Results

Use shell redirection to save output:

```bash
$ jfr-shell show recording.jfr "events/jdk.ExecutionSample | groupBy(thread/name)" > threads.txt

$ jfr-shell show recording.jfr "events/jdk.FileRead | top(10, by=bytes)" --format json > top-files.json
```

### Quick Profiling

Start with broad queries, then narrow:

```bash
# 1. Count event types
jfr> metadata --events-only

# 2. Count specific events
jfr> show events/jdk.ExecutionSample | count()

# 3. Sample events
jfr> show events/jdk.ExecutionSample --limit 5

# 4. Analyze patterns
jfr> show events/jdk.ExecutionSample | groupBy(state)

# 5. Deep dive
jfr> show events/jdk.ExecutionSample[state="RUNNABLE"] | groupBy(thread/name) | top(10, by=count)
```

## Next Steps

- Master [Event Decoration](#event-decoration-and-joining) for advanced correlation analysis
- Learn the [Typed API](typed-api-tutorial.md) for programmatic parsing
- Explore the [Untyped API](untyped-api-tutorial.md) for flexible parsing
- Read [JfrPath Reference](jfrpath.md) for complete syntax
- Check [PERFORMANCE.md](../PERFORMANCE.md) for optimization tips

## Related Documentation

- [JFR Shell README](../jfr-shell/README.md) - Installation and features
- [JfrPath Grammar](jfrpath.md) - Complete syntax reference
- [JFR Shell Usage](jfr_shell_usage.md) - Command reference
- [JBang Usage](jbang-usage.md) - JBang installation guide

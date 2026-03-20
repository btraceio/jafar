# Heap Dump Analysis Quick Start

Get started analyzing Java heap dumps in 5 minutes.

## Installation

```bash
# Using JBang (recommended)
jbang app install jafar-shell@btraceio
```

Or build from source:
```bash
./gradlew :jafar-shell:shadowJar
alias jafar-shell="java -jar jafar-shell/build/libs/jafar-shell-*-all.jar"
```

## Open a Heap Dump

```bash
jafar-shell /path/to/dump.hprof
```

Or start the shell and open later:
```bash
jafar-shell
jafar> open /path/to/dump.hprof
```

## Essential Queries

### Get Overview

```bash
# Count all objects
hdump> objects | count

# Memory statistics
hdump> objects | stats(shallow)

# Class count
hdump> classes | count
```

### Find Memory Hogs

```bash
# Top 10 classes by object count
hdump> classes | top(10, instanceCount)

# Top 10 classes by total memory
hdump> objects | groupBy(class, agg=sum) | top(10, sum)

# Largest individual objects
hdump> objects | top(10, shallow)
```

### Analyze Specific Types

```bash
# String objects
hdump> objects/java.lang.String | stats(shallow)

# Map implementations
hdump> objects/instanceof/java.util.Map | groupBy(class) | top(5, count)

# Your application classes
hdump> classes/com.myapp.* | top(10, instanceCount)
```

### GC Roots

```bash
# Root distribution
hdump> gcroots | groupBy(type) | sortBy(count desc)

# Thread roots
hdump> gcroots/THREAD_OBJ | head(10)
```

## Query Syntax Cheat Sheet

### Roots
| Root | Description |
|------|-------------|
| `objects` | Heap object instances |
| `classes` | Class metadata |
| `gcroots` | GC root references |

### Type Filters
```
objects/java.lang.String              # Exact class
objects/java.util.*                   # Glob pattern
objects/instanceof/java.util.Map      # Include subclasses
```

### Predicates
```
[shallow > 1MB]                       # Size comparison
[instanceCount > 1000]                # Numeric comparison
[name ~ ".*Cache.*"]                  # Regex match
[shallow > 1KB and shallow < 1MB]     # Boolean logic
```

### Pipeline Operations
| Operation | Example |
|-----------|---------|
| `count` | `objects \| count` |
| `top(n, field)` | `objects \| top(10, shallow)` |
| `groupBy(field)` | `objects \| groupBy(class)` |
| `stats(field)` | `objects \| stats(shallow)` |
| `select(fields)` | `objects \| select(class, shallow)` |
| `head(n)` | `objects \| head(100)` |
| `tail(n)` | `objects \| tail(10)` |
| `filter(pred)` | `objects \| filter(shallow > 1KB)` |
| `distinct(field)` | `objects \| distinct(class)` |
| `sortBy(field)` | `classes \| sortBy(instanceCount desc)` |
| `pathToRoot` | `objects[id = 123] \| pathToRoot` |
| `retentionPaths` | `objects/java.util.HashMap \| retentionPaths()` |
| `retainedBreakdown` | `objects[retained > 10MB] \| retainedBreakdown()` |
| `checkLeaks` | `checkLeaks()` or `objects \| checkLeaks` |
| `dominators` | `objects \| dominators(groupBy="class")` |
| `waste` | `objects/instanceof/java.util.HashMap \| waste()` |
| `join` | `classes \| join(session=1)` or `classes \| join(session=1, root="jdk.ObjectAllocationSample", by=class)` |

## Common Workflows

### Memory Leak Investigation

```bash
# Quick leak scan with built-in detectors
hdump> checkLeaks()

# Or run specific detectors
hdump> checkLeaks(detector="threadlocal-leak")
hdump> checkLeaks(detector="classloader-leak")
hdump> checkLeaks(detector="growing-collections")

# Manual investigation
hdump> classes | top(20, instanceCount)
hdump> classes/com.myapp.* | top(10, instanceCount)
hdump> objects/instanceof/java.util.Collection | groupBy(class) | top(10, count)
```

### Retained Size Analysis

```bash
# Top objects by retained size (memory kept alive)
hdump> objects | top(10, retained)

# Find path from object to GC root
hdump> objects[id = 0x12345] | pathToRoot

# Dominator tree (what dominates memory)
hdump> objects[id = 0x12345] | dominators
```

### Heap Size Analysis

```bash
# Total heap usage
hdump> objects | sum(shallow)

# Memory by class
hdump> objects | groupBy(class, agg=sum) | top(20, sum)

# Large objects
hdump> objects[shallow > 1MB] | top(10, shallow)
```

### String Analysis

```bash
# String statistics
hdump> objects/java.lang.String | stats(shallow)

# Large strings
hdump> objects/java.lang.String[shallow > 1KB] | top(10, shallow)

# String count
hdump> objects/java.lang.String | count
```

### Automatic Leak Cluster Detection

```bash
# Detect leak clusters ranked by suspiciousness
hdump> clusters | sortBy(score desc) | head(10)

# Filter for large clusters
hdump> clusters | filter(retainedSize > 10MB)

# Drill into a specific cluster's objects
hdump> clusters[id = 3] | objects | top(10, retained)

# Filter by anchor type
hdump> clusters | filter(anchorType = "THREAD_OBJ")
```

### Heap Diff (Compare Two Dumps)

```bash
# Open two dumps
hdump> open before.hprof
hdump> open after.hprof

# Diff class histograms (session 1 = before.hprof)
hdump> classes | join(session=1) | sortBy(instanceCountDelta desc) | head(20)

# Find growing classes
hdump> classes | join(session=1) | filter(instanceCountDelta > 0) | top(10, instanceCountDelta)
```

### JFR + Heap Dump Correlation

```bash
# Open a JFR recording and a heap dump
hdump> open recording.jfr
hdump> open dump.hprof

# Enrich class histogram with allocation data from JFR
hdump> classes | join(session="recording.jfr", root="jdk.ObjectAllocationSample", by=class)

# Find high-churn classes (many allocations, few survivors)
hdump> classes | join(session=1, root="jdk.ObjectAllocationSample", by=class) | filter(allocCount > 1000) | sortBy(survivalRatio asc) | head(20)

# Top classes by allocation weight
hdump> classes | join(session=1, root="jdk.ObjectAllocationSample", by=class) | sortBy(allocWeight desc) | top(10)
```

## Output Options

### Limit Results
```bash
hdump> objects | top(10, shallow) --limit 5
```

### Output Formats
```bash
# Table (default)
hdump> objects | top(10, shallow)

# JSON
hdump> objects | top(10, shallow) --format json

# CSV
hdump> objects | top(10, shallow) --format csv
```

## Next Steps

- [Full Tutorial](cli/hdump-shell-tutorial.md) - Complete heap dump analysis guide
- [HdumpPath Reference](hdumppath.md) - Query language reference
- [JFR Shell Tutorial](cli/Tutorial.md) - JFR analysis (same shell!)

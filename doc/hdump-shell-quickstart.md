# Heap Dump Analysis Quick Start

Get started analyzing Java heap dumps in 5 minutes.

## Installation

```bash
# Using JBang (recommended)
jbang app install jfr-shell@btraceio
```

Or build from source:
```bash
./gradlew :jfr-shell:shadowJar
alias jfr-shell="java -jar jfr-shell/build/libs/jfr-shell-*-all.jar"
```

## Open a Heap Dump

```bash
jfr-shell /path/to/dump.hprof
```

Or start the shell and open later:
```bash
jfr-shell
jfr> open /path/to/dump.hprof
```

## Essential Queries

### Get Overview

```bash
# Count all objects
jfr> objects | count

# Memory statistics
jfr> objects | stats(shallow)

# Class count
jfr> classes | count
```

### Find Memory Hogs

```bash
# Top 10 classes by object count
jfr> classes | top(10, instanceCount)

# Top 10 classes by total memory
jfr> objects | groupBy(class, agg=sum) | top(10, sum)

# Largest individual objects
jfr> objects | top(10, shallow)
```

### Analyze Specific Types

```bash
# String objects
jfr> objects/java.lang.String | stats(shallow)

# Map implementations
jfr> objects/instanceof/java.util.Map | groupBy(class) | top(5, count)

# Your application classes
jfr> classes/com.myapp.* | top(10, instanceCount)
```

### GC Roots

```bash
# Root distribution
jfr> gcroots | groupBy(type) | sortBy(count desc)

# Thread roots
jfr> gcroots/THREAD_OBJ | head(10)
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
| `pathToRoot` | `objects[id = 123] \| pathToRoot` |
| `checkLeaks` | `checkLeaks()` or `objects \| checkLeaks` |
| `dominators` | `objects[id = 123] \| dominators` |

## Common Workflows

### Memory Leak Investigation

```bash
# Quick leak scan with built-in detectors
jfr> checkLeaks()

# Or run specific detectors
jfr> checkLeaks(detector="threadlocal-leak")
jfr> checkLeaks(detector="classloader-leak")
jfr> checkLeaks(detector="growing-collections")

# Manual investigation
jfr> classes | top(20, instanceCount)
jfr> classes/com.myapp.* | top(10, instanceCount)
jfr> objects/instanceof/java.util.Collection | groupBy(class) | top(10, count)
```

### Retained Size Analysis

```bash
# Top objects by retained size (memory kept alive)
jfr> objects | top(10, retained)

# Find path from object to GC root
jfr> objects[id = 0x12345] | pathToRoot

# Dominator tree (what dominates memory)
jfr> objects[id = 0x12345] | dominators
```

### Heap Size Analysis

```bash
# Total heap usage
jfr> objects | sum(shallow)

# Memory by class
jfr> objects | groupBy(class, agg=sum) | top(20, sum)

# Large objects
jfr> objects[shallow > 1MB] | top(10, shallow)
```

### String Analysis

```bash
# String statistics
jfr> objects/java.lang.String | stats(shallow)

# Large strings
jfr> objects/java.lang.String[shallow > 1KB] | top(10, shallow)

# String count
jfr> objects/java.lang.String | count
```

## Next Steps

- [Full Tutorial](tutorials/hdump-shell-tutorial.md) - Complete heap dump analysis guide
- [HdumpPath Reference](hdumppath.md) - Query language reference
- [JFR Shell Tutorial](tutorials/jfr-shell-tutorial.md) - JFR analysis (same shell!)

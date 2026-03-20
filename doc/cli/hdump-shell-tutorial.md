# Heap Dump Analysis Tutorial

This tutorial teaches you how to use JAFAR's heap dump analysis capabilities for exploring and diagnosing Java heap dumps (HPROF files). You'll learn the HdumpPath query language and common analysis patterns.

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Creating a Heap Dump](#creating-a-heap-dump)
3. [Opening a Heap Dump](#opening-a-heap-dump)
4. [Basic Queries](#basic-queries)
5. [Object Analysis](#object-analysis)
6. [Class Analysis](#class-analysis)
7. [GC Root Analysis](#gc-root-analysis)
8. [Memory Leak Detection](#memory-leak-detection)
9. [Common Analysis Patterns](#common-analysis-patterns)
10. [Tips and Best Practices](#tips-and-best-practices)

## Prerequisites

- JAFAR shell installed (see [main README](../../README.md) for installation)
- A Java heap dump file (`.hprof` format)
- Basic understanding of Java memory concepts

## Creating a Heap Dump

If you don't have a heap dump, create one from a running Java process:

### Using jcmd (Recommended)
```bash
# Find Java process ID
jps -l

# Create heap dump
jcmd <pid> GC.heap_dump /path/to/dump.hprof
```

### Using jmap
```bash
jmap -dump:format=b,file=/path/to/dump.hprof <pid>
```

### Programmatically
```java
import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;

HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
bean.dumpHeap("/path/to/dump.hprof", true);
```

### On OutOfMemoryError
Add JVM flag to automatically dump on OOM:
```bash
java -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/path/to/dumps/ MyApp
```

## Opening a Heap Dump

Start the shell and open your heap dump:

```bash
# Start with a heap dump
jafar-shell /path/to/dump.hprof

# Or start empty and open later
jafar-shell
jafar> open /path/to/dump.hprof
```

When a heap dump is opened, you'll see basic statistics:

```
Opened heap dump: dump.hprof
  Size: 158 MB
  Objects: 1,923,456
  Classes: 16,831
  GC Roots: 4,200
```

## Basic Queries

### Count Objects

```bash
# Total objects
hdump> objects | count

# String objects
hdump> objects/java.lang.String | count

# Objects larger than 1KB
hdump> objects[shallow > 1KB] | count
```

### View Sample Objects

```bash
# First 10 objects
hdump> objects | head(10)

# First 10 strings
hdump> objects/java.lang.String | head(10)
```

### Get Statistics

```bash
# Shallow size statistics for all objects
hdump> objects | stats(shallow)

| count   | sum         | min | max      | avg   |
|---------|-------------|-----|----------|-------|
| 1923456 | 78,345,280  | 16  | 1048576  | 40.7  |
```

## Object Analysis

### Find Large Objects

```bash
# Top 10 by shallow size
hdump> objects | top(10, shallow)

# Large objects (>1MB)
hdump> objects[shallow > 1MB] | select(class, shallow)
```

### Analyze by Class

```bash
# Group objects by class
hdump> objects | groupBy(class, agg=count) | top(10, count)

| class                      | count   |
|---------------------------|---------|
| java.util.HashMap$Node    | 249,734 |
| java.lang.String          | 238,750 |
| java.lang.Object[]        | 156,234 |
| char[]                    | 145,632 |
```

### Memory by Class

```bash
# Total memory by class (sum shallow sizes)
hdump> objects | groupBy(class, agg=sum) | top(10, sum)

| class                | sum         |
|---------------------|-------------|
| byte[]              | 45,234,567  |
| char[]              | 12,345,678  |
| java.lang.String    | 9,072,500   |
```

### String Analysis

Strings often dominate heap usage:

```bash
# String statistics
hdump> objects/java.lang.String | stats(shallow)

# Large strings
hdump> objects/java.lang.String[shallow > 1KB] | top(10, shallow)

# String count by size range
hdump> objects/java.lang.String[shallow > 100] | count
```

### Array Analysis

Arrays can consume significant memory:

```bash
# Large arrays
hdump> objects[arrayLength > 10000] | top(10, shallow)

# Byte arrays (common for buffers)
hdump> objects/byte[] | stats(shallow)

# Object arrays
hdump> objects/java.lang.Object[] | top(10, shallow)
```

## Class Analysis

### Class Overview

```bash
# All classes
hdump> classes | count

# Classes with most instances
hdump> classes | top(10, instanceCount)

| name                        | instanceCount |
|----------------------------|---------------|
| java.util.HashMap$Node     | 249,734       |
| java.lang.String           | 238,750       |
| java.lang.Object[]         | 156,234       |
```

### Find Specific Classes

```bash
# Search by name pattern
hdump> classes/java.util.* | select(name, instanceCount)

# Classes with many instances
hdump> classes[instanceCount > 1000] | sortBy(instanceCount desc)
```

### Class Hierarchy

```bash
# Find implementations of Map
hdump> objects/instanceof/java.util.Map | groupBy(class) | top(10, count)

| class                          | count  |
|-------------------------------|--------|
| java.util.HashMap             | 12,345 |
| java.util.LinkedHashMap       | 5,678  |
| java.util.concurrent.ConcurrentHashMap | 1,234 |
```

### Collection Analysis

```bash
# All collections
hdump> objects/instanceof/java.util.Collection | groupBy(class) | top(10, count)

# List implementations
hdump> objects/instanceof/java.util.List | groupBy(class) | top(5, count)

# Set implementations
hdump> objects/instanceof/java.util.Set | groupBy(class) | top(5, count)
```

## GC Root Analysis

GC roots are entry points that keep objects alive.

### Root Distribution

```bash
# GC roots by type
hdump> gcroots | groupBy(type, agg=count) | sortBy(count desc)

| type         | count |
|-------------|-------|
| JAVA_FRAME  | 2,345 |
| THREAD_OBJ  | 1,234 |
| JNI_GLOBAL  | 456   |
| STICKY_CLASS| 165   |
```

### Thread Roots

```bash
# Thread-related roots
hdump> gcroots/THREAD_OBJ | head(10)

# Java frame roots (stack variables)
hdump> gcroots/JAVA_FRAME | head(10)
```

### JNI References

```bash
# JNI global references (potential leaks)
hdump> gcroots/JNI_GLOBAL | head(20)

hdump> gcroots/JNI_GLOBAL | count
```

## Memory Leak Detection

### Built-in Leak Detectors

The shell includes 6 built-in leak detectors that can be run with `checkLeaks`:

```bash
# Run all detectors (interactive wizard)
hdump> checkLeaks()

# Run a specific detector
hdump> checkLeaks(detector="threadlocal-leak")
```

**Available detectors:**

| Detector | Description |
|----------|-------------|
| `threadlocal-leak` | Find ThreadLocal instances with large retained sizes attached to threads |
| `classloader-leak` | Detect ClassLoader instances that should be GC'd but are retained |
| `duplicate-strings` | Find identical string values with high instance counts |
| `growing-collections` | Detect collections (HashMap, ArrayList, etc.) with large retained sizes |
| `listener-leak` | Find event listeners that may not have been deregistered |
| `finalizer-queue` | Detect objects stuck in the finalizer queue |

**Detector parameters:**

```bash
# Set minimum size threshold (default varies by detector)
hdump> checkLeaks(detector="growing-collections", minSize=10MB)

# Set count threshold
hdump> checkLeaks(detector="duplicate-strings", threshold=100)
```

### Retained Size Analysis

Retained size shows how much memory would be freed if an object were garbage collected:

```bash
# Top objects by retained size
hdump> objects | top(10, retained)

# Classes by total retained memory
hdump> objects | groupBy(class) | top(10, retained)

# Large retained objects of specific type
hdump> objects/java.util.HashMap[retained > 10MB] | top(10, retained)
```

### Path to GC Root

Find why an object is kept alive:

```bash
# Find retention path for a specific object
hdump> objects[id = 0x12345678] | pathToRoot

# Find path for largest HashMap
hdump> objects/java.util.HashMap | top(1, retained) | pathToRoot
```

### Dominator Tree

See what objects dominate memory (keep other objects alive):

```bash
# Dominator tree for an object
hdump> objects[id = 0x12345678] | dominators

# Dominators grouped by class
hdump> objects[id = 0x12345678] | dominators(groupBy="class")

# Full dominator tree view
hdump> objects[id = 0x12345678] | dominators("tree")
```

### Manual Leak Patterns

#### Pattern 1: Unusually Large Instance Counts

```bash
# Classes with most instances
hdump> classes | top(20, instanceCount)

# Focus on application classes
hdump> classes/com.myapp.* | top(10, instanceCount)
```

#### Pattern 2: Growing Collections

```bash
# Large HashMaps by retained size
hdump> objects/java.util.HashMap | top(10, retained)

# Large ArrayLists
hdump> objects/java.util.ArrayList[arrayLength > 1000] | head(10)
```

#### Pattern 3: Duplicate Strings

```bash
# Use the built-in detector
hdump> checkLeaks(detector="duplicate-strings", threshold=50)

# Or manual analysis
hdump> objects/java.lang.String | stats(shallow)
```

#### Pattern 4: Cache Analysis

```bash
# Find cache-like structures
hdump> objects/instanceof/java.util.Map | groupBy(class, agg=count)

# Large Maps that might be caches
hdump> classes/*Cache* | select(name, instanceCount)
```

## Common Analysis Patterns

### Memory Footprint by Package

```bash
# Group by package prefix
hdump> classes/com.myapp.* | top(10, instanceCount)
hdump> classes/org.springframework.* | top(10, instanceCount)
```

### Heap Summary

Create a comprehensive overview:

```bash
# Object count
hdump> objects | count

# Total shallow size
hdump> objects | sum(shallow)

# Class count
hdump> classes | count

# Top consumers
hdump> objects | groupBy(class, agg=sum) | top(10, sum)
```

### Compare Before/After (Heap Diff)

Open two heap dumps and use `join` to diff them:

```bash
hdump> open before.hprof
hdump> open after.hprof

# Diff class histograms — session 1 is before.hprof (the baseline)
hdump> classes | join(session=1) | sortBy(instanceCountDelta desc) | head(20)

# Find classes with growing instance counts
hdump> classes | join(session=1) | filter(instanceCountDelta > 0) | top(10, instanceCountDelta)

# New classes not present in the baseline
hdump> classes | join(session=1) | filter(baseline.exists = false)

# GC root type changes
hdump> gcroots | groupBy(type, agg=count) | join(session=1) | sortBy(countDelta desc)
```

The `join` operator adds `baseline.*` and `*Delta` columns for every numeric field.
The join key is auto-inferred (`name` for classes, `className` for objects, `type` for GC roots)
and can be overridden with `by=field`.

### Export for Further Analysis

```bash
# Export to JSON for external tools
hdump> objects | groupBy(class, agg=count) | top(100, count) --format json > classes.json

# CSV output
hdump> classes | top(20, instanceCount) --format csv > top-classes.csv

# Limit output rows
hdump> objects | top(100, shallow) --limit 10
```

## Tips and Best Practices

### 1. Start with Overview

Always begin with high-level statistics:
```bash
objects | count
objects | stats(shallow)
classes | count
gcroots | groupBy(type)
```

### 2. Use instanceof for Interface Analysis

Find all implementations:
```bash
objects/instanceof/java.io.Closeable | groupBy(class)
```

### 3. Focus on Your Application

Filter to your packages:
```bash
classes/com.myapp.* | top(10, instanceCount)
```

### 4. Compare Heap Dumps

Take dumps at different times and use `join` to diff them:
```bash
open before.hprof
open after.hprof
classes | join(session=1) | filter(instanceCountDelta > 0) | top(20, instanceCountDelta)
```

Good scenarios for comparison:
- Before and after a suspected leak operation
- Under normal load vs. high load
- Fresh start vs. after extended run

### 5. Look for Patterns

Common leak indicators:
- Steadily growing instance counts
- Large collections (Maps, Lists)
- Many JNI_GLOBAL references
- Duplicate strings

### 6. Use Size Units

Make queries readable:
```bash
# Instead of
objects[shallow > 1048576]

# Use
objects[shallow > 1MB]
```

### 7. Chain Operations Efficiently

Filter early to reduce data:
```bash
# Good: filter first
objects/java.lang.String[shallow > 1KB] | groupBy(class) | top(10)

# Less efficient: groupBy all, then filter
objects/java.lang.String | groupBy(class) | filter(count > 100)
```

### 8. Start Leak Analysis with checkLeaks

Use built-in detectors for quick wins:
```bash
# Interactive wizard runs all detectors
checkLeaks()

# Or target specific leak types
checkLeaks(detector="threadlocal-leak")
checkLeaks(detector="classloader-leak")
```

### 9. Use Retained Size for Impact Analysis

Shallow size shows object's own memory; retained size shows total impact:
```bash
# Find objects with highest memory impact
objects | top(10, retained)

# Then trace why they're retained
objects[id = 0x12345] | pathToRoot
```

## Next Steps

- Read the [HdumpPath Reference](../hdumppath.md) for complete query syntax
- Explore [JFR Shell Tutorial](Tutorial.md) for JFR analysis
- Check [Scripting Guide](Scripting.md) for automating analysis

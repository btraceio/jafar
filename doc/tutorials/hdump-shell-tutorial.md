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
jfr-shell /path/to/dump.hprof

# Or start empty and open later
jfr-shell
jfr> open /path/to/dump.hprof
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
jfr> objects | count

# String objects
jfr> objects/java.lang.String | count

# Objects larger than 1KB
jfr> objects[shallow > 1KB] | count
```

### View Sample Objects

```bash
# First 10 objects
jfr> objects | head(10)

# First 10 strings
jfr> objects/java.lang.String | head(10)
```

### Get Statistics

```bash
# Shallow size statistics for all objects
jfr> objects | stats(shallow)

| count   | sum         | min | max      | avg   |
|---------|-------------|-----|----------|-------|
| 1923456 | 78,345,280  | 16  | 1048576  | 40.7  |
```

## Object Analysis

### Find Large Objects

```bash
# Top 10 by shallow size
jfr> objects | top(10, shallow)

# Large objects (>1MB)
jfr> objects[shallow > 1MB] | select(class, shallow)
```

### Analyze by Class

```bash
# Group objects by class
jfr> objects | groupBy(class, agg=count) | top(10, count)

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
jfr> objects | groupBy(class, agg=sum) | top(10, sum)

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
jfr> objects/java.lang.String | stats(shallow)

# Large strings
jfr> objects/java.lang.String[shallow > 1KB] | top(10, shallow)

# String count by size range
jfr> objects/java.lang.String[shallow > 100] | count
```

### Array Analysis

Arrays can consume significant memory:

```bash
# Large arrays
jfr> objects[arrayLength > 10000] | top(10, shallow)

# Byte arrays (common for buffers)
jfr> objects/byte[] | stats(shallow)

# Object arrays
jfr> objects/java.lang.Object[] | top(10, shallow)
```

## Class Analysis

### Class Overview

```bash
# All classes
jfr> classes | count

# Classes with most instances
jfr> classes | top(10, instanceCount)

| name                        | instanceCount |
|----------------------------|---------------|
| java.util.HashMap$Node     | 249,734       |
| java.lang.String           | 238,750       |
| java.lang.Object[]         | 156,234       |
```

### Find Specific Classes

```bash
# Search by name pattern
jfr> classes/java.util.* | select(name, instanceCount)

# Classes with many instances
jfr> classes[instanceCount > 1000] | sortBy(instanceCount desc)
```

### Class Hierarchy

```bash
# Find implementations of Map
jfr> objects/instanceof/java.util.Map | groupBy(class) | top(10, count)

| class                          | count  |
|-------------------------------|--------|
| java.util.HashMap             | 12,345 |
| java.util.LinkedHashMap       | 5,678  |
| java.util.concurrent.ConcurrentHashMap | 1,234 |
```

### Collection Analysis

```bash
# All collections
jfr> objects/instanceof/java.util.Collection | groupBy(class) | top(10, count)

# List implementations
jfr> objects/instanceof/java.util.List | groupBy(class) | top(5, count)

# Set implementations
jfr> objects/instanceof/java.util.Set | groupBy(class) | top(5, count)
```

## GC Root Analysis

GC roots are entry points that keep objects alive.

### Root Distribution

```bash
# GC roots by type
jfr> gcroots | groupBy(type, agg=count) | sortBy(count desc)

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
jfr> gcroots/THREAD_OBJ | head(10)

# Java frame roots (stack variables)
jfr> gcroots/JAVA_FRAME | head(10)
```

### JNI References

```bash
# JNI global references (potential leaks)
jfr> gcroots/JNI_GLOBAL | head(20)

jfr> gcroots/JNI_GLOBAL | count
```

## Memory Leak Detection

### Pattern 1: Unusually Large Instance Counts

Look for classes with unexpected instance counts:

```bash
# Classes with most instances
jfr> classes | top(20, instanceCount)

# Focus on application classes
jfr> classes/com.myapp.* | top(10, instanceCount)
```

### Pattern 2: Growing Collections

Look for large collections that might be accumulating:

```bash
# Large HashMaps
jfr> objects/java.util.HashMap[shallow > 10KB] | top(10, shallow)

# Large ArrayLists
jfr> objects/java.util.ArrayList[arrayLength > 1000] | head(10)
```

### Pattern 3: Duplicate Strings

String duplication wastes memory:

```bash
# String statistics
jfr> objects/java.lang.String | stats(shallow)

# Unique class names holding strings (rough indicator)
jfr> objects/java.lang.String | groupBy(class) | count
```

### Pattern 4: Cache Analysis

Unbounded caches often cause leaks:

```bash
# Find cache-like structures
jfr> objects/instanceof/java.util.Map | groupBy(class, agg=count)

# Large Maps that might be caches
jfr> classes/*Cache* | select(name, instanceCount)
```

## Common Analysis Patterns

### Memory Footprint by Package

```bash
# Group by package prefix
jfr> classes/com.myapp.* | top(10, instanceCount)
jfr> classes/org.springframework.* | top(10, instanceCount)
```

### Heap Summary

Create a comprehensive overview:

```bash
# Object count
jfr> objects | count

# Total shallow size
jfr> objects | sum(shallow)

# Class count
jfr> classes | count

# Top consumers
jfr> objects | groupBy(class, agg=sum) | top(10, sum)
```

### Compare Before/After

Open two heap dumps in separate sessions:

```bash
jfr> open before.hprof --alias before
jfr> open after.hprof --alias after

jfr> use before
jfr> objects/java.lang.String | count
# 100,000

jfr> use after
jfr> objects/java.lang.String | count
# 150,000 (50% increase!)
```

### Export for Further Analysis

```bash
# Export to JSON for external tools
jfr> objects | groupBy(class, agg=count) | top(100, count) --format json > classes.json
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

Take dumps at different times and compare:
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

## Next Steps

- Read the [HdumpPath Reference](../hdumppath.md) for complete query syntax
- Explore [JFR Shell Tutorial](jfr-shell-tutorial.md) for JFR analysis
- Check [Scripting Guide](../jfr-shell-scripting.md) for automating analysis

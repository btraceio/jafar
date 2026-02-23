# Tutorial: JFR Shell Script Execution

This tutorial teaches you how to create and execute JFR Shell scripts for automated JFR analysis.

## Prerequisites

- JFR Shell installed (via JBang or built from source)
- At least one JFR recording file for testing
- Basic understanding of JfrPath queries (see [JFRPath.md](JFRPath.md))

## Learning Path

1. [Your First Script](#your-first-script)
2. [Adding Variables](#adding-variables)
3. [Running Scripts Different Ways](#running-scripts-different-ways)
4. [Error Handling](#error-handling)
5. [Creating Reusable Templates](#creating-reusable-templates)

## Your First Script

Let's create a simple script to analyze execution samples in a JFR recording.

### Step 1: Create the Script

Create a file named `simple-analysis.jfrs`:

```bash
# My first JFR Shell script
# This script counts execution samples and shows the top 5 threads

open /path/to/your/recording.jfr

events/jdk.ExecutionSample | count()

events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(5, by=count)

close
```

### Step 2: Execute the Script

```bash
jfr-shell script simple-analysis.jfrs
```

You should see:
1. Event counts
2. Top 5 threads by sample count

### What Just Happened?

The script executed four commands in sequence:
1. `open` - Loaded the JFR recording
2. First `show` - Counted total execution samples
3. Second `show` - Grouped samples by thread and showed top 5
4. `close` - Closed the recording session

## Adding Variables

Hardcoded paths make scripts less reusable. Let's add variables!

### Step 1: Parameterize Your Script

Create `analysis-with-vars.jfrs`:

```bash
# Analysis script with variables
# Arguments: recording, top_n

open $1

events/jdk.ExecutionSample | count()

events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top($2, by=count)

close
```

### Step 2: Execute with Variables

```bash
jfr-shell script analysis-with-vars.jfrs \
  /path/to/recording.jfr \
  10
```

### Try Different Values

```bash
# Show top 20 threads
jfr-shell script analysis-with-vars.jfrs \
  /path/to/recording.jfr \
  20

# Analyze a different recording
jfr-shell script analysis-with-vars.jfrs \
  /path/to/another-recording.jfr \
  5
```

### Benefits of Variables

- ✅ One script, many recordings
- ✅ Adjustable thresholds and limits
- ✅ Shareable with teammates (no hardcoded paths)

## Running Scripts Different Ways

### Method 1: Direct Execution (What We've Done)

```bash
jfr-shell script script.jfrs value
```

### Method 2: From Stdin

Pipe the script content:

```bash
cat analysis.jfrs | jfr-shell script - /tmp/app.jfr
```

Or use redirection:

```bash
jfr-shell script - /tmp/app.jfr < analysis.jfrs
```

### Method 3: Shebang (Direct Execution)

Make your script directly executable!

**Step 1:** Add shebang to `executable-analysis.jfrs`:

```bash
#!/usr/bin/env -S jbang jfr-shell@btraceio script -
# Executable JFR analysis script
# Arguments: recording

open $1
events/jdk.ExecutionSample | count()
close
```

**Step 2:** Make it executable:

```bash
chmod +x executable-analysis.jfrs
```

**Step 3:** Run it directly:

```bash
./executable-analysis.jfrs recording=/tmp/app.jfr
```

No need to type `jfr-shell script` anymore!

### Method 4: From Interactive Mode

You can run scripts while in an interactive session:

```bash
$ jfr-shell
jfr> script analysis.jfrs /tmp/app.jfr
...output...
jfr> # Continue with more interactive commands
```

## Error Handling

Scripts can fail for various reasons. Let's learn to handle errors gracefully.

### Scenario: Missing Event Types

Create `risky-analysis.jfrs`:

```bash
# This script might fail if events don't exist
open $1

# Common event (usually present)
events/jdk.ExecutionSample | count()

# Custom event (might not exist)
events/com.example.CustomEvent | count()

# Another common event
events/jdk.GarbageCollection | count()

close
```

### Fail-Fast Mode (Default)

```bash
jfr-shell script risky-analysis.jfrs /tmp/app.jfr
```

If `com.example.CustomEvent` doesn't exist, the script stops and you'll see:

```
Error on line 8: Unknown event type: com.example.CustomEvent
  Command: events/com.example.CustomEvent | count()

Script execution failed. 2/5 commands executed successfully.
```

### Continue-on-Error Mode

```bash
jfr-shell script risky-analysis.jfrs \
  /tmp/app.jfr \
  --continue-on-error
```

Now all commands attempt to run, and you get a full error report at the end:

```
Script completed with errors:
  Line 8: Unknown event type: com.example.CustomEvent

Executed 4/5 commands successfully.
```

### When to Use Each Mode

- **Fail-fast (default)**: Critical analysis where all steps must succeed
- **Continue-on-error**: Exploratory analysis, optional event types, unknown recordings

## Creating Reusable Templates

Let's create a comprehensive analysis template you can reuse.

### Create `comprehensive-analysis.jfrs`:

```bash
#!/usr/bin/env -S jbang jfr-shell@btraceio script -
# Comprehensive JFR Analysis Template
#
# Analyzes: CPU, Memory, I/O, GC, Threading
#
# Usage:
#   ./comprehensive-analysis.jfrs recording=/path/to/file.jfr top_n=10
#
# Arguments:
#   recording - Path to JFR recording file (required)
#   top_n     - Number of top results to display (default: 10)

# Open recording
open $1

# === Recording Info ===
info

# === CPU Analysis ===
events/jdk.ExecutionSample | count()
events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top($2, by=count)

# === Memory Analysis ===
events/jdk.ObjectAllocationInNewTLAB | sum(allocationSize)
events/jdk.ObjectAllocationInNewTLAB | groupBy(objectClass/name) | top($2, by=sum(allocationSize))

# === I/O Analysis ===
events/jdk.FileRead | sum(bytes)
events/jdk.FileWrite | sum(bytes)
events/jdk.SocketRead | sum(bytes)
events/jdk.SocketWrite | sum(bytes)

# === GC Analysis ===
events/jdk.GarbageCollection | stats(duration)
events/jdk.GarbageCollection | groupBy(name) | top($2, by=count)

# === Threading Analysis ===
events/jdk.JavaMonitorEnter | groupBy(monitorClass/name) | top($2, by=count)
events/jdk.ThreadPark | groupBy(parkedClass/name) | top($2, by=count)

# Close
close
```

### Make It Executable:

```bash
chmod +x comprehensive-analysis.jfrs
```

### Use It:

```bash
# Basic usage
./comprehensive-analysis.jfrs recording=/tmp/prod-app.jfr top_n=10

# Show more results
./comprehensive-analysis.jfrs recording=/tmp/prod-app.jfr top_n=25

# Analyze multiple recordings
for jfr_file in /tmp/recordings/*.jfr; do
  echo "=== Analyzing $jfr_file ==="
  ./comprehensive-analysis.jfrs recording=$jfr_file top_n=5
done
```

### Template Benefits

- ✅ Consistent analysis across recordings
- ✅ Share with team for standardized diagnostics
- ✅ Version control with application
- ✅ Customize by adjusting parameters
- ✅ Document analysis procedures

## Real-World Example: Performance Regression Detection

Let's create a script to detect performance regressions.

### Create `perf-regression-check.jfrs`:

```bash
#!/usr/bin/env -S jbang jfr-shell@btraceio script -
# Performance Regression Check
#
# Checks if key metrics are within acceptable thresholds
#
# Arguments:
#   recording           - JFR recording to analyze
#   max_gc_pause_ms    - Maximum acceptable GC pause (milliseconds)
#   max_alloc_rate_mb  - Maximum allocation rate (MB/sec)

open $1

# Check GC pause times
events/jdk.GarbageCollection | stats(duration)

# Check allocation rate
events/jdk.ObjectAllocationInNewTLAB | sum(allocationSize)

# Check lock contention
events/jdk.JavaMonitorEnter | count()

close

# Note: In the future, we could add conditional logic
# For now, manually review the output against thresholds
```

### Use in CI/CD:

```bash
#!/bin/bash
# ci-performance-test.sh

# Run application with JFR
java -XX:StartFlightRecording:filename=/tmp/test.jfr,duration=60s -jar app.jar

# Analyze
./perf-regression-check.jfrs \
  recording=/tmp/test.jfr \
  max_gc_pause_ms=100 \
  max_alloc_rate_mb=500 \
  > perf-report.txt

# Manual check for now
echo "Review perf-report.txt for regressions"
```

## Next Steps

Now that you know how to execute scripts:

1. **Practice**: Create scripts for your common analysis tasks
2. **Explore Examples**: Check out scripts in `jfr-shell/src/main/resources/examples/`
3. **Learn Recording**: See [CommandRecording.md](CommandRecording.md)
4. **Master JfrPath**: Deep dive into [JFRPath.md](JFRPath.md)
5. **Share Scripts**: Create a team library of analysis templates

## Exercises

### Exercise 1: File I/O Analysis

Create a script that:
- Takes a recording and byte threshold
- Shows all file reads above the threshold
- Sums total bytes read and written

<details>
<summary>Solution</summary>

```bash
#!/usr/bin/env -S jbang jfr-shell@btraceio script -
# Arguments: recording, min_bytes

open $1

events/jdk.FileRead[bytes>=$2] --limit 20
events/jdk.FileRead | sum(bytes)
events/jdk.FileWrite | sum(bytes)

close
```

Usage:
```bash
./fileio-analysis.jfrs recording=/tmp/app.jfr min_bytes=10000
```
</details>

### Exercise 2: Multi-Recording Comparison

Create a script that:
- Takes two recordings (baseline and current)
- Opens both as separate sessions
- Shows top 5 threads from each

<details>
<summary>Solution</summary>

```bash
#!/usr/bin/env -S jbang jfr-shell@btraceio script -
# Arguments: baseline, current

open $1 --alias baseline
use baseline
events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(5, by=count)

open $2 --alias current
use current
events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(5, by=count)

close --all
```

Usage:
```bash
./compare.jfrs baseline=/tmp/v1.jfr current=/tmp/v2.jfr
```
</details>

### Exercise 3: Adaptive Analysis

Create a script with continue-on-error that:
- Tries to analyze various event types
- Some may not exist in all recordings
- Shows whatever data is available

<details>
<summary>Solution</summary>

```bash
#!/usr/bin/env -S jbang jfr-shell@btraceio script -
# Arguments: recording

open $1

# Standard events (usually present)
events/jdk.ExecutionSample | count()
events/jdk.GarbageCollection | stats(duration)

# Optional events (may not exist)
events/jdk.ObjectAllocationSample | count()
events/jdk.NativeMemoryUsage | stats(committed)
events/com.example.CustomEvent | count()

close
```

Usage:
```bash
./adaptive-analysis.jfrs recording=/tmp/app.jfr --continue-on-error
```
</details>

### Exercise 4: Field Projection with Expressions

Create a script that uses computed expressions to transform and format output:
- Shows file I/O with sizes in human-readable format (KB/MB)
- Computes I/O throughput (bytes per microsecond)
- Adds conditional labels for small/medium/large operations

<details>
<summary>Solution</summary>

```bash
#!/usr/bin/env -S jbang jfr-shell@btraceio script -
# File I/O Analysis with Computed Fields
# Arguments: recording

open $1

# File reads with computed fields
events/jdk.FileRead | select(
  path,
  bytes / 1024 as kilobytes,
  if(bytes > 1048576, 'large', if(bytes > 1024, 'medium', 'small')) as size_category,
  duration / 1000 as milliseconds
) --limit 20

# Summarize with expressions
events/jdk.FileRead | select(
  bytes / 1024 as kb,
  duration
) | stats(kb)

# Group with computed keys
events/jdk.FileRead | select(
  if(bytes > 1048576, '>1MB', if(bytes > 10240, '>10KB', '<10KB')) as category,
  path
) | groupBy(category)

close
```

Usage:
```bash
./computed-fields-analysis.jfrs recording=/tmp/app.jfr
```

This demonstrates:
- Arithmetic expressions: `bytes / 1024`
- Nested conditionals: `if(condition, if(...))`
- String functions and concatenation
- Using computed fields in aggregations
</details>

## Summary

You've learned to:

- ✅ Create simple JFR Shell scripts
- ✅ Use variables for parameterization
- ✅ Execute scripts in multiple ways (file, stdin, shebang, interactive)
- ✅ Handle errors (fail-fast vs continue-on-error)
- ✅ Build reusable analysis templates
- ✅ Integrate with CI/CD workflows

Happy scripting! 🚀

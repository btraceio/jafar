# JFR Shell Scripting Guide

JFR Shell supports powerful scripting capabilities that allow you to automate JFR analysis workflows, create reusable analysis templates, and share analysis procedures with your team.

## Table of Contents

- [Overview](#overview)
- [Script File Format](#script-file-format)
- [Positional Parameters](#positional-parameters)
- [Variables](#variables)
- [Conditionals](#conditionals)
- [Executing Scripts](#executing-scripts)
- [Recording Commands](#recording-commands)
- [Shebang Support](#shebang-support)
- [Example Scripts](#example-scripts)
- [Best Practices](#best-practices)

## Overview

JFR Shell scripts (`.jfrs` extension) are plain text files containing sequences of shell commands and JfrPath queries. Scripts support:

- **Positional parameters** - Parameterize scripts with bash-style `$1`, `$2`, `$@` syntax
- **Comments** - Document your scripts with `#` comments
- **All shell commands** - Use any command available in interactive mode
- **Error handling** - Continue-on-error mode for robust scripts
- **Stdin support** - Read scripts from stdin for piping and shebangs

## Script File Format

Scripts are line-based text files with a simple syntax:

```bash
# Comments start with # and are ignored
# Blank lines are also ignored

# Commands are executed line by line
open /path/to/recording.jfr

# JfrPath queries
show events/jdk.ExecutionSample | count()

# Multi-word commands
show events/jdk.FileRead[bytes>=1000] --limit 10

# Close the session
close
```

### File Extension

By convention, JFR Shell scripts use the `.jfrs` extension (JFR Shell Script).

## Positional Parameters

Scripts support bash-style positional parameter substitution using `$1`, `$2`, `$@` syntax. Positional parameters make scripts reusable across different recordings, thresholds, and parameters.

### Parameter Syntax

```bash
# Use $1, $2, etc. anywhere in a command
open $1

show events/jdk.FileRead[bytes>=$2] --limit $3

# $@ expands to all parameters space-separated
show events/$1 | count()
```

### Passing Parameters

Parameters are passed as positional arguments after the script path:

```bash
# Single parameter
jfr-shell script analysis.jfrs /path/to/file.jfr

# Multiple parameters
jfr-shell script analysis.jfrs /tmp/app.jfr 1000 100
```

Parameters are positional (like bash):
- `$1` = first argument
- `$2` = second argument
- `$3` = third argument, etc.
- `$@` = all arguments space-separated

### Out-of-Bounds Parameters

If a script references a parameter that wasn't provided, execution fails with a clear error message:

```
Error on line 3: Positional parameter $1 out of bounds. Script has 0 argument(s).
  Command: open $1
```

### Example with Parameters

**script.jfrs:**
```bash
# Configurable analysis script
# Arguments: $1=recording, $2=min_bytes, $3=top_n

open $1

# Show large file reads
show events/jdk.FileRead[bytes>=$2] --limit $3

# Thread analysis
show events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top($3, by=count)

close
```

**Execution:**
```bash
jfr-shell script script.jfrs /tmp/app.jfr 1024 20
```

## Variables

JFR Shell supports variables for storing and reusing values in your analysis. Variables can hold scalar values (strings, numbers) or lazily-evaluated query results.

### Variable Types

**Scalar Variables** store immediate values:
```bash
set threshold = 1000
set filename = "/tmp/output.txt"
set limit = 20
```

**Lazy Query Variables** store JfrPath queries that are evaluated on-demand:
```bash
set fileReads = events/jdk.FileRead
set threadStats = events/jdk.ExecutionSample | groupBy(sampledThread/javaName)
set eventCount = events/jdk.FileRead | count()
```

Lazy variables preserve memory by not materializing results until accessed. They also cache results for subsequent accesses.

**Map Variables** store structured data with nested field access (⭐ NEW):
```bash
set config = {"threshold": 1000, "enabled": true, "pattern": ".*Error.*"}
set db = {"host": "localhost", "port": 5432, "credentials": {"user": "admin"}}
set paths = {"logs": "/var/log/.*", "temp": "/tmp/.*", "home": "/home/.*"}
```

Maps use JSON-like syntax and support strings, numbers, booleans, null, and nested maps. See [Map Variables Tutorial](map-variables.md) for complete reference.

### Variable Scopes

Variables can be session-scoped (default) or global:

```bash
# Session-scoped (cleared when session closes)
set myvar = "value"

# Global (persists across all sessions)
set --global myvar = "value"
```

### Setting Variables

Use the `set` command (or its alias `let`):

```bash
# Scalar values
set name = "my-analysis"
set threshold = 1000
let limit = 25

# Lazy query (assigned when RHS contains JfrPath expression)
set reads = events/jdk.FileRead[bytes>=1000]
set stats = events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(10, by=count)
```

The `=` sign is required but spaces around it are flexible:
```bash
set x = 10       # OK
set x=10         # OK
set x= 10        # OK
```

### Using Variables

Variables are accessed using `${varname}` syntax:

```bash
# Simple substitution
echo "Threshold is: ${threshold}"

# In JfrPath queries
show events/jdk.FileRead[bytes>=${threshold}] --limit ${limit}

# Access query result fields
set count = events/jdk.FileRead | count()
echo "Found ${count.count} file read events"
```

#### Advanced Substitution Syntax

For structured results, access nested fields and array elements:

```bash
# Access a field from result
${varname.fieldname}

# Access nested fields (multi-level)
${varname.field.subfield.deeply.nested}

# Access array element
${varname[0]}

# Access field from array element
${varname[0].fieldname}

# Size property (works for lazy results and maps)
${varname.size}
```

**Query result example:**
```bash
set threads = events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(3, by=count)
echo "Top thread: ${threads[0].sampledThread/javaName}"
echo "Sample count: ${threads[0].count}"
echo "Total groups: ${threads.size}"
```

**Map variable example:**
```bash
set config = {"threshold": 1000, "db": {"host": "localhost", "port": 5432}}
echo "Threshold: ${config.threshold}"
echo "Database: ${config.db.host}:${config.db.port}"
echo "Config size: ${config.size} entries"
```

### Listing Variables

Use the `vars` command to list defined variables:

```bash
# List all variables
vars

# List only session variables
vars --session

# List only global variables
vars --global

# Show detailed info for a specific variable
vars --info config
```

Output shows variable type and value/description:
```
Session variables:
  threshold = 1000 (scalar)
  config = map{threshold=1000, enabled=true, pattern=".*Error.*"} (map)
  fileReads = events/jdk.FileRead (lazy query, not evaluated)
  stats = events/jdk.ExecutionSample | groupBy(...) (lazy query, cached: 15 items)

Global variables:
  defaultLimit = 20 (scalar)
```

**Detailed variable info:**
```bash
jfr> vars --info config
Variable: config
Type: map
Size: 3 entries
Structure: map{threshold=1000, enabled=true, pattern=".*Error.*"}
```

### Removing Variables

Use the `unset` command:

```bash
unset myvar
unset --global globalvar
```

### Cache Management

Lazy query variables cache their results after first evaluation. Use `invalidate` to clear the cache and force re-evaluation:

```bash
set data = events/jdk.FileRead | count()
echo ${data.count}    # Evaluates and caches
# ... do something that might affect results ...
invalidate data       # Clear cache
echo ${data.count}    # Re-evaluates
```

### Echo Command

Print text with variable substitution:

```bash
echo "Analysis for ${filename}"
echo "Found ${count.count} events exceeding ${threshold} bytes"
echo "Top ${limit} results:"
```

### Complete Example

```bash
#!/usr/bin/env -S jbang jfr-shell@btraceio script -
# Parameterized analysis with variables
# Arguments: $1=recording, $2=threshold

open $1

# Set configuration variables
set threshold = $2
set limit = 10

# Store lazy queries
set fileReads = events/jdk.FileRead[bytes>=${threshold}]
set readCount = events/jdk.FileRead[bytes>=${threshold}] | count()

echo "=== File Read Analysis ==="
echo "Threshold: ${threshold} bytes"
echo "Events found: ${readCount.count}"

# Show results if any found
show events/jdk.FileRead[bytes>=${threshold}] --limit ${limit}

# Thread analysis with caching
set threadStats = events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(5, by=count)
echo ""
echo "=== Top 5 Threads by Samples ==="
echo "Thread count: ${threadStats.size}"
show events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(5, by=count)

close
```

## Conditionals

JFR Shell supports block-based conditionals (`if`/`elif`/`else`/`endif`) for controlling script execution flow based on conditions.

### Syntax

```bash
if <condition>
  # commands executed if condition is true
elif <condition>
  # commands executed if previous conditions were false and this is true
else
  # commands executed if all conditions were false
endif
```

### Condition Expressions

Conditions support comparisons, arithmetic, logical operators, and built-in functions.

#### Comparisons

```bash
if ${count} == 0
if ${value} != "error"
if ${bytes} > 1000
if ${threshold} >= 100
if ${size} < 50
if ${limit} <= 10
```

#### Arithmetic

```bash
if ${a} + ${b} > 100
if ${var.size} * 2 <= ${limit}
if ${total} / ${count} > 50
```

#### Logical Operators

```bash
if ${a} > 0 && ${b} > 0
if ${a} == 0 || ${b} == 0
if !${flag}
if (${a} > 0 && ${b} > 0) || ${c} == 1
```

#### Built-in Functions

```bash
# Check if a variable exists
if exists(myvar)

# Check if a variable is empty (doesn't exist, null, or empty string/result)
if empty(results)

# Negation
if !exists(optionalVar)
if !empty(data)
```

### Interactive Mode

In interactive mode, the prompt changes to show nesting depth:

```
jfr> if ${count} > 0
...(1)>   echo "Found events"
...(1)>   if ${count} > 100
...(2)>     echo "That's a lot!"
...(2)>   endif
...(1)> else
...(1)>   echo "No events found"
...(1)> endif
jfr>
```

### Complete Examples

#### Checking Query Results

```bash
#!/usr/bin/env -S jbang jfr-shell@btraceio script -
# Arguments: $1=recording

open $1

set fileReads = events/jdk.FileRead | count()

if ${fileReads.count} > 0
  echo "Found ${fileReads.count} file read events"

  if ${fileReads.count} > 1000
    echo "Warning: High number of file reads detected!"
  elif ${fileReads.count} > 100
    echo "Moderate file I/O activity"
  else
    echo "Light file I/O activity"
  endif

  show events/jdk.FileRead | top(10, by=bytes)
else
  echo "No file read events in this recording"
endif

close
```

#### Conditional Analysis

```bash
#!/usr/bin/env -S jbang jfr-shell@btraceio script -
# Arguments: $1=recording, $2=analysis_type

open $1

if ${2} == "cpu"
  echo "=== CPU Analysis ==="
  show events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(10, by=count)
elif ${2} == "io"
  echo "=== I/O Analysis ==="
  show events/jdk.FileRead | sum(bytes)
  show events/jdk.FileWrite | sum(bytes)
elif ${2} == "gc"
  echo "=== GC Analysis ==="
  show events/jdk.GarbageCollection | stats(duration)
else
  echo "Unknown analysis type: ${2}"
  echo "Supported: cpu, io, gc"
endif

close
```

#### Using exists() and empty()

```bash
# Check if optional configuration exists
set threshold = 1000
if exists(custom_threshold)
  set threshold = ${custom_threshold}
endif

# Process only if results are not empty
set results = events/jdk.FileRead[bytes>=${threshold}]
if !empty(results)
  echo "Found ${results.size} events exceeding threshold"
  show events/jdk.FileRead[bytes>=${threshold}] --limit 20
else
  echo "No events exceed the threshold of ${threshold} bytes"
endif
```

### Nesting

Conditionals can be nested to arbitrary depth:

```bash
if ${a} > 0
  if ${b} > 0
    if ${c} > 0
      echo "All positive"
    else
      echo "c is not positive"
    endif
  else
    echo "b is not positive"
  endif
else
  echo "a is not positive"
endif
```

### Error Handling

#### Unclosed Conditionals

If you exit the shell with unclosed conditionals, a warning is displayed:

```
jfr> if ${x} > 0
...(1)> exit
Warning: 1 unclosed conditional block(s)
Goodbye!
```

#### Mismatched Keywords

```bash
# Error: elif without if
elif ${x} > 0  # Error: elif without if

# Error: else without if
else           # Error: else without if

# Error: endif without if
endif          # Error: endif without if
```

## Executing Scripts

### Scripts Directory

JFR Shell maintains a scripts directory at `~/.jfr-shell/scripts` where you can store reusable scripts. Scripts in this directory can be run by name without specifying the full path.

### Listing Available Scripts

List all scripts in the scripts directory:

```bash
jfr> script list
Available scripts in /Users/user/.jfr-shell/scripts:

  basic-analysis           Comprehensive recording overview
  thread-profiling         Detailed thread analysis
  gc-analysis              GC pause and heap statistics

Run with: script run <name> [args...]
```

The first comment line in each script (after the shebang) is shown as the description.

### Running Scripts by Name

Run a script from the scripts directory by name (without the `.jfrs` extension):

```bash
jfr> script run basic-analysis /tmp/app.jfr
jfr> script run thread-profiling /tmp/app.jfr 20
```

### Running Scripts by Path

Run a script by specifying the full path:

```bash
jfr> script /path/to/script.jfrs arg1 arg2 arg3
```

### From Command Line

Execute scripts directly from the command line:

```bash
# Run by path
jfr-shell script /path/to/script.jfrs arg1 arg2 arg3

# From stdin
jfr-shell script - /tmp/app.jfr <<'EOF'
open $1
show events/jdk.ExecutionSample | count()
close
EOF

# Redirect from file
jfr-shell script - /tmp/app.jfr < analysis.jfrs
```

### Error Handling

By default, script execution stops on the first error (fail-fast mode). Use `--continue-on-error` to execute all commands:

```bash
# Stop on first error (default)
jfr-shell script analysis.jfrs /tmp/app.jfr

# Continue on errors
jfr-shell script analysis.jfrs /tmp/app.jfr --continue-on-error
```

When errors occur, you'll see a detailed report:

```
Error on line 15: No session open. Use 'open <file>' first.
  Command: show events/jdk.ExecutionSample

Script completed with errors:
  Line 15: No session open. Use 'open <file>' first.
  Line 23: Unknown event type: jdk.InvalidEvent

Executed 21/25 commands successfully.
```

## Recording Commands

JFR Shell can record your interactive commands into an executable script file. This is perfect for:

- **Capturing ad-hoc analysis** - Record exploratory analysis for later reuse
- **Creating templates** - Build script templates by example
- **Documentation** - Record steps for reproducing issues
- **Collaboration** - Share exact analysis procedures with teammates

### Recording Commands

Start recording with `record start`:

```bash
jfr> record start
Recording started: /Users/user/.jfr-shell/recordings/session-20251226143022.jfrs

jfr> record start /tmp/my-analysis.jfrs
Recording started: /tmp/my-analysis.jfrs
```

If no path is provided, scripts are saved to `~/.jfr-shell/recordings/session-{timestamp}.jfrs`.

### Check Recording Status

```bash
jfr> record status
Recording to: /tmp/my-analysis.jfrs

jfr> record status
Not currently recording
```

### Stop Recording

```bash
jfr> record stop
Recording stopped: /tmp/my-analysis.jfrs
```

Recording also auto-saves when you exit the shell.

### Recorded Script Format

Recorded scripts include timestamp comments for context but are immediately executable:

```bash
# JFR Shell Recording
# Started: 2025-12-26T14:30:00Z
# Session: my-analysis.jfrs

# [14:30:15]
open /path/to/recording.jfr

# [14:30:20]
show events/jdk.ExecutionSample --limit 10

# [14:31:45]
show events/jdk.ExecutionSample | groupBy(sampledThread/javaName)

# Recording stopped: 2025-12-26T14:32:10Z
```

You can execute this script directly:

```bash
jfr-shell script /tmp/my-analysis.jfrs
```

### Converting to Parameterized Scripts

Recorded scripts often contain hardcoded paths. Convert them to parameterized scripts by replacing paths with positional parameters:

**Original recorded script:**
```bash
open /Users/john/recordings/prod-app-20251226.jfr
show events/jdk.FileRead[bytes>=1000] --limit 10
```

**Parameterized version:**
```bash
# Arguments: $1=recording, $2=threshold, $3=limit
open $1
show events/jdk.FileRead[bytes>=$2] --limit $3
```

**Usage:**
```bash
jfr-shell script analysis.jfrs /Users/john/recordings/prod-app-20251226.jfr 1000 10
```

## Shebang Support

JFR Shell scripts can be made directly executable using Unix shebang notation. This allows scripts to be run like standalone programs.

### Basic Shebang

Add this line at the top of your script:

```bash
#!/usr/bin/env -S jbang jfr-shell@btraceio script -
```

This tells the shell to execute the script using JBang's jfr-shell distribution.

### Making Scripts Executable

```bash
# Add shebang to script
cat > analyze.jfrs <<'EOF'
#!/usr/bin/env -S jbang jfr-shell@btraceio script -
# Arguments: $1=recording

open $1
show events/jdk.ExecutionSample | count()
close
EOF

# Make executable
chmod +x analyze.jfrs

# Run directly
./analyze.jfrs /tmp/app.jfr
```

### Shebang Parameter Passing

Parameters are passed as positional arguments after the script name:

```bash
#!/usr/bin/env -S jbang jfr-shell@btraceio script -

# This script expects: $1=recording, $2=threshold, $3=limit
open $1
show events/jdk.FileRead[bytes>=$2] --limit $3
```

Execute with:

```bash
./script.jfrs /tmp/app.jfr 1000 10
```

### Requirements

- Unix-like system (Linux, macOS)
- `env` command with `-S` flag support (modern systems)
- JBang installed and in PATH
- jfr-shell available via JBang catalog

## Example Scripts

JFR Shell includes several example scripts in `jfr-shell/src/main/resources/examples/`:

### basic-analysis.jfrs

Comprehensive recording overview including metadata, top threads, I/O, and GC statistics.

```bash
#!/usr/bin/env -S jbang jfr-shell@btraceio script -
# Arguments: $1=recording

open $1
info
metadata --summary
show events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(10, by=count)
show events/jdk.FileRead | sum(bytes)
show events/jdk.FileWrite | sum(bytes)
show events/jdk.GarbageCollection | stats(duration)
close
```

**Usage:**
```bash
jfr-shell script basic-analysis.jfrs /tmp/app.jfr

# Or with shebang:
chmod +x basic-analysis.jfrs
./basic-analysis.jfrs /tmp/app.jfr
```

### thread-profiling.jfrs

Detailed thread analysis including execution samples, allocations, contention, and blocking operations.

```bash
#!/usr/bin/env -S jbang jfr-shell@btraceio script -
# Arguments: $1=recording, $2=top_n

open $1
show events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top($2, by=count)
show events/jdk.ThreadAllocationStatistics | groupBy(thread/javaName) | top($2, by=sum(allocated))
show events/jdk.JavaMonitorEnter | groupBy(monitorClass/name) | top($2, by=count)
show events/jdk.ThreadSleep | groupBy(thread/javaName) | top($2, by=sum(time))
close
```

**Usage:**
```bash
jfr-shell script thread-profiling.jfrs /tmp/app.jfr 15
```

### gc-analysis.jfrs

Comprehensive GC analysis including pause times, heap utilization, and allocation patterns.

```bash
#!/usr/bin/env -S jbang jfr-shell@btraceio script -
# Arguments: $1=recording

open $1
show events/jdk.GarbageCollection | stats(duration)
show events/jdk.GarbageCollection | groupBy(name) | top(10, by=count)
show events/jdk.GCHeapSummary | stats(heapUsed)
show events/jdk.ObjectAllocationInNewTLAB | groupBy(objectClass/name) | top(20, by=sum(allocationSize))
show events/jdk.ObjectAllocationInNewTLAB | sum(allocationSize)
close
```

## Best Practices

### 1. Add Clear Headers

Start scripts with a comment header explaining purpose, usage, and parameters:

```bash
#!/usr/bin/env -S jbang jfr-shell@btraceio script -
# Performance Analysis Script
#
# Analyzes CPU hotspots, memory allocations, and I/O patterns
#
# Usage:
#   jfr-shell script perf-analysis.jfrs /path/to/file.jfr 20
#
# Arguments:
#   $1 - Path to JFR recording file
#   $2 - Number of top results to display (default: 20)
```

### 2. Use Meaningful Parameter Descriptions

```bash
# Good
# Arguments: $1=recording_path, $2=min_bytes, $3=max_results
open $1
show events/jdk.FileRead[bytes>=$2] --limit $3

# Less clear (but sometimes acceptable for simple scripts)
# Arguments: $1=file, $2=threshold, $3=limit
open $1
show events/jdk.FileRead[bytes>=$2] --limit $3
```

### 3. Group Related Operations

Use comments to section your script:

```bash
# Session setup
open $1
info

# CPU Analysis
show events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(10, by=count)
show events/jdk.ExecutionSample | groupBy(stackTrace) | top(10, by=count)

# Memory Analysis
show events/jdk.ObjectAllocationInNewTLAB | groupBy(objectClass/name) | top(20, by=sum(allocationSize))

# Cleanup
close
```

### 4. Provide Default Values in Documentation

Document recommended default values in comments:

```bash
# Arguments:
#   $1 - Path to JFR recording (required)
#   $2 - Minimum operation duration in ms (default: 100)
#   $3 - Number of results to show (default: 10)
```

### 5. Test with Different Recordings

Test your scripts with various recordings to ensure robustness:

```bash
# Test with different event profiles
jfr-shell script analysis.jfrs test-light.jfr
jfr-shell script analysis.jfrs test-full.jfr
jfr-shell script analysis.jfrs prod-snapshot.jfr
```

### 6. Use Continue-on-Error for Exploratory Scripts

When analyzing unknown recordings, use `--continue-on-error` to handle missing event types gracefully:

```bash
jfr-shell script exploratory-analysis.jfrs /tmp/unknown.jfr --continue-on-error
```

### 7. Version Control Your Scripts

Store scripts in version control alongside your application:

```
project/
├── src/
├── jfr-scripts/
│   ├── production-analysis.jfrs
│   ├── memory-leak-detection.jfrs
│   └── performance-regression.jfrs
└── README.md
```

### 8. Create Script Libraries

Organize scripts by purpose:

```
jfr-scripts/
├── diagnostics/
│   ├── thread-deadlock-check.jfrs
│   ├── memory-pressure.jfrs
│   └── slow-queries.jfrs
├── performance/
│   ├── cpu-hotspots.jfrs
│   ├── allocation-rate.jfrs
│   └── gc-impact.jfrs
└── monitoring/
    ├── daily-health-check.jfrs
    └── weekly-summary.jfrs
```

### 9. Document Event Type Requirements

Note which event types your script requires:

```bash
# This script requires these event types to be enabled in the recording:
# - jdk.ExecutionSample
# - jdk.ThreadAllocationStatistics
# - jdk.JavaMonitorEnter
#
# Enable with: -XX:StartFlightRecording:settings=profile
```

### 10. Provide Usage Examples

Include concrete usage examples in script comments:

```bash
# Examples:
#
# Basic usage:
#   ./analyze.jfrs /tmp/app.jfr
#
# With custom thresholds:
#   ./analyze.jfrs /tmp/app.jfr 10000 50
#
# Analyze multiple recordings:
#   for f in *.jfr; do ./analyze.jfrs "$f"; done
```

## Troubleshooting

### Script Not Found

```bash
Error: Script file not found: analysis.jfrs
```

**Solution:** Use absolute paths or check current directory:
```bash
jfr-shell script /absolute/path/to/script.jfrs
jfr-shell script ./relative/path/script.jfrs
```

### Out-of-Bounds Parameter Error

```bash
Error on line 3: Positional parameter $1 out of bounds. Script has 0 argument(s).
```

**Solution:** Pass all required parameters:
```bash
jfr-shell script analysis.jfrs /path/to/file.jfr
```

### Shebang Not Working

```bash
./script.jfrs: bad interpreter: No such file or directory
```

**Solutions:**
- Verify JBang is installed: `jbang version`
- Check shebang line: `#!/usr/bin/env -S jbang jfr-shell@btraceio script -`
- Ensure script is executable: `chmod +x script.jfrs`
- Try explicit path: `jbang jfr-shell@btraceio script script.jfrs /tmp/app.jfr`

### Script Fails Silently

If a script appears to do nothing, check for:
- Missing `open` command before queries
- Missing positional parameters
- Event types not present in recording

Use `--continue-on-error` to see all errors:
```bash
jfr-shell script analysis.jfrs /tmp/app.jfr --continue-on-error
```

## Advanced Techniques

### Multi-File Analysis

Compare multiple recordings in a single script:

```bash
# Arguments: $1=baseline, $2=current

# Analyze baseline
open $1 --alias baseline
use baseline
show events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(5, by=count)

# Analyze current
open $2 --alias current
use current
show events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(5, by=count)

# Cleanup
close --all
```

**Usage:**
```bash
jfr-shell script compare.jfrs /tmp/v1.0.jfr /tmp/v2.0.jfr
```

### Conditional Logic with Continue-on-Error

Use continue-on-error to implement optional analysis:

```bash
# Core analysis (always runs)
open $1
show events/jdk.ExecutionSample | count()

# Optional analyses (may not have these events)
show events/jdk.CustomEvent | count()
show events/jdk.ExperimentalFeature | count()

close
```

Execute with:
```bash
jfr-shell script analysis.jfrs /tmp/app.jfr --continue-on-error
```

### Dynamic Event Type Analysis

Analyze events by pattern:

```bash
# Arguments: $1=recording, $2=event_pattern

open $1
metadata --search $2
# Note: Can't dynamically use search results in show commands yet
# This is a future enhancement
close
```

## Integration with CI/CD

### Automated Recording Analysis

Run scripts in CI/CD pipelines:

```bash
#!/bin/bash
# ci-analyze.sh

RECORDING="/tmp/performance-test.jfr"
THRESHOLD_MS=100

# Run performance test and capture JFR
java -XX:StartFlightRecording:filename=$RECORDING,duration=60s -jar app.jar

# Analyze recording
jfr-shell script performance-check.jfrs \
  $RECORDING \
  $THRESHOLD_MS \
  > analysis-report.txt

# Check for performance regressions
if grep -q "REGRESSION" analysis-report.txt; then
  echo "Performance regression detected!"
  exit 1
fi
```

### Regression Detection Script

```bash
# performance-check.jfrs
# Arguments: $1=recording, $2=max_gc_pause_ms

open $1

show events/jdk.GarbageCollection | stats(duration)
# Add logic to check if max duration > $2
# Output: REGRESSION if threshold exceeded

close
```

## Summary

JFR Shell scripting provides:

- ✅ **Automation** - Run complex analyses with a single command
- ✅ **Reusability** - Create templates for common analysis patterns
- ✅ **Parameterization** - Flexible scripts that work with any recording
- ✅ **Collaboration** - Share analysis procedures as executable scripts
- ✅ **Documentation** - Scripts serve as runnable documentation
- ✅ **CI/CD Integration** - Automate performance testing and monitoring

For more examples and tutorials, see:
- [JFR Shell Tutorial](jfr-shell-tutorial.md)
- [Example Scripts](../jfr-shell/src/main/resources/examples/)
- [JfrPath Query Language](jfrpath.md)

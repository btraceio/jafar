# JFR Shell

An interactive CLI for exploring and analyzing Java Flight Recorder (JFR) files with a powerful query language.

**Quick Links**: [Usage Guide](../doc/jfr_shell_usage.md) | [JfrPath Reference](../doc/jfrpath.md)

## Features

- **Interactive REPL** with intelligent tab completion
- **JfrPath query language** for filtering, projection, and aggregation
- **Variables**: store scalars and lazy query results with `${var}` substitution ⭐ NEW
- **Conditionals**: if/elif/else/endif for control flow ⭐ NEW
- **Scripting support**: record, save, and replay analysis workflows
- **Positional parameters**: parameterize scripts for reusability
- **Shebang support**: make scripts directly executable
- **Multiple output formats**: table (default) and JSON
- **Multi-session support**: work with multiple recordings simultaneously
- **Non-interactive mode**: execute queries from command line for scripting/CI
- **Fast streaming parser**: efficient memory usage, handles large recordings

## Quick Start

### Installation

#### Option 1: JBang (Easiest - No Build Required) ⭐

[JBang](https://jbang.dev) provides the simplest installation - no Java or build tools required!

```bash
# One-time JBang setup (if needed)
curl -Ls https://sh.jbang.dev | bash -s - app setup

# Run jfr-shell directly
jbang jfr-shell@btraceio recording.jfr

# Or install as a permanent command
jbang app install jfr-shell@btraceio
jfr-shell recording.jfr
```

See [JBang Usage Guide](../doc/jbang-usage.md) for more options.

#### Option 2: Standalone Distribution (Bundled JRE)

Build a self-contained distribution with bundled JRE:

```bash
# Build standalone distribution
./gradlew :jfr-shell:jlinkDist

# Run the standalone distribution
jfr-shell/build/jlink/bin/jfr-shell

# Or create a distributable zip archive (~39MB)
./gradlew :jfr-shell:jlinkZip
# Distribution will be at: jfr-shell/build/distributions/jfr-shell-<version>-standalone.zip
```

#### Option 3: Build Fat JAR

```bash
# Build and use fat JAR (requires Java 25+)
./gradlew :jfr-shell:shadowJar
java -jar jfr-shell/build/libs/jfr-shell-all.jar
```

### Interactive Session

```
jfr> open recording.jfr
Opened session #1: recording.jfr

jfr> show events/jdk.FileRead[bytes>=1000] --limit 5
| startTime           | duration | path              | bytes  |
+---------------------+----------+-------------------+--------+
| 2024-01-15 10:23:41 | 1234567  | /tmp/data.txt     | 524288 |
...

jfr> show events/jdk.ExecutionSample | groupBy(thread/name)
| key              | count |
+------------------+-------+
| main             | 15234 |
| ForkJoinPool-1   | 8923  |
| GC Thread#0      | 4521  |
...

jfr> show events/jdk.FileRead | top(10, by=bytes)
| path                    | bytes    |
+-------------------------+----------+
| /data/large-file.bin    | 10485760 |
| /logs/app.log           | 5242880  |
...
```

## Common Use Cases

### Count Events

```bash
# How many execution samples?
show events/jdk.ExecutionSample | count()

# How many file reads over 1MB?
show events/jdk.FileRead[bytes>1048576] | count()
```

### Analyze Thread Activity

```bash
# Group execution samples by thread
show events/jdk.ExecutionSample | groupBy(thread/name)

# Find threads with deep call stacks
show events/jdk.ExecutionSample[len(stackTrace/frames)>20] --limit 10

# Top threads by sample count
show events/jdk.ExecutionSample | groupBy(thread/name, agg=count) | top(10, by=count)
```

### File I/O Analysis

```bash
# Sum total bytes read
show events/jdk.FileRead/bytes | sum()

# Statistics on read sizes
show events/jdk.FileRead/bytes | stats()

# Top 10 files by bytes read
show events/jdk.FileRead | groupBy(path, agg=sum, value=bytes) | top(10, by=sum)

# Files read from /tmp
show events/jdk.FileRead[path~"/tmp/.*"] --limit 20
```

### GC Analysis

```bash
# GC events after collection
show events/jdk.GCHeapSummary[when/when="After GC"]/heapSpace

# Large committed heap sizes
show events/jdk.GCHeapSummary/heapSpace[committedSize>1000000000]

# GC pause statistics
show events/jdk.GarbageCollection/sumOfPauses | stats()
```

### Metadata Exploration

```bash
# Browse a type structure
show metadata/jdk.types.StackTrace --tree --depth 2

# List all fields in a type
show metadata/jdk.types.Method/fields/name

# Find event types
metadata --search jdk.* --events-only
```

### Chunks and Constant Pools

```bash
# Chunk summary statistics
chunks --summary

# Browse constant pool symbols
cp jdk.types.Symbol

# Find specific symbols
show cp/jdk.types.Symbol[string~"java/lang/.*"]
```

## Non-Interactive Mode

Execute queries without entering the shell - perfect for scripts and CI pipelines:

```bash
# Count events
jfr-shell show recording.jfr "events/jdk.ExecutionSample | count()"

# Get JSON output
jfr-shell show recording.jfr "events/jdk.FileRead | top(10, by=bytes)" --format json

# Group analysis
jfr-shell show recording.jfr "events/jdk.ExecutionSample | groupBy(thread/name)"

# List event types
jfr-shell metadata recording.jfr --events-only

# Chunk information
jfr-shell chunks recording.jfr --summary
```

Exit codes: 0 for success, 1 for errors (sent to stderr).

## Variables

Store and reuse values in your analysis sessions with `${var}` substitution.

### Scalar Variables

```bash
jfr> set threshold = 1000
jfr> set limit = 10
jfr> show events/jdk.FileRead[bytes>=${threshold}] --limit ${limit}
```

### Lazy Query Variables

Store queries for on-demand evaluation with caching:

```bash
jfr> set fileReads = events/jdk.FileRead[bytes>=1000]
jfr> set stats = events/jdk.ExecutionSample | groupBy(sampledThread/javaName)

# Access results
jfr> echo "Found ${fileReads.size} large file reads"
jfr> echo "Top thread: ${stats[0].sampledThread/javaName}"
```

### Variable Scopes

```bash
# Session-scoped (default) - cleared when session closes
jfr> set myvar = "value"

# Global - persists across all sessions
jfr> set --global myvar = "value"
```

### Variable Commands

```bash
jfr> vars                    # List all variables
jfr> vars --session          # List session variables only
jfr> unset myvar             # Remove variable
jfr> invalidate data         # Clear lazy variable cache
jfr> echo "Value: ${myvar}"  # Print with substitution
```

See [Scripting Guide](../doc/jfr-shell-scripting.md#variables) for complete reference.

## Conditionals

Control script flow with if/elif/else/endif blocks:

```bash
jfr> set count = events/jdk.FileRead | count()
jfr> if ${count.count} > 0
...(1)>   echo "Found ${count.count} file reads"
...(1)>   if ${count.count} > 100
...(2)>     echo "That's a lot!"
...(2)>   endif
...(1)> else
...(1)>   echo "No file reads found"
...(1)> endif
```

### Condition Expressions

```bash
# Comparisons
if ${x} == 0
if ${value} != "error"
if ${bytes} > 1000

# Arithmetic
if ${a} + ${b} > 100

# Logical operators
if ${a} > 0 && ${b} > 0
if ${x} == 0 || ${y} == 0
if !${flag}

# Built-in functions
if exists(myvar)
if empty(results)
if !empty(data)
```

See [Scripting Guide](../doc/jfr-shell-scripting.md#conditionals) for complete reference.

## Scripting

JFR Shell supports powerful scripting capabilities for automating analysis workflows.

### Scripts Directory

Store reusable scripts in `~/.jfr-shell/scripts` and run them by name:

```bash
# List available scripts
jfr> script list
Available scripts in ~/.jfr-shell/scripts:

  basic-analysis           Comprehensive recording overview
  thread-profiling         Detailed thread analysis

Run with: script run <name> [args...]

# Run a script by name
jfr> script run basic-analysis /tmp/app.jfr
```

### Script Execution

Create reusable analysis scripts with variable substitution:

**analysis.jfrs:**
```bash
# Comprehensive file I/O analysis
# Arguments: recording, min_bytes, top_n

open $1
show events/jdk.FileRead[bytes>=$2] --limit $3
show events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top($3, by=count)
close
```

**Execute by path:**
```bash
jfr-shell script analysis.jfrs /tmp/app.jfr 1000 10
```

### Command Recording

Record your interactive commands for later replay:

```bash
jfr> record start analysis.jfrs
Recording started: analysis.jfrs

jfr> open /tmp/app.jfr
jfr> show events/jdk.ExecutionSample | count()
jfr> show events/jdk.FileRead | sum(bytes)

jfr> record stop
Recording stopped: analysis.jfrs

# Replay the recorded script
$ jfr-shell script analysis.jfrs
```

### Shebang Support

Make scripts directly executable:

**analyze.jfrs:**
```bash
#!/usr/bin/env -S jbang jfr-shell@btraceio script -
# Arguments: recording

open $1
show events/jdk.ExecutionSample | count()
close
```

```bash
chmod +x analyze.jfrs
./analyze.jfrs recording=/tmp/app.jfr
```

### Example Scripts

Check `jfr-shell/src/main/resources/examples/` for ready-to-use scripts:
- `basic-analysis.jfrs` - Recording overview, threads, I/O, GC
- `thread-profiling.jfrs` - Detailed thread analysis
- `gc-analysis.jfrs` - Comprehensive GC statistics

### Documentation

- [Scripting Guide](../doc/jfr-shell-scripting.md) - Complete scripting reference
- [Script Execution Tutorial](../doc/tutorials/script-execution-tutorial.md) - Learn by example
- [Command Recording Tutorial](../doc/tutorials/command-recording-tutorial.md) - Record and replay workflows

## JfrPath Query Language

JfrPath is a concise path-based query language for JFR data:

```
<root>/<segments>[filters]/<projection> | <aggregation>
```

### Roots
- `events/<type>` - Event data (e.g., `events/jdk.FileRead`)
- `metadata/<type>` - Type metadata (e.g., `metadata/java.lang.Thread`)
- `chunks` - Chunk information
- `cp/<type>` - Constant pool entries (e.g., `cp/jdk.types.Symbol`)

### Filters

Simple comparisons:
```
[field op value]
```
Operators: `=` `!=` `>` `>=` `<` `<=` `~` (regex)

Boolean expressions with functions:
```
[contains(path, "substring")]
[starts_with(path, "prefix")]
[matches(path, "regex")]
[exists(path)]
[empty(path)]
[between(value, min, max)]
[len(field)>10]
```

Logic operators: `and`, `or`, `not`, parentheses

List matching:
```
[any:stackTrace/frames[matches(method/name/string, ".*Foo.*")]]
[all:items[value>100]]
[none:items[error=true]]
```

### Aggregations

- `| count()` - Count rows
- `| sum([path])` - Sum numeric values
- `| stats([path])` - Min, max, avg, stddev
- `| groupBy(key[, agg=count|sum|avg|min|max, value=path])` - Group and aggregate
- `| top(n[, by=path, asc=false])` - Sort and take top N
- `| quantiles(0.5,0.9,0.99[,path=])` - Percentiles
- `| sketch([path])` - Stats + p50, p90, p99

### Field Projection with Expressions

- `| select(field1, field2, ...)` - Project specific fields
- `| select(field as alias)` - Rename fields
- `| select(expr as alias)` - Computed expressions

**Expressions support**:
- Arithmetic: `+` `-` `*` `/`
- String concatenation: `+`
- String templates: `"text ${expr} more text"`
- Functions: `if()`, `upper()`, `lower()`, `substring()`, `length()`, `coalesce()`

**Examples:**
```bash
# Simple field selection
show events/jdk.FileRead | select(path, bytes)

# Computed expressions
show events/jdk.FileRead | select(bytes / 1024 as kilobytes)
show events/jdk.FileRead | select(path + ' (' + bytes + ')' as description)

# String templates (cleaner syntax)
show events/jdk.FileRead | select("${path} (${bytes} bytes)" as description)
show events/jdk.FileRead | select("${path}: ${bytes / 1024} KB" as summary)

# Mixed fields and expressions
show events/jdk.FileRead | select(path, bytes / 1024 as kb, duration * 1000 as micros)

# Built-in functions
show events/jdk.FileRead | select(upper(path) as upperPath, length(path) as len)
show events/jdk.FileRead | select(if(bytes, 'large', 'small') as size)

# Functions in templates
show events/jdk.FileRead | select("File: ${upper(path)}" as info)
```

### Transforms

- `| len([path])` - String/list length
- `| uppercase([path])`, `| lowercase([path])`, `| trim([path])`
- `| abs([path])`, `| round([path])`, `| floor([path])`, `| ceil([path])`
- `| contains([path], "s")`, `| replace([path], "old", "new")`

### Event Decoration (Joining)

- `| decorateByTime(DecoratorType, fields=...)` - Join events by time overlap on same thread
- `| decorateByKey(DecoratorType, key=..., decoratorKey=..., fields=...)` - Join events by correlation key

Decorator fields accessed with `$decorator.fieldName` prefix.

**Examples:**
```bash
# Monitor contention: execution samples during lock waits
show events/jdk.ExecutionSample | decorateByTime(jdk.JavaMonitorWait, fields=monitorClass)

# Request tracing: correlate samples with request context
show events/jdk.ExecutionSample | decorateByKey(RequestStart,
                                                  key=sampledThread/javaThreadId,
                                                  decoratorKey=thread/javaThreadId,
                                                  fields=requestId,endpoint)

# Group by decorator field
show events/jdk.ExecutionSample | decorateByTime(jdk.GCPhase, fields=name)
  | groupBy($decorator.name)
```

See [doc/jfrpath.md](../doc/jfrpath.md) for complete reference.

## Available Commands

### Session Management
- `open <path> [--alias NAME]` - Open a recording
- `sessions` - List all sessions
- `use <id|alias>` - Switch current session
- `info [id|alias]` - Show session information
- `close [id|alias|--all]` - Close session(s)

### Querying
- `show <expr> [options]` - Execute JfrPath query
- `metadata [options]` - List/inspect metadata types
- `chunks [options]` - List chunks
- `chunk <index> show` - Show chunk details
- `cp [<type>] [options]` - Browse constant pools

### Variables
- `set [--global] <name> = <value>` - Set variable (scalar or lazy query)
- `vars [--global|--session]` - List variables
- `unset <name>` - Remove a variable
- `echo <text>` - Print with `${var}` substitution
- `invalidate <name>` - Clear cached lazy variable

### Conditionals
- `if <condition>` - Start conditional block
- `elif <condition>` - Else-if branch
- `else` - Else branch
- `endif` - End conditional block

### Scripting
- `script list` - List available scripts in `~/.jfr-shell/scripts`
- `script run <name> [args...]` - Run script by name
- `script <path> [args...]` - Run script by full path
- `record start [path]` - Start recording commands
- `record stop` - Stop recording
- `record status` - Show recording status

### Help
- `help [<command>]` - Show help (use `help show` for JfrPath syntax)
- `exit` / `quit` - Exit shell

## Requirements

### Using Standalone Distribution
- No external dependencies (JRE is bundled)

### Building from Source
- Java 25+ (for compilation)
- Gradle 8+ (wrapper included)

## Development

```bash
# Run tests
./gradlew :jfr-shell:test

# Build fat jar
./gradlew :jfr-shell:shadowJar

# Run with test recording
java -jar jfr-shell/build/libs/jfr-shell-*.jar -f parser/src/test/resources/test-jfr.jfr
```

## Documentation

- [jfr_shell_usage.md](../doc/jfr_shell_usage.md) - Complete usage guide
- [jfrpath.md](../doc/jfrpath.md) - JfrPath grammar and operator reference

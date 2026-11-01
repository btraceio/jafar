# JFR Shell Scripting Guide

JFR Shell supports powerful scripting capabilities that allow you to automate JFR analysis workflows, create reusable analysis templates, and share analysis procedures with your team.

## Table of Contents

- [Overview](#overview)
- [In jafar-shell](#in-jafar-shell)
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

JFR Shell scripts (`.jfrs` extension) are plain text files containing sequences of shell commands and JfrPath queries. The same scripting — variables, maps, `merge()`, lazy query variables, `if`/`elif`/`else`/`endif`, `echo`, `invalidate`, `script` — also works in the unified `jafar-shell`, against heap dumps, pprof and OTLP profiles as well as recordings; see [In jafar-shell](#in-jafar-shell) for the differences. Scripts support:

- **Positional parameters** - Parameterize scripts with bash-style `$1`, `$2`, `$@` syntax
- **Comments** - Document your scripts with `#` comments
- **All shell commands** - Use any command available in interactive mode
- **Error handling** - Continue-on-error mode for robust scripts
- **Stdin support** - Read scripts from stdin for piping and shebangs

## In jafar-shell

`jafar-shell` opens JFR recordings, heap dumps (`.hprof`), pprof (`.pprof`, `.pb.gz`) and OTLP
(`.otlp`) profiles side by side, and the scripting in this guide works the same way there — the
parsing, scoping and condition rules are one implementation in `shell-core`, shared by both shells.
A query-valued variable is written in the language of the session that is current when it is set
(JfrPath, HdumpPath, or the pprof/OTLP samples grammar), run by that session's query evaluator the
same way `show` runs it, and stays bound to that session. That is what makes cross-format scripts
possible:

```bash
# cross.jfrs — $1 recording, $2 heap dump, $3 minimum object size (default 1000)
set --global min = ${3:-1000}
open $1
set --global samples = events/jdk.ExecutionSample | count()
open $2
set big = objects[shallow > ${min}] | count
set top = classes | top(3, by=instanceCount)
echo heap: ${big} objects over ${min} bytes; top class ${top[0].name}
echo jfr (session 1, read from session 2): ${samples} samples
if ${samples} > 100 && ${big} > 0
echo VERDICT both busy
else
echo VERDICT quiet
endif
```

```
jafar> script cross.jfrs app.jfr app.hprof 4096
...
heap: 26 objects over 4096 bytes; top class byte[]
jfr (session 1, read from session 2): 551 samples
VERDICT both busy

Script executed successfully: 13 commands
```

`samples` is `--global` so that it is still visible after `open $2` makes the heap dump current;
it keeps evaluating against the recording. Once its session is closed, reading the variable fails
with `Session #1 has been closed` rather than returning rows from a file that is no longer open, and
`vars` shows it as `(closed)`.

A script may run another (`script` inside a script) up to 16 deep. In both shells each script has
its own `if` blocks: if it ends inside one, the blocks it left open are closed and reported (see
[Blocks Left Open by a Script](#blocks-left-open-by-a-script)), so the commands you type next are
not skipped; and a stray `endif` in a script is an error in that script rather than closing a block
of the caller's. A script that leaves blocks open, or nests deeper than 16, has
failed: the `script` line fails, and `jafar-shell script` exits 1.

The same script runs without a prompt — from a file, or from stdin with `-`:

```bash
jafar-shell script cross.jfrs app.jfr app.hprof 4096
jafar-shell script - app.jfr app.hprof < cross.jfrs
```

Results go to stdout and errors to stderr. As in `jfr-shell script`, the banner, the `Opening
...`/`Session 1 opened` lines and the `Set x = ...` confirmations are left out, and the interactive
history is not touched. `JFR_SHELL_VERBOSE=1` (or `-Djfr.shell.verbose=true`) brings the
confirmations back, in either shell's script mode. The exit status is 1 if any line failed; `--continue-on-error` runs the
remaining lines after a failure.

**A JFR-only script behaves differently here.** `jafar-shell script f.jfrs ...` used to be handed
to jfr-shell's command line; it now runs in the unified shell. The data a query returns is the
same, but running the shipped examples both ways shows what changes:

- Tables are drawn without the box and end with `(N rows)`; `info` prints its own layout. A
  column holding a multi-field value, such as `eventThread`, is printed as
  `{virtual=false, javaThreadId=0, osName=VM Thread, ...}`, where jfr-shell's table leaves the
  column out.
- Constant-pool strings print as their value — `G1New`, `G1 Evacuation Pause`,
  `java/lang/Object` — in tables, `--format csv` and `--format json` alike. jfr-shell prints them
  that way only in tables; its CSV and JSON keep the parser's wrapper, `{name=G1New}` and
  `"name": {"name": "G1New"}`.
- The other rows of the table below: `show ${var} | ...` and `true`/`false`.

To keep jfr-shell's behaviour, run the script with `jfr-shell script` (the `jfr-shell` jbang alias,
or `java -cp jafar-shell-<version>-all.jar io.jafar.shell.Main script ...`). `-b/--backend` on
`jafar-shell script` picks the backend for recordings but keeps the script in this shell. Scripts
with the shebang line `#!/usr/bin/env -S jbang jfr-shell@btraceio script -` are not affected: the
`jfr-shell` jbang alias launches jfr-shell's own entry point (`io.jafar.shell.Main`), not this one.

Where the two shells differ:

| | `jfr-shell` | `jafar-shell` |
|---|---|---|
| Query variables | JfrPath; `count()`/`sum()` results are stored as scalars at once, `toMap()` as a map | any session's language; always lazy. `${n}` still reads a count's value; a query ending in `toMap()` is read as its map (`${v.size}` counts entries, `${v.key}` reads one), decided from the query, so a one-row result of any other query, such as `top(1)`, still has `.size` 1; `merge()` takes a variable whose rows are one map |
| `show ${var}` | `show ${var} \| <pipeline>` re-pipes a JfrPath variable's rows | `show ${var}` alone prints the variable's rows; piping a variable is not supported |
| Running scripts | `script <path>`, `script run <name>`, `script list`, `jfr-shell script <file\|-> [args]` | `script <path> [args]` at the prompt, and `jafar-shell script <file\|-> [args] [--continue-on-error] [-b <backend>]` on the command line — both in this shell, so a script can open every format. On the command line it is quiet like jfr-shell's, errors go to stderr, and the exit status is 1 on failure; `-b` picks the backend for recordings, as in jfr-shell |
| `$1` in an interactive `script` | the current session's recording is prepended as `$1` | arguments are exactly what you typed |
| JFR commands | `metadata`, `chunks`, `chunk`, `constants`/`cp`, `backend` | the same commands (jfr-shell's implementations) on a recording; `metadata/...`, `chunks \| ...` and `show metadata` remain queries |
| [`record`](#recording-commands) | saves to `~/.jfr-shell/recordings/`; records each typed line except `record`, `help`, `exit`/`quit` and `script ...` (nor the lines the script runs, so a replay skips it) | the same command, any format; saves to `~/.jafar-shell/recordings/`, header `# Jafar Shell Recording`; records the same lines but keeps `script ...`, so a replay runs that script again |
| A failing line | stops the script (unless `--continue-on-error`), is listed, and makes `script` exit 1 | the same, at the prompt and on the command line |
| Booleans and quotes | `set b = true` is not a boolean literal; strings use `"..."` | `true`/`false` are booleans; `'...'` quotes too |

The JFR commands `metadata`, `chunks`, `chunk`, `constants`/`cp` and `backend` work in both shells
(in `jafar-shell` they need the current session to be a recording), so the example scripts below run
in either. `metadata/...`, `chunks[...]`, and a line with a pipeline or `--limit`
(`chunks --format json | count()`, `chunks --limit 1`) remain queries; `cp <type> | crossref()` is the
command.
`session <id>` is an alias for `use <id>` in both shells.

`llm.*` settings follow the same scoping as other variables in both shells: set while a session is
open, they belong to that session (and go when it closes); `set --global llm.backend = ollama`
keeps one for the whole shell.

## Script File Format

Scripts are line-based text files with a simple syntax:

```bash
# Comments start with # and are ignored
# Blank lines are also ignored

# Commands are executed line by line
open /path/to/recording.jfr

# JfrPath queries
events/jdk.ExecutionSample | count()

# Multi-word commands
events/jdk.FileRead[bytesRead>=1000] --limit 10

# Close the session
close
```

### File Extension

By convention, JFR Shell scripts use the `.jfrs` extension (JFR Shell Script).

## Positional Parameters

Scripts support bash-style positional parameter substitution using `$1`, `$2`, `$@` syntax with optional parameter support. Positional parameters make scripts reusable across different recordings, thresholds, and parameters.

### Parameter Syntax

```bash
# Required parameters (error if not provided)
open $1

# Optional parameters (empty string if not provided)
open ${1}
set limit = ${2}      # Empty string if $2 not provided

# Optional with default value
set limit = ${2:-100}
set format = ${3:-table}

# Required with custom error message
open ${1:?recording file required}
set threshold = ${2:?threshold value required}

# All parameters space-separated
events/$1 | count()
# or using all args
echo "Processing: $@"
```

**Parameter forms:**
- `$N` - Required parameter (error if missing)
- `${N}` - Optional parameter (empty if missing)
- `${N:-default}` - Optional with default value
- `${N:?error message}` - Required with custom error message
- `$@` - All arguments space-separated

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

If a script references a **required** parameter (`$N` form) that wasn't provided, execution fails with a clear error message:

```
Error on line 3: Positional parameter $1 out of bounds. Script has 0 argument(s).
  Command: open $1
```

Optional parameters (`${N}` form) return empty string when not provided, while `${N:-default}` returns the default value.

### Example with Parameters

**script.jfrs:**
```bash
# Configurable analysis script
# Usage: script.jfrs <recording> [min_bytes] [top_n]

# Required parameter with error message
open ${1:?recording file path required}

# Optional parameters with defaults
set min_bytes = ${2:-1024}
set top_n = ${3:-20}

# Show large file reads
events/jdk.FileRead[bytesRead>=${min_bytes}] --limit ${top_n}

# Thread analysis
events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(${top_n}, by=count)

close
```

**Execution:**
```bash
# With all arguments
jfr-shell script script.jfrs /tmp/app.jfr 1024 20

# With defaults (uses min_bytes=1024, top_n=20)
jfr-shell script script.jfrs /tmp/app.jfr

# Partial defaults (uses top_n=20)
jfr-shell script script.jfrs /tmp/app.jfr 2048
```

## Variables

Both shells support variables for storing and reusing values in your analysis. Variables can hold scalar values (strings, numbers), maps, or lazily-evaluated query results.

### Variable Types

**Scalar Variables** store immediate values:
```bash
set threshold = 1000
set filename = "/tmp/output.txt"
set limit = 20
```

**Lazy Query Variables** store queries that are evaluated on-demand — JfrPath here, or in `jafar-shell` whatever language the current session speaks (`set big = objects[shallow > 1000]` on a heap dump):
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

Maps use JSON-like syntax and support strings, numbers, booleans, null, and nested maps. See [Map Variables Tutorial](../parser/MapVariables.md) for complete reference.

### Variable Scopes

Variables can be session-scoped (default) or global. With no session open, `set` writes the global
scope. A name is looked up in the current session first, then globally; `unset` removes from the
scope `set` would write and, if the name is not there, from the other one.

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

# Copy variables
set backup = threshold          # Copy value from threshold
set copy = $threshold            # Alternative syntax with $

# Lazy query (assigned when RHS contains JfrPath expression)
set reads = events/jdk.FileRead[bytesRead>=1000]
set stats = events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(10, by=count)

# Copy map variables
set originalConfig = {"threshold": 1000, "enabled": true}
set configBackup = originalConfig    # Creates a copy
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
events/jdk.FileRead[bytesRead>=${threshold}] --limit ${limit}

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

# Size property: row count for a query, entry count for a map or a toMap() query
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
set fileReads = events/jdk.FileRead[bytesRead>=${threshold}]
set readCount = events/jdk.FileRead[bytesRead>=${threshold}] | count()

echo "=== File Read Analysis ==="
echo "Threshold: ${threshold} bytes"
echo "Events found: ${readCount.count}"

# Show results if any found
events/jdk.FileRead[bytesRead>=${threshold}] --limit ${limit}

# Thread analysis with caching
set threadStats = events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(5, by=count)
echo ""
echo "=== Top 5 Threads by Samples ==="
echo "Thread count: ${threadStats.size}"
events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(5, by=count)

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

Both symbols and keywords are supported:

```bash
# Symbols
if ${a} > 0 && ${b} > 0
if ${a} == 0 || ${b} == 0
if !${flag}
if (${a} > 0 && ${b} > 0) || ${c} == 1

# Keywords (equivalent to symbols above)
if ${a} > 0 and ${b} > 0
if ${a} == 0 or ${b} == 0
if not ${flag}
if (${a} > 0 and ${b} > 0) or ${c} == 1
```

#### String Operations

```bash
# Substring check with contains operator
if ${path} contains "/tmp/"
if ${name} contains "Error"
if ${filename} contains ".jfr" and ${size} > 1000
```

#### Built-in Functions

```bash
# Check if a variable exists
if exists(myvar)

# Check if a variable is empty (doesn't exist, null, empty string, map with no entries,
# or query result with no rows)
if empty(results)

# References: the same shapes ${...} accepts
if exists(config.db.host)       # the field resolves to a non-null value
if empty(config.db.user)        # missing, null, "", {} or [] all count as empty
if !empty(results.thread.name)  # query results: field of the first row
if exists(results[2].thread)    # field of row 2 (0-based); a missing row does not exist

# LLM settings are names, including hyphenated ones
if empty(llm.base-url)
  echo "llm.base-url is not set"
endif

# Negation
if !exists(optionalVar)
if !empty(data)
```

An argument to `exists()` and `empty()` resolves exactly as the same reference does in `${...}`:

- `name.field.path` reads a map field or the first row of a query result; `name[idx].field` reads
  row `idx`; `name[idx]` alone is the first value of that row, as `${name[idx]}` is.
- `name.size` is the entry or row count, so it exists and is never empty.
- A path on a plain value is ignored, as in `${...}`: after `set n = events/jdk.ExecutionSample |
  count`, `${n.count}` is the count and `empty(n.count)` tests that count.
- An `llm.*` setting name (case-insensitive, hyphens allowed) checks that setting and is never split
  into a variable plus a field; an unset setting does not exist.
- A malformed reference such as `config..db`, `results[x]` or `a-b` is an error rather than silently
  empty.

### Interactive Mode

In interactive mode, the prompt changes to show nesting depth (in both shells), and `exit` warns
about blocks left open:

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

  events/jdk.FileRead | top(10, by=bytesRead)
else
  echo "No file read events in this recording"
endif

close
```

#### Conditional Analysis

```bash
#!/usr/bin/env -S jbang jfr-shell@btraceio script -
# Usage: script.jfrs <recording> [analysis_type]

open ${1:?recording file required}
set analysis = ${2:-cpu}

if ${analysis} == "cpu"
  echo "=== CPU Analysis ==="
  events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(10, by=count)
elif ${analysis} == "io" or ${analysis} == "file"
  echo "=== I/O Analysis ==="
  events/jdk.FileRead | sum(bytesRead)
  events/jdk.FileWrite | sum(bytesWritten)
elif ${analysis} == "gc"
  echo "=== GC Analysis ==="
  events/jdk.GarbageCollection | stats(duration)
else
  echo "Unknown analysis type: ${analysis}"
  echo "Supported: cpu, io, file, gc"
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
set results = events/jdk.FileRead[bytesRead>=${threshold}]
if !empty(results)
  echo "Found ${results.size} events exceeding threshold"
  events/jdk.FileRead[bytesRead>=${threshold}] --limit 20
else
  echo "No events exceed the threshold of ${threshold} bytes"
endif
```

An `elif` condition is evaluated only when its branch could still be taken: after a branch has
run, or inside a block that is being skipped, it is not evaluated at all — so it cannot report an
error or run a lazy query for a branch that will not execute.

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

#### Conditions That Cannot Be Evaluated

A condition that fails to evaluate — a parse error, an undefined variable, a wrong number of
function arguments, division by zero, a missing condition — is an error, not a `false`. **No branch
of that if-block runs**: not the failing branch, and not any later `elif` or `else`, because taking
one would mean guessing what the condition would have said. The block is still open, and its
`endif` closes it. To branch on whether a variable is set, use `exists(name)` rather than letting
`${name}` fail.

In a script this is a command error like any other, in both shells:

- by default the script stops at that line and exits with a non-zero status;
- with `--continue-on-error` the script resumes after the block's `endif` and the error is counted
  in the final report (the script still exits non-zero).

```
if empty(cfg..db)
  echo EMPTY
else
  echo SET
endif
```

```
Script completed with errors:
Line 1: Condition evaluation failed: Invalid variable reference: cfg..db; skipping every branch of this if-block
  Command: if empty(cfg..db)
```

Neither `EMPTY` nor `SET` is printed. Interactively the error is printed, the prompt shows the
block as open (`...(1)>`), its commands are skipped, and `endif` closes it. The jfr-shell TUI shows
the same in the title of the command input: `Command ...(1, skipping until endif) >`.

An `elif` condition is evaluated only when every earlier branch was false, and a condition inside
a block that is already being skipped is not evaluated at all, so neither can fail there. A
*missing* condition (`if` or `elif` alone) is a syntax error and is reported either way.

#### Blocks Left Open by a Script

Each script runs with its own `if` blocks, so whether it ends normally or stops early at an error,
the blocks it opened are closed when it returns: running a script from the interactive shell never
leaves the shell inside one of the script's blocks, and a block you opened before running it stays
open. A script that runs to its end with an `if` still open is reported, and exits non-zero:
`jfr-shell` adds an error `N if-block(s) not closed with endif by end of script` against the last
line; `jafar-shell` prints `<script> left N if-block(s) open; closed them` (it also prints this
after a script that stopped early at an error inside a block, alongside that error).

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

These are command errors like a failed condition: a script stops at them (or, with
`--continue-on-error`, counts them) and exits non-zero.

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

In `jafar-shell` this is the form there is (`script list` and `script run` are `jfr-shell`'s):

```bash
jafar> script /path/to/cross.jfrs app.jfr app.hprof
```

### From Command Line

Execute scripts directly from the command line:

```bash
# Run by path
jfr-shell script /path/to/script.jfrs arg1 arg2 arg3

# In jafar-shell: same arguments and --continue-on-error; the script may open any format
jafar-shell script /path/to/cross.jfrs app.jfr app.hprof

# From stdin
jfr-shell script - /tmp/app.jfr <<'EOF'
open $1
events/jdk.ExecutionSample | count()
close
EOF

# Redirect from file
jfr-shell script - /tmp/app.jfr < analysis.jfrs
```

`jafar-shell script` takes the same arguments and runs the script in the unified shell, where it
can open heap dumps and profiles too — see [In jafar-shell](#in-jafar-shell).

### Error Handling

By default, script execution stops on the first error (fail-fast mode). A line fails when it is not a command, when it throws, or when the shell reports an error for it — a query that does not parse, `No session open`, a file that is not there, or an `ask`/`as-query`/`explain` that ran nothing (no backend, no query in the reply, nothing to explain). Use `--continue-on-error` to execute all commands:

```bash
# Stop on first error (default)
jfr-shell script analysis.jfrs /tmp/app.jfr

# Continue on errors
jfr-shell script analysis.jfrs /tmp/app.jfr --continue-on-error
```

When errors occur, you'll see a detailed report:

```
Error on line 15: No session open. Use 'open <file>' first.
  Command: events/jdk.ExecutionSample

Script completed with errors:
  Line 15: No session open. Use 'open <file>' first.
  Line 23: Unknown event type: jdk.InvalidEvent

Executed 21/25 commands successfully.
```

## Recording Commands

JFR Shell can record your interactive commands into an executable script file (`jafar-shell` has the same
`record` command, for every format, saving to `~/.jafar-shell/recordings/` and replayed with
`jafar-shell script <file>`). This is perfect for:

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

Recording also auto-saves when you exit the shell. `jafar-shell` has the same three commands, saves
to `~/.jafar-shell/recordings/` by default, and records lines typed at its prompt whatever the
session type — see [In jafar-shell](#in-jafar-shell)
for which lines each shell leaves out.

### Recorded Script Format

Recorded scripts include timestamp comments for context but are immediately executable:

```bash
# JFR Shell Recording
# Started: 2025-12-26T14:30:00Z
# Session: my-analysis.jfrs

# [14:30:15]
open /path/to/recording.jfr

# [14:30:20]
events/jdk.ExecutionSample --limit 10

# [14:31:45]
events/jdk.ExecutionSample | groupBy(sampledThread/javaName)

# Recording stopped: 2025-12-26T14:32:10Z
```

A `set` or `let` of `llm.api-key` is recorded without its value, as a comment —
`# set --global llm.api-key = <redacted: not recorded>` — so the file holds no key and a replay uses
whatever key the person replaying it has configured. The line is kept out of the shell's history
too (`~/.jfr-shell/history`, `~/.jafar-shell/history`, also used by `--tui`), so the up arrow does
not bring it back; a key line saved there by an earlier version is removed the next time the shell
starts. The shells do not print it back either: `set`'s confirmation, `vars` and `vars --info` show
`<hidden, ends in "TAIL">` (the last four characters, for a value of 16 or more) or `<hidden>`, and
the TUI shows `set llm.api-key = <hidden>` in its tab names and echo. What you type is still visible
while you type it, so for a key you use often prefer `~/.config/jafar/llm.properties` or the
provider's environment variable.

You can execute this script directly:

```bash
jfr-shell script /tmp/my-analysis.jfrs
```

### Converting to Parameterized Scripts

Recorded scripts often contain hardcoded paths. Convert them to parameterized scripts by replacing paths with positional parameters:

**Original recorded script:**
```bash
open /Users/john/recordings/prod-app-20251226.jfr
events/jdk.FileRead[bytesRead>=1000] --limit 10
```

**Parameterized version:**
```bash
# Arguments: $1=recording, $2=threshold, $3=limit
open $1
events/jdk.FileRead[bytesRead>=$2] --limit $3
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

Run as `./analyze.jfrs /tmp/app.jfr`, the kernel executes `jbang jfr-shell@btraceio script -
./analyze.jfrs /tmp/app.jfr`. When the path is `-` and the first argument is a file whose first line
starts with `#!`, `script` runs that file and passes the arguments after it as `$1`, `$2`, ... —
so `$1` is `/tmp/app.jfr`, and stdin is not read. `jafar-shell script` does the same, so
`#!/usr/bin/env -S jbang jafar-shell@btraceio script -` gives a script that can open any format.

### Making Scripts Executable

```bash
# Add shebang to script
cat > analyze.jfrs <<'EOF'
#!/usr/bin/env -S jbang jfr-shell@btraceio script -
# Arguments: $1=recording

open $1
events/jdk.ExecutionSample | count()
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
events/jdk.FileRead[bytesRead>=$2] --limit $3
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
events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(10, by=count)
events/jdk.FileRead | sum(bytesRead)
events/jdk.FileWrite | sum(bytesWritten)
events/jdk.GarbageCollection | stats(duration)
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
events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top($2, by=count)
events/jdk.ThreadAllocationStatistics | groupBy(thread/javaName, agg=sum, value=allocated) | top($2, by=value)
events/jdk.JavaMonitorEnter | groupBy(monitorClass/name) | top($2, by=count)
events/jdk.ThreadSleep | groupBy(thread/javaName, agg=sum, value=time) | top($2, by=value)
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
events/jdk.GarbageCollection | stats(duration)
events/jdk.GarbageCollection | groupBy(name) | top(10, by=count)
events/jdk.GCHeapSummary | stats(heapUsed)
events/jdk.ObjectAllocationInNewTLAB | groupBy(objectClass/name, agg=sum, value=allocationSize) | top(20, by=value)
events/jdk.ObjectAllocationInNewTLAB | sum(allocationSize)
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
events/jdk.FileRead[bytesRead>=$2] --limit $3

# Less clear (but sometimes acceptable for simple scripts)
# Arguments: $1=file, $2=threshold, $3=limit
open $1
events/jdk.FileRead[bytesRead>=$2] --limit $3
```

### 3. Group Related Operations

Use comments to section your script:

```bash
# Session setup
open $1
info

# CPU Analysis
events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(10, by=count)
events/jdk.ExecutionSample | groupBy(stackTrace) | top(10, by=count)

# Memory Analysis
events/jdk.ObjectAllocationInNewTLAB | groupBy(objectClass/name, agg=sum, value=allocationSize) | top(20, by=value)

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
events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(5, by=count)

# Analyze current
open $2 --alias current
use current
events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(5, by=count)

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
events/jdk.ExecutionSample | count()

# Optional analyses (may not have these events)
events/jdk.CustomEvent | count()
events/jdk.ExperimentalFeature | count()

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

events/jdk.GarbageCollection | stats(duration)
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
- [JFR Shell Tutorial](Tutorial.md)
- [Example Scripts](../jfr-shell/src/main/resources/examples/)
- [JfrPath Query Language](JFRPath.md)

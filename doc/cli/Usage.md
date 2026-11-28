# JFR Shell - Interactive JFR CLI

An interactive CLI for exploring Java Flight Recorder (JFR) files: browse metadata, list events, inspect chunks and constant pools, and query values with a concise JfrPath expression.

## Quick Start

### Run the shell

```bash
# Build the fat jar first
./gradlew :jfr-shell:shadowJar

# Interactive mode
java -jar jfr-shell/build/libs/jfr-shell-*.jar

# Or pass a file immediately (positional argument or -f flag)
java -jar jfr-shell/build/libs/jfr-shell-*.jar /path/to/recording.jfr
java -jar jfr-shell/build/libs/jfr-shell-*.jar -f /path/to/recording.jfr

# Or use the standalone distribution (includes bundled JRE)
./gradlew :jfr-shell:jlinkDist
jfr-shell/build/jlink/bin/jfr-shell
```

On startup:
```
Type 'help' for commands, 'exit' to quit
jfr> _
```

### Open a recording and browse

```
jfr> open /path/to/recording.jfr
jfr> info
jfr> metadata --summary
jfr> show metadata/java.lang.Thread
jfr> show metadata/java.lang.Thread --format json
jfr> show metadata/jdk.types.StackTrace --tree --depth 2
jfr> show metadata/jdk.types.Method/fields/name --tree

# Events example (values projection)
jfr> events/jdk.FileRead/bytesRead --limit 5
```

## Available Commands

### Session Management
- `open <path> [--alias NAME]`: Open a recording file. A path may contain spaces; quote it
  (`open "/data/my recordings/app.jfr"`) when it also contains ` --alias`.
- `connect`: List local JVMs (`[jfr]` marks one with a discoverable disk-backed JFR event repository).
- `connect <pid> [--alias NAME] [--repository <dir>]`: Attach a live session to a running JVM's JFR event repository (local machine only, no attach API). While a live session is current, file-bound commands (`show`, `metadata`, `chunks`, `cp`, `backend`) are not available — use `tail`.
- `tail [filter] [--limit N] [--format table|json]`: Stream events from the live session until Ctrl-C, the target JVM exits, or the limit is reached. The filter is a JfrPath expression with per-event operators only (`filter`, `select`, transforms) — aggregations (`count()`, `groupBy()`, …) need the full row set and are rejected.
- `sessions`: List all sessions; marks the current one.
- `use <id|alias>` (or `session`): Switch current session.
- `close [<id|alias>|--all]`: Close a session or all.
- `info [<id|alias>]`: Show session information.

Live-streaming caveats: the target needs a **disk-backed** recording (e.g.
`-XX:StartFlightRecording=disk=true`); in-memory recordings only reach the on-disk repository at
rotation/dump. Slow consumers never slow the target, but events can be lost on the target side
when its repository wraps (old chunks are deleted); the drop count is visible in `info` and the
tail status line. See [Live event streaming](#live-event-streaming) for a walkthrough.

### Querying and Browsing
- `show <expr> [--limit N] [--format table|json] [--tree] [--depth N] [--list-match any|all|none]`: Evaluate a JfrPath expression. For list fields, `--list-match` sets default matching mode.
- `metadata [--search <glob>|--regex <pat>] [--refresh] [--events-only|--non-events-only] [--primitives] [--summary]`: List types.
- `metadata class <name> [--tree|--json] [--fields] [--annotations] [--depth N]`: Inspect a class.
- `chunks [--summary] [--range N-M]`: List chunk information.
- `chunk <index> show`: Show specific chunk details.
- `constants [<type>] [--summary] [--range N-M] [--limit N]`: Browse constant pool entries.
- `constants/<type> | crossref()`: Crosscheck CP entries against event references.
- `events/<type>[filter] [--limit N]`: Query events (shorthand for `show events`, both forms work).

`metadata`, `metadata class`, `chunks`, `chunk`, `constants`/`cp` and `backend` work in `jafar-shell`
too while the current session is a JFR recording; in a heap dump, pprof or OTLP session they say so
and do nothing.

### Variables
The variable, conditional and `script <path>` commands below exist in `jafar-shell` too, for every
format it opens; see [Scripting in jafar-shell](Scripting.md#in-jafar-shell) for the differences.

- `set [--global] <name> = <value>`: Set a variable (scalar, map, or lazy query).
- `let [--global] <name> = <value>`: Alias for `set`.
- `vars [--global|--session|--all]` / `vars --info <name>`: List variables or show detailed info.
- `unset <name> [--global]`: Remove a variable.
- `echo <text>`: Print text with `${var}` substitution (supports nested fields: `${var.field.subfield}`).
- `invalidate <name>`: Clear cached lazy variable result.

### Conditionals
- `if <condition>`: Start conditional block.
- `elif <condition>`: Else-if branch.
- `else`: Else branch.
- `endif`: End conditional block.

### Scripting
- `script list`: List available scripts in `~/.jfr-shell/scripts`.
- `script run <name> [args...]`: Run script by name from scripts directory.
- `script <path> [args...]`: Run script by full path.
- `record start [path]`: Start recording commands to a script file.
- `record stop`: Stop recording and save the script.
- `record status`: Show current recording status.

### Help and Exit
- `help [<command>]`: Show contextual help.
- `exit` / `quit`: Exit shell.

## Command Line Options

### Interactive Mode (Default)

```bash
Usage: jfr-shell [-hqV] [-f=<jfrFile>]
JFR analysis tool with interactive and non-interactive modes
  -f, --file=<jfrFile>   JFR file to open immediately (interactive mode)
  -h, --help             Show this help message and exit
  -q, --quiet            Suppress banner (interactive mode)
  -V, --version          Print version information and exit
```

### Non-Interactive Mode

Execute queries without entering the shell, suitable for scripting and CI/automation:

```bash
# List local JVMs and their discoverable JFR event repositories
jfr-shell ps

# Stream live events from a running JVM until Ctrl-C (exit 0 on interrupt)
jfr-shell tail <pid> ["events/<type>[filter] | filter(...)"] [--repository <dir>] [--limit N] [--format table|json]

# Show command - Execute JfrPath queries
jfr-shell show <jfr-file> "<expression>" [options]
  --limit, -l <N>           Limit number of results
  --format, -f <format>     Output format: table (default), json
  --list-match <mode>       List matching mode: any, all, none
  --json                    One JSON object on stdout and nothing else (see "One JSON result")

# Metadata command - List event types and metadata
jfr-shell metadata <jfr-file> [options]
  --search, -s <pattern>    Search pattern
  --regex, -r               Use regex for search
  --events-only, -e         Show only event types
  --summary                 Show summary only

# Chunks command - List chunk information
jfr-shell chunks <jfr-file> [options]
  --summary                 Show summary only
  --format, -f <format>     Output format: table (default), json

# Constants command - List constant pool entries
jfr-shell constants <jfr-file> [options]
  --type, -t <name>         Constant pool type name
  --summary                 Show summary only
  --format, -f <format>     Output format: table (default), json

# Compare command - Compare a candidate recording, pprof or OTLP profile against a baseline
jfr-shell compare <baseline> <candidate> [options]
  --json                    Print the full result as JSON
  --event-type <type>       Execution sample event type of a JFR side (default: detected)
  --sample-type <t[/unit]>  Sample type of a pprof or OTLP side (default: first CPU, else first)
  --baseline-sample-type, --candidate-sample-type <t[/unit]>
                            The same for one side; overrides --sample-type
  --allow-mixed-samplers    Compare CPU against wall clock instead of refusing (nothing judged)
  --list-sample-types       List both files' sample types and exit
  --min-delta-pct <pct>     Noise floor in percentage points for frame changes (default: 1.0)
  --limit, -l <N>           Maximum number of changed frames per list (default: 25)
```

**Non-Interactive Examples:**
```bash
# Count execution samples
jfr-shell show recording.jfr "events/jdk.ExecutionSample | count()"

# Top 10 files by bytes, JSON output
jfr-shell show recording.jfr "events/jdk.FileRead | top(10, by=bytesRead)" --format json

# Group execution samples by thread
jfr-shell show recording.jfr "events/jdk.ExecutionSample | groupBy(sampledThread/javaName)"

# List event types only
jfr-shell metadata recording.jfr --events-only

# Chunk summary
jfr-shell chunks recording.jfr --summary

# Constant pool symbols
jfr-shell constants recording.jfr --type jdk.types.Symbol

# Crosscheck StackTrace CP entries against event references
jfr-shell show recording.jfr "constants/jdk.types.StackTrace | crossref()"
```

#### Comparing two recordings

`jfr-shell compare` runs the comparison behind the MCP tool `jfr_compare`, with no server. Event
counts are compared as rates per second of each recording's own observed span. Frames are compared
as shares of each recording's execution samples, in two lists:

- **Self time** (`frames`): the share of samples whose leaf frame is the method.
- **Inclusive time** (`inclusiveFrames`): the share of samples whose stack contains the method,
  counted once per sample even when it recurses. This list names a caller whose own code did not
  change but whose callees got slower, which self time cannot show.

Frame names are `<class>.<method>` with the class name dotted (`java.lang.String.indexOf`, though
JFR stores `java/lang/String`), or the bare symbol for a native frame. They leave out what the JVM
assigns per run, a hidden class's address (`/0x…` or `.0x…`, as in
`LambdaForm$MH/0x00007f2e141a1000`) and a lambda class's number (`$$Lambda$14`), and what javac
assigns per compilation: the number in a synthetic accessor (`access$300`, for code compiled for
Java 8) and in a lambda body (`lambda$main$0`). Two recordings come from two JVMs, often running
two builds, so with those parts the same frame would have a different name on each side. Frames
that differ only in them are counted as one.

JFR cuts every stack at its stack depth, 64 frames unless the recorded JVM ran with
`-XX:FlightRecorderOptions:stackdepth=<n>`. On JDK 21 that option was measured to cap at 2048 frames.
A cut stack lacks its outermost frames, so their inclusive share is understated. Each side's
`truncatedStacks` and `truncatedStacksPct` say how many stacks were cut. When more than 1% were cut
on either side, a comparability note says so and no inclusive change is called significant; self
time is judged as usual.

A frame is reported only if its share moved by at least `--min-delta-pct` points. It is called a
`regression` or an `improvement` only when the move is significant: both recordings have at least
30 samples, the frame has at least 5 samples in one of them, and the two-proportion z score is at
least 3. Anything less is `inconclusive`. A share moving says where time went, not that the build
got slower. Findings are drawn from self time and event rates only.

```bash
# Short report: rates, both frame lists, findings
jfr-shell compare before.jfr after.jfr

# Machine output for CI: the keys jfr_compare returns (without session ids), plus inclusiveFrames
jfr-shell compare before.jfr after.jfr --json | jq '.inclusiveFrames[] | select(.significant)'
```

The JSON object has the keys `baseline`, `candidate` (each with `recordingPath`, `eventType`,
`samples`, `observedDurationSeconds`, `totalEvents`, `truncatedStacks`, `truncatedStacksPct`),
`comparability`, `metrics`, `frames`,
`inclusiveFrames`, `minDeltaPct`, `sufficientSamples`, `minSamplesPerRecording`, `findings` and
`findingCounts`. Self-time rows carry `baselineSelfPct` and `candidateSelfPct`; inclusive rows carry
`baselineTotalPct` and `candidateTotalPct`. The exit code is `0` whenever the comparison ran,
whatever it found, `1` when a recording is missing or cannot be read, and `2` on a usage error.

#### Comparing a pprof profile

Either side can be a pprof profile (`.pb.gz`, `.pprof`) when `compare` runs from the `jafar-shell`
jar, which carries the pprof module; jfr-shell's own jar knows JFR only, and reads every file as a
recording. A file that starts with JFR's magic is a recording; any other file is read by the
module that recognises it. The comparison is the same, with these differences:

- **Shares are of the chosen sample type.** A pprof profile has one value column per sample type.
  `--sample-type` (or `--baseline-sample-type` / `--candidate-sample-type`) picks one by `type` or
  `type/unit`; the default is the first CPU type, else the first, and the side's description says
  which was chosen, why, and where its counts came from (`sampleTypeChosen`).
  `--list-sample-types` prints them (a JFR side by its bare event type, which `--event-type` takes).
  Using an option on a side of the other format (`--sample-type` with two recordings) is a usage
  error, exit 2.
- **Significance needs observation counts.** They come from, in order: the column itself when its
  unit is `count` and it counts samples; the column a producer pairs with it (`samples/count`
  beside `cpu/nanoseconds`, when there is exactly one; `cpu-samples` beside `cpu-time`); or the
  column's values summed per frame and divided by the period once, when the period has the column's
  unit. Allocation and lock columns never give counts: allocation object counts (`alloc_objects`,
  `inuse_objects`) are estimates scaled from the sampled allocations, not trials, so a Go heap
  profile has no counts; Go's mutex and block profiles scale each sampled contention by the
  sampling rate and write period 1 whatever the rate, so `contentions` divided by the period is
  not a count either. A profile without counts still gets its shares, but no change is called
  significant, and a note says why.
- **Samplers must measure the same thing.** A sample type named `cpu`, `cpu-time`, `cpu_time` or
  any other `cpu*` is CPU; `wall*` is wall clock. With a pprof side, CPU against wall clock is
  refused unless `--allow-mixed-samplers`, which compares with nothing judged; an allocation profile
  against a CPU or wall one is always refused. Allocation types are `alloc_*`, `inuse_*`,
  async-profiler's `allocations` and `malloc`; lock types `contentions`, `delay`, `lock` and
  async-profiler's `locks`. A sample type whose kind is not recognised is compared with a note. The
  kind comes from the name alone: async-profiler's `jfrconv --wall` names its column `cpu`, so a
  wall-clock profile it converted reads as CPU. Two recordings are never refused: they get the event-type note, as with
  `jfr_compare`.
- **Frame names follow the profile.** A slashed Java name (`java/lang/String.indexOf`) is dotted
  only when the profile looks JVM-originated: some function is `<init>`, `<clinit>` or a
  `$$Lambda`, or comes from a `.java`, `.kt` or `.scala` file. A Go profile keeps
  `encoding/json.Marshal` as stored. A trailing `:<line>` is dropped, so a recording still matches
  its conversion with `includeLineNumbers: true`.
- **No event rates** unless both sides are recordings, and **no truncation count** for a pprof side:
  pprof does not record when a stack was cut, so a note says inclusive shares may be understated.
  Inclusive moves are still judged.

```bash
# A recording against its jfr2pprof conversion: the same frames, so nothing moves
jafar-shell compare before.jfr before.pb.gz
# Two Go-style CPU profiles, comparing the cpu/nanoseconds column
jafar-shell compare old.pb.gz new.pb.gz --sample-type cpu/nanoseconds --json
```

#### Comparing an OTLP profile

An OTLP profiles file (`.otlp`, binary `ProfilesData`) is compared the same way, also from the
`jafar-shell` jar, with the pprof rules above. What differs:

- **Sample types are profiles.** An OTLP file holds profiles of one sample type each; its sample
  types are their distinct `type/unit` pairs, and `--sample-type` selects every profile of one.
  async-profiler writes one profile per file. Its agent names the sample type after its engine:
  `perf_events/count` (CPU, by its `perf_events/ns` period type), `itimer`, `ctimer`, `wall`,
  `object_sampler` (allocations), `malloc_tracer`, `lock_tracer`; `jfrconv -o otlp` writes `cpu`,
  `allocations` and, for `--wall`, `cpu` again, so a wall profile it converted reads as CPU.
- **Counts:** a `count` sample type counts samples (not an allocation or lock one); otherwise one
  per sample timestamp, when every sample has timestamps; otherwise values over the period. A
  sample's weight is the sum of its values, or its number of timestamps when it has none.
- **Layout:** the reader reads opentelemetry-proto v1.10.0's layout (async-profiler 4.6). An older
  file, such as async-profiler 4.1's (v1.7.0), fails with "unsupported OTLP profiles layout"
  rather than giving wrong numbers.
- **Frame names:** async-profiler's frame-type marks (`byte[]_[i]`, `do_syscall_64_[k]`) are not
  part of the name, so its agent's OTLP and its JFR agree.

```bash
# One async-profiler run as JFR and as its OTLP conversion: the same frames and counts
java -agentpath:/path/to/libasyncProfiler.so=start,event=cpu,file=run.jfr -jar app.jar
jfrconv -o otlp run.jfr run.otlp
jafar-shell compare run.jfr run.otlp
# The agent can write OTLP itself
java -agentpath:/path/to/libasyncProfiler.so=start,event=cpu,otlp,file=after.otlp -jar app.jar
jafar-shell compare before.otlp after.otlp --list-sample-types
```

**Exit Codes:**
- `0` - Success
- `1` - Error (with message to stderr)
- `2` - Usage error: a missing or malformed argument (message and the command's usage to stderr)

All non-interactive commands execute without prompts, making them suitable for automation and CI pipelines.

The stdout of `show`, `metadata`, `chunks` and `constants` carries the result only, so `--format
json` output pipes into a JSON parser. With `-Djfr.shell.verbose=true` or `JFR_SHELL_VERBOSE=1`
they also print notes such as `Opened session #1: <file>` on stdout, before the result.

### One JSON result (`--json`)

For a program reading the output, `jfr-shell show ... --json` and `jfr-shell script ... --json`
print one JSON object on stdout and nothing else, whatever the exit status. Everything else
(notes, warnings, log lines, a script's `echo` lines, the error text) goes to stderr. `jafar-shell
show` is the same command. Output without `--json` is unchanged.

```bash
$ jfr-shell show recording.jfr "events/jdk.InitialSystemProperty" --limit 2 --json
{
  "result": [ ... 2 rows ... ],
  "truncated": true,
  "limit": 2
}
```

- `show`: `{"result": <value>, "truncated": <bool>, "limit": <n or null>}`. `result` is the value
  `--format json` prints, or `null` where that prints `(no rows)` (a metadata path that names no
  type or field). `truncated` is `true` when `--limit` left rows out, and `false` when there were
  no more: a limit of exactly the number of rows is not truncated.
- `script`: `{"results": [...]}`, one entry per `show` the script ran, in order, each
  `{"command": <the line>, "result", "truncated", "limit"}`. The `Script executed successfully`
  line is not printed.
- Failure: a nonzero exit status and `{"error": <message>}`; a script's object keeps `"results"`,
  with those of the lines before the failure (or of every line that succeeded, with
  `--continue-on-error`). The message is the failure's own text without the `Error: ` prefix:
  the query error, the usage error, or for a script `Line <n>: <error>` for each failed line. The
  exit status is `2` for an argument error found while reading the command line and `1`
  otherwise.
- A `show` whose result has no JSON form fails: a flame graph, `--tree`, the bare `metadata`
  type list.
- `jafar-shell script` runs in the unified shell, which does not take `--json` (exit status 2,
  nothing on stdout), unless a jfr-shell option such as `--plugin-dir` comes before `script`:
  that command line runs jfr-shell's `script`, which does.

## Metadata Field Paths (mini guide)

Use `show metadata/<Type>` with JfrPath to explore metadata. The `fields` view
is aliased for convenience.

- List fields: `show metadata/<Type>/fields`
- Inspect field: `show metadata/<Type>/fields/<name>`
- Field subproperties: `.../type`, `.../dimension`, `.../annotations`
- Aliases:
  - `show metadata/<Type>/fields.<name>` (same as `.../fields/<name>`)
  - `show metadata/<Type>/fields.<name>/annotations`
  - `show metadata/<Type>/fieldsByName.<name>`
- Trees:
  - Class tree: `show metadata/<Type> --tree [--depth N]`
  - Field tree: `show metadata/<Type>/fields/<name> --tree [--depth N]`
    (renders the field and recursively expands its type)

## Features

- Interactive CLI with sessions (open/list/use/close/info)
- JfrPath queries over `events`, `metadata`, `chunks`, and `constants`
- Table or JSON output (`--format json`)
- Metadata browsing: class/fields/annotations/settings
- Recursive metadata trees (`--tree`, `--depth N`), including field-focused trees
- Variables with lazy query evaluation and caching
- Conditionals (if/elif/else/endif) for control flow
- Helpful completion and contextual `help`

## JfrPath Essentials

- Roots: `events`, `metadata`, `chunks`, `constants`
- Show values: `events/<Type>/<path>` or `metadata/<Type>/<path>`
- Filters:
  - Simple: `[field op value]` with `= != > >= < <= ~` (regex)
  - Boolean expressions with functions and logic: `[expr]`
    - Logic: `and`, `or`, `not`, parentheses
    - String funcs: `contains(path, "substr")`, `startsWith(path, "pre")`, `endsWith(path, "suf")`, `matches(path, "re"[, "i"])`
    - Existence/emptiness: `exists(path)`, `empty(path)`
    - Numeric: `between(path, min, max)`, and `len(path)` for length in comparisons
    - List-scoped: keep `any:/all:/none:` prefixes for list fields (e.g., `any:frames[ matches(method/name/string, ".*Foo.*") ]`)
  - Lists/arrays: prefix with `any:`, `all:`, or `none:` to control how a filter applies across list elements.
    - Examples:
      - `events/jdk.ExecutionSample[contains(sampledThread/osName, "GC")]`
      - `events/jdk.ExecutionSample[stackTrace/truncated=true]`
      - `events/jdk.ExecutionSample[any:stackTrace/frames[matches(method/name/string, ".*Main.*")]]`
- Examples:
  - `events/jdk.FileRead[bytesRead>=1000] --limit 5`
  - `events/jdk.ExecutionSample[sampledThread/javaName~"main"] --limit 10`
  - `show metadata/java.lang.Thread` (class overview)
  - `show metadata/jdk.types.Method/name` (single value)

## Field Projection

Project specific fields from events and build computed expressions with the `select()` operator.

**Basic Syntax:**
- `| select(field1, field2, ...)` — Project specific fields, filtering out all others.
- `| select(field as alias)` — Rename fields with optional alias.
- `| select(expr as alias)` — Computed expressions (alias required).

**Expression Support:**

Expressions can include arithmetic operators, string concatenation, string templates, and built-in functions:

- **Arithmetic Operators:** `+`, `-`, `*`, `/`
- **String Concatenation:** `+` (when either operand is a string)
- **String Templates:** `"text ${expr} more text"` — Interpolate expressions in strings
- **Built-in Functions:**
  - `if(condition, trueValue, falseValue)` — Conditional expression
  - `upper(string)` — Convert to uppercase
  - `lower(string)` — Convert to lowercase
  - `substring(string, start[, length])` — Extract substring
  - `length(string)` — Get string length
  - `coalesce(value1, value2, ...)` — Return first non-null value

**Field Projection Examples:**
```bash
# Simple field selection
events/jdk.FileRead | select(path, bytesRead)

# Convert bytes to kilobytes
events/jdk.FileRead | select(bytesRead / 1024 as kilobytes)

# Build descriptive string (concatenation)
events/jdk.FileRead | select(path + ' (' + bytesRead + ' bytes)' as description)

# Build descriptive string (template - cleaner)
events/jdk.FileRead | select("${path} (${bytesRead} bytes)" as description)

# String template with arithmetic
events/jdk.FileRead | select("${path}: ${bytesRead / 1024} KB" as summary)

# Mixed fields and expressions
events/jdk.FileRead | select(path, bytesRead / 1024 as kb, duration * 1000 as micros)

# String functions
events/jdk.FileRead | select(upper(path) as upperPath, length(path) as len)

# Conditional expressions (true when non-zero; comparisons are not supported)
events/jdk.FileRead | select(if(bytesRead, 'has data', 'empty') as status)

# Complex expressions with coalesce
events/jdk.FileRead | select(coalesce(path, altPath, 'unknown') as finalPath)

# Field aliasing
events/jdk.ExecutionSample | select(sampledThread/javaName as thread)
```

**Expression Evaluation:**
- Null field references return null and propagate through expressions
- Division by zero returns NaN
- String concatenation takes precedence: `"bytes: " + 1024` → `"bytes: 1024"`
- Type coercion for arithmetic: strings and objects convert to numbers where needed

For complete grammar and operator reference, see [JFRPath.md](JFRPath.md).

## Aggregations

Append pipeline functions with `|` to compute aggregates over results.

**Aggregation Functions:**
- `| count()` — Count matching rows/events.
- `| sum([path])` — Sum numeric values. Returns `sum` and `count`.
- `| stats([path])` — Numeric stats: `min`, `max`, `avg`, `stddev`.
- `| quantiles(q1,q2[,path=...])` — Percentiles as `pXX` columns (e.g., `p50`, `p90`).
- `| sketch([path])` — Shortcut: stats + `p50`, `p90`, `p99`.
- `| groupBy(key[, agg=count|sum|avg|min|max, value=path, sortBy=key|value, asc=false])` — Group by key and aggregate with optional sorting.
- `| sortBy(field[, asc=false])` — Sort rows by field (works after any multi-row operator).
- `| top(n[, by=path, asc=false])` — Sort and return top N rows (descending by default).
- `| head(n)` — Take first N rows.
- `| tail(n)` — Take last N rows.
- `| filter(predicate)` — Filter rows mid-pipeline (alias: `where`).
- `| distinct(field)` — Unique values of a field (alias: `unique`).

**Value Transform Functions:**
- `| len([path])` — For attributes: length of a string or list/array. Errors on unsupported types.
- `| uppercase([path])`, `| lowercase([path])`, `| trim([path])` — String transforms.
- `| abs([path])`, `| round([path])`, `| floor([path])`, `| ceil([path])` — Numeric transforms.
- `| contains([path], "substr")` — Boolean: string contains substring.
- `| replace([path], "a", "b")` — String replace occurrences of `a` with `b`.

For complete operator reference and grammar details, see [JFRPath.md](JFRPath.md).

**Aggregation Examples:**
- `events/jdk.FileRead | count()`
- `events/jdk.FileRead/bytesRead | sum()`
- `events/jdk.FileRead/bytesRead | stats()`
- `events/jdk.FileRead/bytesRead | quantiles(0.5,0.9,0.99)`
- `events/jdk.FileRead/bytesRead | sketch()`
- `events/jdk.FileRead | groupBy(path, agg=sum, value=bytesRead)` — Total bytesRead by path
- `events/jdk.ExecutionSample | groupBy(sampledThread/javaName, sortBy=value)` — Sorted by count (descending)
- `events/jdk.ExecutionSample | groupBy(sampledThread/javaName, sortBy=key, asc=true)` — Sorted alphabetically
- `events/jdk.FileRead | top(10, by=bytesRead)` — Top 10 files by bytesRead
- `show metadata/jdk.types.Method/name | count()`
- `constants/jdk.types.Symbol | count()`

**Other Examples:**
- `constants/jdk.types.Symbol[string~"find.*"]` (filter CP entries by field)
- `constants/jdk.types.Symbol[string="java/lang/String"]/id` (filter then project id)
- `constants[name~"jdk\\.types\\..*"]` (filter CP summary rows)
- `events/jdk.GCHeapSummary[when/when="After GC"]/heapSpace` (filter before projection)
- `events/jdk.GCHeapSummary/heapSpace[committedSize>1000000]/reservedSize` (filter relative to projection path)
- `constants/jdk.types.Symbol/string | len()` (string length per CP entry)
- `events/jdk.ExecutionSample/stackTrace/frames | len()` (list length per event)

## Tab Completion

JFR Shell provides intelligent context-aware tab completion throughout the query language:

**Query Roots:**
- `events/` — Event type completion
- `metadata/` — Metadata type completion
- `constants/` — Constant pool type completion
- `chunks/` — Chunk ID completion (e.g., `chunks/1`, `chunks/2`)

**Path Navigation:**
- After `events/<Type>/` — Field names for the event type
- After `metadata/<Type>/` — Subproperties: `fields`, `settings`, `annotations`
- Nested paths — Field names of nested types (e.g., `sampledThread/javaName`)

**Filter Predicates (`[...]`):**
- After `[` — Field names and filter functions (`contains(`, `exists(`, `startsWith(`, `endsWith(`)
- After field name — Operators: `==`, `!=`, `>`, `>=`, `<`, `<=`, `~`, `contains`, `startsWith`, `endsWith`, `matches`
- After condition — Logical operators: `&&`, `||`
- After logical operator — Field names for next condition

**Pipeline Operators (`|`):**
- After `|` — Aggregation functions: `count()`, `sum(`, `groupBy(`, `top(`, `stats(`, `quantiles(`, `sketch(`
- Transform functions: `select(`, `len(`, `uppercase(`, `lowercase(`, `trim(`
- Decoration functions: `decorateByTime(`, `decorateByKey(`
- Inside functions — Field names as parameters

**Command Options:**
- After `--` — Available options: `--limit`, `--format`, `--tree`, `--depth`, `--list-match`

Press Tab at any point to see context-appropriate suggestions.

## Live Event Streaming

Attach to a running JVM and watch its JFR events as they arrive (JEP-349 style, local machine,
no attach API — the shell reads the target's on-disk event repository):

```bash
jfr-shell
jfr> connect                 # list local JVMs; [jfr] = disk-backed JFR repository found
jfr> connect 12345           # attach; the live session becomes current
jfr> tail                    # stream all events until Ctrl-C
jfr> tail "events/jdk.FileRead | filter(bytesRead > 0)" --limit 100
jfr> info                    # live statistics: events seen, dropped, stream state
jfr> close                   # detach (the target JVM is never modified)
```

One-shot (scripting/automation):

```bash
jfr-shell ps
jfr-shell tail 12345 "events/jdk.ExecutionSample" --format json
```

Notes:

- The target needs a **disk-backed** recording (`-XX:StartFlightRecording=disk=true`); an
  in-memory recording only reaches the on-disk repository at rotation/dump.
- Discovery scans the platform temp base (`/tmp` on Linux, the per-user temp dir on macOS,
  `%TEMP%` on Windows). If the target uses `-XX:FlightRecorderOptions=repository=...` or a
  custom location, pass it with `--repository <dir>` — supported by `connect <pid>` (interactive)
  and the one-shot `jfr-shell tail <pid>` command only. The interactive `tail` streams from the
  repository the live session attached to at `connect` time; it accepts only `--limit` and
  `--format`. Explicitly passed repositories are hardened like discovery-found ones (same owner,
  liveness and symlink checks).
- While a live session is current, file-bound commands (`show`, `metadata`, `chunks`, `cp`,
  `backend`, LLM queries) are rejected with a pointer to `tail`; `sessions`/`use`/`close`/`info`
  work unchanged. Per-event filters only — aggregations are Phase 2 (see the design doc,
  `doc/design/jfr-event-streaming.md`).
- Ctrl-C ends `tail` cleanly: the prompt stays alive (interactive) or the process exits 0
  (one-shot).

## See Also

- [JfrPath Reference](JFRPath.md) — Complete query language reference
- [JFR Shell Tutorial](tutorials/jfr-shell-tutorial.md) — Interactive tutorial
- [HdumpPath Reference](hdumppath.md) — Heap dump query language (same shell, similar syntax)
- [Heap Dump Quick Start](hdump-shell-quickstart.md) — Get started with heap dump analysis

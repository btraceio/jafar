# JFR Shell - Interactive JFR CLI

An interactive CLI for exploring Java Flight Recorder (JFR) files: browse metadata, list events, inspect chunks and constant pools, and query values with a concise JfrPath expression.

## Quick Start

### Run the shell

```bash
# Interactive mode
./gradlew :jfr-shell:run --console=plain

# Or pass a file immediately
./gradlew :jfr-shell:run --console=plain --args="-f /path/to/recording.jfr"
```

After building, you can also use the generated scripts in `jfr-shell/build/install/jfr-shell/bin/`.

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
jfr> show events/jdk.FileRead/bytes --limit 5
```

## Available Commands

### Session Management
- `open <path> [--alias NAME]`: Open a recording file.
- `sessions`: List all sessions; marks the current one.
- `use <id|alias>`: Switch current session.
- `close [<id|alias>|--all]`: Close a session or all.
- `info [<id|alias>]`: Show session information.

### Querying and Browsing
- `show <expr> [--limit N] [--format table|json] [--tree] [--depth N] [--list-match any|all|none]`: Evaluate a JfrPath expression. For list fields, `--list-match` sets default matching mode.
- `metadata [--search <glob>|--regex <pat>] [--refresh] [--events-only|--non-events-only] [--primitives] [--summary]`: List types.
- `metadata class <name> [--tree|--json] [--fields] [--annotations] [--depth N]`: Inspect a class.
- `chunks [--summary] [--range N-M]`: List chunk information.
- `chunk <index> show`: Show specific chunk details.
- `cp [<type>] [--summary] [--range N-M]`: Browse constant pool entries.

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
# Show command - Execute JfrPath queries
jfr-shell show <jfr-file> "<expression>" [options]
  --limit, -l <N>           Limit number of results
  --format, -f <format>     Output format: table (default), json
  --list-match <mode>       List matching mode: any, all, none

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

# CP command - List constant pool entries
jfr-shell cp <jfr-file> [options]
  --type, -t <name>         Constant pool type name
  --summary                 Show summary only
  --format, -f <format>     Output format: table (default), json
```

**Non-Interactive Examples:**
```bash
# Count execution samples
jfr-shell show recording.jfr "events/jdk.ExecutionSample | count()"

# Top 10 files by bytes, JSON output
jfr-shell show recording.jfr "events/jdk.FileRead | top(10, by=bytes)" --format json

# Group execution samples by thread
jfr-shell show recording.jfr "events/jdk.ExecutionSample | groupBy(thread/name)"

# List event types only
jfr-shell metadata recording.jfr --events-only

# Chunk summary
jfr-shell chunks recording.jfr --summary

# Constant pool symbols
jfr-shell cp recording.jfr --type jdk.types.Symbol
```

**Exit Codes:**
- `0` - Success
- `1` - Error (with message to stderr)

All non-interactive commands execute without prompts, making them suitable for automation and CI pipelines.

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
- JfrPath queries over `events`, `metadata`, `chunks`, and `cp`
- Table or JSON output (`--format json`)
- Metadata browsing: class/fields/annotations/settings
- Recursive metadata trees (`--tree`, `--depth N`), including field-focused trees
- Helpful completion and contextual `help`

## JfrPath Essentials

- Roots: `events`, `metadata`, `chunks`, `cp`
- Show values: `show events/<Type>/<path>` or `show metadata/<Type>/<path>`
- Filters:
  - Simple: `[field op value]` with `= != > >= < <= ~` (regex)
  - Boolean expressions with functions and logic: `[expr]`
    - Logic: `and`, `or`, `not`, parentheses
    - String funcs: `contains(path, "substr")`, `starts_with(path, "pre")`, `ends_with(path, "suf")`, `matches(path, "re"[, "i"])`
    - Existence/emptiness: `exists(path)`, `empty(path)`
    - Numeric: `between(path, min, max)`, and `len(path)` for length in comparisons
    - List-scoped: keep `any:/all:/none:` prefixes for list fields (e.g., `any:frames[ matches(method/name/string, ".*Foo.*") ]`)
  - Lists/arrays: prefix with `any:`, `all:`, or `none:` to control how a filter applies across list elements.
    - Examples:
      - `show events/jdk.ExecutionSample[contains(sampledThread/osName, "GC")]`
      - `show events/jdk.ExecutionSample[stackTrace/truncated=true]`
      - `show events/jdk.ExecutionSample[any:stackTrace/frames[matches(method/name/string, ".*Main.*")]]`
- Examples:
  - `show events/jdk.FileRead[bytes>=1000] --limit 5`
  - `show events/jdk.ExecutionSample[thread/name~"main"] --limit 10`
  - `show metadata/java.lang.Thread` (class overview)
  - `show metadata/jdk.types.Method/name` (single value)

## Aggregations

Append pipeline functions with `|` to compute aggregates over results.

**Aggregation Functions:**
- `| count()` — Count matching rows/events.
- `| sum([path])` — Sum numeric values. Returns `sum` and `count`.
- `| stats([path])` — Numeric stats: `min`, `max`, `avg`, `stddev`.
- `| quantiles(q1,q2[,path=...])` — Percentiles as `pXX` columns (e.g., `p50`, `p90`).
- `| sketch([path])` — Shortcut: stats + `p50`, `p90`, `p99`.
- `| groupBy(key[, agg=count|sum|avg|min|max, value=path])` — Group by key and aggregate.
- `| top(n[, by=path, asc=false])` — Sort and return top N rows (descending by default).

**Value Transform Functions:**
- `| len([path])` — For attributes: length of a string or list/array. Errors on unsupported types.
- `| uppercase([path])`, `| lowercase([path])`, `| trim([path])` — String transforms.
- `| abs([path])`, `| round([path])`, `| floor([path])`, `| ceil([path])` — Numeric transforms.
- `| contains([path], "substr")` — Boolean: string contains substring.
- `| replace([path], "a", "b")` — String replace occurrences of `a` with `b`.

For complete operator reference and grammar details, see [doc/jfrpath.md](jfrpath.md).

**Aggregation Examples:**
- `show events/jdk.FileRead | count()`
- `show events/jdk.FileRead/bytes | sum()`
- `show events/jdk.FileRead/bytes | stats()`
- `show events/jdk.FileRead/bytes | quantiles(0.5,0.9,0.99)`
- `show events/jdk.FileRead/bytes | sketch()`
- `show events/jdk.ExecutionSample/thread/name | groupBy(value)` — Count by thread name
- `show events/jdk.FileRead | groupBy(path, agg=sum, value=bytes)` — Total bytes by path
- `show events/jdk.FileRead | top(10, by=bytes)` — Top 10 files by bytes
- `show metadata/jdk.types.Method/name | count()`
- `show cp/jdk.types.Symbol | count()`

**Other Examples:**
- `show cp/jdk.types.Symbol[string~"find.*"]` (filter CP entries by field)
- `show cp/jdk.types.Symbol[string="java/lang/String"]/id` (filter then project id)
- `show cp[name~"jdk\\.types\\..*"]` (filter CP summary rows)
- `show events/jdk.GCHeapSummary[when/when="After GC"]/heapSpace` (filter before projection)
- `show events/jdk.GCHeapSummary/heapSpace[committedSize>1000000]/reservedSize` (filter relative to projection path)
- `show cp/jdk.types.Symbol/string | len()` (string length per CP entry)
- `show events/jdk.ExecutionSample/stackTrace/frames | len()` (list length per event)

## Completion Hints

- Roots: Tab complete `events/`, `metadata/`, `cp/`, `chunks/`.
- Under `events/`: event type names; under `metadata/`: all metadata classes.
- Under `cp/`: constant pool types. After a type, filters operate on CP entry fields (e.g., `string` for `jdk.types.Symbol`).
- Common flags: `--limit`, `--format json`, `--list-match` values (`any`, `all`, `none`).

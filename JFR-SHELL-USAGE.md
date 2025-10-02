# JFR Shell - Interactive JFR CLI

An interactive CLI for exploring Java Flight Recorder (JFR) files: browse metadata, list events, inspect chunks and constant pools, and query values with a concise JfrPath expression.

## Quick Start

### Run the shell

```bash
# Using the launcher script
./jfr-cli

# Or pass a file immediately
./jfr-cli -f /path/to/recording.jfr
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
jfr> show metadata/jdk.Thread
jfr> show metadata/jdk.Thread --format json
jfr> show metadata/jdk.types.StackTrace --tree --depth 2
jfr> show metadata/jdk.types.Method/fields/name --tree

# Events example (values projection)
jfr> show events/jdk.FileRead/bytes --limit 5
```

## Available Commands

- `open <path> [--alias NAME]`: Open a recording file.
- `sessions`: List all sessions; marks the current one.
- `use <id|alias>`: Switch current session.
- `close [<id|alias>|--all]`: Close a session or all.
- `info [<id|alias>]`: Show session information.
- `show <expr> [--limit N] [--format json] [--tree] [--depth N] [--list-match any|all|none]`: Evaluate a JfrPath expression. For list fields, `--list-match` sets default matching mode.
- `metadata [--search <glob>|--regex <pat>] [--refresh] [--events-only|--non-events-only] [--primitives] [--summary]`: List types.
- `metadata class <name> [--tree|--json] [--fields] [--annotations] [--depth N]`: Inspect a class.
- `help [<command>]`: Show contextual help.
- `exit` / `quit`: Exit shell.

## Command Line Options

```bash
Usage: jfr-shell [-hqV] [-f=<jfrFile>]
Interactive JFR CLI
  -f, --file=<jfrFile>   JFR file to open immediately
  -h, --help             Show this help message and exit
  -q, --quiet            Suppress banner
  -V, --version          Print version information and exit
```

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
- Filters: `[field op value]` with `= != > >= < <= ~` (regex)
  - Lists/arrays: prefix with `any:`, `all:`, or `none:` to control how a filter applies across list elements.
    - Examples:
      - `show events/jdk.ExecutionSample[stackTrace/truncated=true]`
      - `show events/jdk.ExecutionSample[any:stackTrace/frames/method/name/string~".*Main.*"]`
- Examples:
  - `show events/jdk.FileRead[bytes>=1000] --limit 5`
  - `show events/jdk.ExecutionSample[thread/name~"main"] --limit 10`
  - `show metadata/jdk.Thread` (class overview)
  - `show metadata/jdk.types.Method/name` (single value)

## Aggregations

Append pipeline functions with `|` to compute aggregates over results.

- `| count()` — Count matching rows/events.
- `| stats([path])` — Numeric stats: `min`, `max`, `avg`, `stddev`.
- `| quantiles(q1,q2[,path=...])` — Percentiles as `pXX` columns (e.g., `p50`, `p90`).
- `| sketch([path])` — Shortcut: stats + `p50`, `p90`, `p99`.

Examples:
- `show events/jdk.FileRead | count()`
- `show events/jdk.FileRead/bytes | stats()`
- `show events/jdk.FileRead/bytes | quantiles(0.5,0.9,0.99)`
- `show events/jdk.FileRead | sketch(path=bytes)`
- `show metadata/jdk.types.Method/name | count()`
- `show cp/jdk.types.Symbol | count()`

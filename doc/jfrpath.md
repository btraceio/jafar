# JfrPath Reference

JfrPath is a path-based query language for navigating and querying Java Flight Recorder (JFR) files. It provides a concise syntax for accessing events, metadata, chunks, and constant pools with filtering, projection, and aggregation capabilities.

## Grammar Overview

```
<query>     ::= <root> "/" <segments> [<filters>] [<projection>] [<pipeline>]
<root>      ::= "events" | "metadata" | "chunks" | "cp"
<segments>  ::= <segment> ("/" <segment>)*
<segment>   ::= <identifier> | <index> | <slice>
<filters>   ::= "[" <predicate> "]" ("[" <predicate> "]")*
<predicate> ::= <simple-filter> | <expr-filter>
<projection>::= "/" <segment> ("/" <segment>)*
<pipeline>  ::= "|" <operator> ("|" <operator>)*
```

## Roots

JfrPath queries start with one of four roots:

### `events/<type>`
Access event data from the recording.
- **Requires**: Event type name (e.g., `jdk.ExecutionSample`, `jdk.FileRead`)
- **Returns**: Event instances with all fields
- **Example**: `events/jdk.FileRead`

### `metadata/<type>`
Access type metadata (structure, fields, annotations).
- **Requires**: Type name (e.g., `java.lang.Thread`, `jdk.types.Method`)
- **Returns**: Type metadata as structured data
- **Example**: `metadata/jdk.types.StackTrace`

### `chunks`
Access chunk-level information from the recording.
- **Returns**: Chunk metadata (offset, size, duration, compression status)
- **Example**: `chunks`
- **Specific chunk**: `chunks/0` (by index)

### `cp` or `cp/<type>`
Access constant pool entries.
- **Summary**: `cp` returns all CP types with counts
- **Entries**: `cp/<type>` returns entries for specific type
- **Example**: `cp/jdk.types.Symbol`

## Path Segments

After the root, segments navigate through the structure:

### Field Access
```
events/jdk.FileRead/path
metadata/jdk.types.Method/name
cp/jdk.types.Symbol/string
```

### Nested Fields
```
events/jdk.ExecutionSample/thread/name
events/jdk.FileRead/stackTrace/frames
metadata/jdk.types.StackTrace/fields/name
```

### Array/List Access
```
events/jdk.ExecutionSample/stackTrace/frames/0          # First element
events/jdk.ExecutionSample/stackTrace/frames/0:3        # Slice [0,3)
```

### Metadata Aliases
Convenient shortcuts for metadata navigation:
```
metadata/jdk.types.Method/fields.name           # Same as /fields/name
metadata/jdk.types.Method/fieldsByName.name     # Access field by name
```

## Filters

Filters use square brackets `[...]` to constrain results. Filters can be placed:
- After the root/type: `events/jdk.FileRead[bytes>1000]`
- After any segment (interleaved): `events/jdk.GCHeapSummary/heapSpace[committedSize>1000000]`

### Simple Filters

Simple field comparisons:

```
[field op value]
```

**Operators**:
- `=` : Equal
- `!=` : Not equal
- `>` : Greater than
- `>=` : Greater than or equal
- `<` : Less than
- `<=` : Less than or equal
- `~` : Regex match

**Examples**:
```
events/jdk.FileRead[bytes>1000]
events/jdk.ExecutionSample[thread/name="main"]
events/jdk.FileRead[path~"/tmp/.*"]
metadata/jdk.types.Method[name="toString"]
```

### Boolean Expression Filters

Complex conditions with functions and logic:

```
[<expr> and <expr>]
[<expr> or <expr>]
[not <expr>]
[(<expr>) and (<expr>)]
```

**Examples**:
```
events/jdk.FileRead[bytes>1000 and path~"/tmp/.*"]
events/jdk.ExecutionSample[thread/name="main" or thread/name="worker"]
events/jdk.FileRead[not (bytes<100)]
```

### Filter Functions

#### String Functions
- `contains(field, "substr")` - Check if string contains substring
- `starts_with(field, "prefix")` - Check if string starts with prefix
- `ends_with(field, "suffix")` - Check if string ends with suffix
- `matches(field, "regex")` - Check if string matches regex
- `matches(field, "regex", "i")` - Case-insensitive regex match

**Examples**:
```
events/jdk.FileRead[contains(path, "tmp")]
events/jdk.ExecutionSample[starts_with(thread/name, "pool-")]
events/jdk.FileRead[ends_with(path, ".log")]
events/jdk.ExecutionSample[matches(thread/name, "worker-[0-9]+")]
```

#### Existence and Emptiness
- `exists(field)` - Check if field is present and not null
- `empty(field)` - Check if string/list is empty

**Examples**:
```
events/jdk.FileRead[exists(path)]
events/jdk.ExecutionSample[not empty(stackTrace/frames)]
```

#### Numeric Functions
- `between(field, min, max)` - Check if value is between min and max (inclusive)
- `len(field)` - Get length of string or list (can be used in comparisons)

**Examples**:
```
events/jdk.FileRead[between(bytes, 1000, 10000)]
events/jdk.ExecutionSample[len(stackTrace/frames)>10]
events/jdk.FileRead[len(path)>50]
```

### List/Array Matching

For list and array fields, control how filters apply to elements:

- `any:` (default) - Filter matches if ANY element satisfies condition
- `all:` - Filter matches if ALL elements satisfy condition
- `none:` - Filter matches if NO elements satisfy condition

**Syntax**:
```
[any:<listField>[condition]]
[all:<listField>[condition]]
[none:<listField>[condition]]
```

**Examples**:
```
events/jdk.ExecutionSample[any:stackTrace/frames[matches(method/name/string, ".*Foo.*")]]
events/jdk.ExecutionSample[all:stackTrace/frames[lineNumber>0]]
events/jdk.ExecutionSample[none:stackTrace/frames[matches(method/name/string, ".*Test.*")]]
```

### Interleaved Filters

Filters can be placed after any segment to filter at that level:

```
events/jdk.GCHeapSummary[when/when="After GC"]/heapSpace[committedSize>1000000]/reservedSize
```

This filters:
1. Events where `when/when="After GC"`
2. Then projects to `heapSpace`
3. Then filters heapSpace entries where `committedSize>1000000`
4. Then projects to `reservedSize`

## Aggregation Pipeline

Pipeline operators transform or aggregate results. Append with `|`:

```
events/jdk.FileRead | count()
events/jdk.FileRead/bytes | stats()
```

### Count
```
| count()
```

Count the number of rows/events.

**Returns**: `{ "count": N }`

**Examples**:
```
events/jdk.FileRead | count()
metadata/jdk.types.Method/name | count()
cp/jdk.types.Symbol | count()
```

### Sum
```
| sum([path])
```

Sum numeric values. If `path` is omitted, uses the projection path.

**Returns**: `{ "sum": total, "count": N }`

**Examples**:
```
events/jdk.FileRead/bytes | sum()
events/jdk.FileRead | sum(bytes)
```

### Stats
```
| stats([path])
```

Compute statistics for numeric values: min, max, avg, stddev.

**Returns**: `{ "min": M, "max": N, "avg": A, "stddev": S, "count": C }`

**Examples**:
```
events/jdk.FileRead/bytes | stats()
events/jdk.FileRead | stats(bytes)
```

### Quantiles
```
| quantiles(q1, q2, ...[, path=...])
```

Compute percentiles at specified quantiles (0.0 to 1.0).

**Returns**: Columns `pXX` for each quantile (e.g., `p50`, `p90`, `p99`)

**Examples**:
```
events/jdk.FileRead/bytes | quantiles(0.5, 0.9, 0.99)
events/jdk.FileRead | quantiles(0.5, 0.9, path=bytes)
```

### Sketch
```
| sketch([path])
```

Shortcut for stats + common percentiles (p50, p90, p99).

**Returns**: `{ "min": M, "max": N, "avg": A, "stddev": S, "p50": X, "p90": Y, "p99": Z, "count": C }`

**Examples**:
```
events/jdk.FileRead/bytes | sketch()
events/jdk.FileRead | sketch(bytes)
```

### Group By
```
| groupBy(keyPath[, agg=count|sum|avg|min|max, value=path])
```

Group results by key and apply aggregation function.

**Parameters**:
- `keyPath` - Field path to group by
- `agg` - Aggregation function (default: `count`)
- `value` - Value path for sum/avg/min/max

**Returns**: `{ "key": groupKey, "<agg>": result }`

**Examples**:
```
events/jdk.ExecutionSample/thread/name | groupBy(value)                    # Count by thread name
events/jdk.FileRead | groupBy(path, agg=count)                              # Count by file path
events/jdk.FileRead | groupBy(path, agg=sum, value=bytes)                   # Total bytes by path
events/jdk.ExecutionSample | groupBy(thread/name, agg=count)                # Count by thread name
events/jdk.FileRead | groupBy(path, agg=avg, value=bytes)                   # Avg bytes by path
events/jdk.FileRead | groupBy(path, agg=min, value=bytes)                   # Min bytes by path
events/jdk.FileRead | groupBy(path, agg=max, value=bytes)                   # Max bytes by path
```

### Top
```
| top(n[, by=path, asc=false])
```

Sort and return top N rows.

**Parameters**:
- `n` - Number of results to return
- `by` - Path to sort by (default: `value`)
- `asc` - Sort ascending (default: `false`, descending)

**Examples**:
```
events/jdk.FileRead | top(10, by=bytes)                   # Top 10 by bytes (descending)
events/jdk.FileRead/bytes | top(5)                         # Top 5 values
events/jdk.FileRead | top(10, by=bytes, asc=true)         # Bottom 10 by bytes (ascending)
```

## Value Transform Functions

Transform individual values (can also be used in filters where applicable):

### String Transforms
- `| len([path])` - String or list length
- `| uppercase([path])` - Convert to uppercase
- `| lowercase([path])` - Convert to lowercase
- `| trim([path])` - Trim whitespace
- `| contains([path], "substr")` - Check if contains substring
- `| replace([path], "old", "new")` - Replace occurrences

### Numeric Transforms
- `| abs([path])` - Absolute value
- `| round([path])` - Round to nearest integer
- `| floor([path])` - Round down
- `| ceil([path])` - Round up

**Examples**:
```
cp/jdk.types.Symbol/string | len()
events/jdk.ExecutionSample/thread/name | uppercase()
events/jdk.FileRead/path | replace("/tmp/", "/data/")
events/jdk.FileRead/bytes | abs()
```

## Usage Patterns

### Event Queries

List all events of a type:
```
events/jdk.FileRead
events/jdk.ExecutionSample
```

Project to specific field:
```
events/jdk.FileRead/path
events/jdk.ExecutionSample/thread/name
```

Filter and project:
```
events/jdk.FileRead[bytes>1000]/path
events/jdk.ExecutionSample[thread/name="main"]/stackTrace
```

Count matching events:
```
events/jdk.FileRead[bytes>1000] | count()
events/jdk.ExecutionSample[thread/name~"worker-.*"] | count()
```

Aggregate values:
```
events/jdk.FileRead/bytes | stats()
events/jdk.FileRead[path~"/tmp/.*"]/bytes | sum()
events/jdk.ExecutionSample | groupBy(thread/name)
events/jdk.FileRead | top(10, by=bytes)
```

### Metadata Queries

Inspect type structure:
```
metadata/jdk.types.StackTrace
metadata/java.lang.Thread
```

List field names:
```
metadata/jdk.types.Method/fields/name
metadata/jdk.types.StackTrace/fields.name
```

Access specific field metadata:
```
metadata/jdk.types.Method/fields/name/type
metadata/jdk.types.StackTrace/fields.frames/annotations
```

Tree view (recursive):
```
metadata/jdk.types.StackTrace --tree --depth 2
metadata/jdk.types.Method/fields/name --tree
```

### Chunk Queries

List all chunks:
```
chunks
```

Specific chunk by index:
```
chunks/0
chunks/5
```

Filter chunks:
```
chunks[size>1000000]
chunks[compressed=true]
```

Chunk summary:
```
chunks --summary
```

### Constant Pool Queries

CP summary (all types with counts):
```
cp
```

Entries for specific type:
```
cp/jdk.types.Symbol
cp/jdk.types.Method
cp/jdk.types.Package
```

Filter CP entries:
```
cp/jdk.types.Symbol[string~"java/.*"]
cp/jdk.types.Method[name="toString"]
```

Project CP entry field:
```
cp/jdk.types.Symbol/string
cp/jdk.types.Method/name
```

Aggregate:
```
cp/jdk.types.Symbol | count()
cp/jdk.types.Symbol/string | len()
```

## Operator Limitations

- Pipeline operators are **not chainable**: `| count() | sum()` is not supported
- Each query can have at most one pipeline operator
- For multi-step analytics, use interactive commands or scripting

## Design Principles

1. **Streaming First**: All operations use streaming parser when possible
2. **Lazy Evaluation**: Events processed incrementally, not loaded into memory
3. **Early Abort**: `--limit` stops parsing once enough matches collected
4. **Minimal Syntax**: Path-based navigation, filters inline with brackets
5. **Composability**: Filters, projection, and pipelines compose naturally

## Examples by Root

### Events Examples
```bash
# Count execution samples
show events/jdk.ExecutionSample | count()

# Top 10 files by bytes read
show events/jdk.FileRead | top(10, by=bytes)

# Execution samples by thread
show events/jdk.ExecutionSample | groupBy(thread/name)

# File reads to /tmp, sum bytes
show events/jdk.FileRead[path~"/tmp/.*"] | sum(bytes)

# Execution samples with deep stacks
show events/jdk.ExecutionSample[len(stackTrace/frames)>20] --limit 5

# GC events after GC
show events/jdk.GCHeapSummary[when/when="After GC"]/heapSpace
```

### Metadata Examples
```bash
# Inspect StackTrace structure
show metadata/jdk.types.StackTrace

# List all field names in Method
show metadata/jdk.types.Method/fields/name

# Field annotations
show metadata/jdk.types.StackTrace/fields.frames/annotations

# Recursive tree view
show metadata/jdk.types.StackTrace --tree --depth 3

# Count metadata types
show metadata/jdk.types.Method/name | count()
```

### Chunks Examples
```bash
# List all chunks
show chunks

# Chunk summary stats
show chunks --summary

# Large chunks only
show chunks[size>10000000]

# Compressed chunks
show chunks[compressed=true]

# Specific chunk
show chunks/0
```

### Constant Pool Examples
```bash
# CP summary
show cp

# All Symbol entries
show cp/jdk.types.Symbol

# Symbols matching pattern
show cp/jdk.types.Symbol[string~"java/.*"]

# Count symbols
show cp/jdk.types.Symbol | count()

# Symbol string lengths
show cp/jdk.types.Symbol/string | len()

# Methods named "toString"
show cp/jdk.types.Method[name="toString"]
```

## Interactive Commands

In the interactive shell, use these commands:

- `open <path> [--alias NAME]` - Open a recording
- `sessions` - List all sessions
- `use <id|alias>` - Switch session
- `close [<id|alias>|--all]` - Close session(s)
- `info [<id|alias>]` - Show session info
- `show <expr> [options]` - Execute JfrPath query
- `metadata [options]` - List types
- `metadata class <name> [options]` - Inspect class
- `chunks [options]` - List chunks
- `chunk <index> show` - Show specific chunk
- `cp [<type>] [options]` - List CP entries
- `help [<command>]` - Show help
- `exit` / `quit` - Exit

## Non-Interactive Mode

Execute queries without entering the shell:

```bash
# Show command
jfr-shell show recording.jfr "events/jdk.FileRead | count()"
jfr-shell show recording.jfr "events/jdk.FileRead/bytes | stats()" --format json

# Metadata command
jfr-shell metadata recording.jfr --events-only
jfr-shell metadata recording.jfr --search FileRead

# Chunks command
jfr-shell chunks recording.jfr --summary

# CP command
jfr-shell cp recording.jfr --type jdk.types.Symbol
jfr-shell cp recording.jfr --summary
```

All non-interactive commands:
- Return exit code 0 on success, 1 on error
- Write errors to stderr
- Support `--format json` for structured output
- Execute without prompts (suitable for CI/scripting)

See `jfr-shell --help` and `jfr-shell <command> --help` for full options.

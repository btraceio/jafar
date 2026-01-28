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
- `startsWith(field, "prefix")` - Check if string starts with prefix
- `endsWith(field, "suffix")` - Check if string ends with suffix
- `matches(field, "regex")` - Check if string matches regex
- `matches(field, "regex", "i")` - Case-insensitive regex match

**Examples**:
```
events/jdk.FileRead[contains(path, "tmp")]
events/jdk.ExecutionSample[startsWith(thread/name, "pool-")]
events/jdk.FileRead[endsWith(path, ".log")]
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
| groupBy(keyPath[, agg=count|sum|avg|min|max, value=path, sortBy=key|value, asc=false])
```

Group results by key and apply aggregation function with optional sorting.

**Parameters**:
- `keyPath` - Field path to group by
- `agg` - Aggregation function (default: `count`)
- `value` - Value path for sum/avg/min/max
- `sortBy` - Sort results by `key` (grouping key) or `value` (aggregated value)
- `asc` - Sort ascending (default: `false`, descending)

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

# Sorted results
events/jdk.ExecutionSample | groupBy(thread/name, sortBy=value)             # Sort by count descending
events/jdk.ExecutionSample | groupBy(thread/name, sortBy=key, asc=true)     # Sort alphabetically
events/jdk.FileRead | groupBy(path, agg=sum, value=bytes, sortBy=value)     # Sort by total bytes
```

### Sort By
```
| sortBy(field[, asc=false])
```

Sort rows by any field in the current result set. Works after any operator that produces multiple rows.

**Parameters**:
- `field` - Field name to sort by (must exist in current row structure)
- `asc` - Sort ascending (default: `false`, descending)

**Key constraint**: Can only sort by fields available after previous operators:
- After `select(a, b)` → only `a`, `b` available
- After `groupBy(x)` → only `key`, `<aggFunc>` available
- After `len(path)` → all original fields + `len`

**Examples**:
```
events/jdk.FileRead | select(path, bytes) | sortBy(bytes)              # Sort by bytes descending
events/jdk.FileRead | select(path, bytes) | sortBy(path, asc=true)     # Sort by path ascending
events/jdk.ExecutionSample | groupBy(thread/name) | sortBy(count)      # Sort grouped results by count
events/jdk.FileRead | len(path) | sortBy(len)                          # Sort by computed length
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

### Decorate By Time
```
| decorateByTime(decoratorEventType, fields=field1,field2[, threadPath=..., decoratorThreadPath=...])
```

Decorate events with information from time-overlapping events on the same thread.

**Use Cases**: Correlate events that occur during the same time period (e.g., execution samples during monitor waits, allocations during GC phases).

**Parameters**:
- `decoratorEventType` - Event type to use as decorator (quoted string)
- `fields` - Comma-separated list of fields to extract from decorator
- `threadPath` - Optional: path to thread ID in primary event (default: `eventThread/javaThreadId`)
- `decoratorThreadPath` - Optional: path to thread ID in decorator (default: `eventThread/javaThreadId`)

**Overlap Logic**: Events overlap if their time ranges intersect:
```
primaryStart < decoratorEnd && primaryEnd > decoratorStart
```

**Decorator Field Access**: Decorated fields are accessed using `$decorator.` prefix:
```
$decorator.fieldName
```

**Examples**:
```
# Find execution samples during monitor waits
events/jdk.ExecutionSample | decorateByTime(jdk.JavaMonitorWait, fields=monitorClass,duration)

# Correlate allocations with GC phases
events/jdk.ObjectAllocationSample | decorateByTime(jdk.GCPhase, fields=name,duration)

# Execution samples during I/O operations
events/jdk.ExecutionSample | decorateByTime(jdk.SocketWrite, fields=host,port,bytesWritten)

# Access decorator fields
events/jdk.ExecutionSample | decorateByTime(jdk.JavaMonitorEnter, fields=monitorClass)
  | groupBy($decorator.monitorClass)
```

### Decorate By Key
```
| decorateByKey(decoratorEventType, key=keyPath, decoratorKey=decoratorKeyPath, fields=field1,field2)
```

Decorate events using correlation keys derived from event fields.

**Use Cases**: Join events with shared identifiers (e.g., request tracing, correlation by thread or custom IDs).

**Parameters**:
- `decoratorEventType` - Event type to use as decorator (quoted string)
- `key` - Path to correlation key in primary event
- `decoratorKey` - Path to correlation key in decorator event
- `fields` - Comma-separated list of fields to extract from decorator

**Decorator Field Access**: Decorated fields are accessed using `$decorator.` prefix:
```
$decorator.fieldName
```

**Examples**:
```
# Correlate execution samples with request context by thread ID
events/jdk.ExecutionSample | decorateByKey(RequestStart,
                                            key=sampledThread/javaThreadId,
                                            decoratorKey=thread/javaThreadId,
                                            fields=requestId,endpoint,userId)

# Join file reads with thread metadata
events/jdk.FileRead | decorateByKey(jdk.ThreadStart,
                                     key=eventThread/javaThreadId,
                                     decoratorKey=thread/javaThreadId,
                                     fields=javaName,group)

# Group execution samples by request endpoint
events/jdk.ExecutionSample | decorateByKey(RequestStart,
                                            key=sampledThread/javaThreadId,
                                            decoratorKey=thread/javaThreadId,
                                            fields=endpoint)
  | groupBy($decorator.endpoint)

# Access decorator fields in filters
events/jdk.ExecutionSample | decorateByKey(RequestStart,
                                            key=sampledThread/javaThreadId,
                                            decoratorKey=thread/javaThreadId,
                                            fields=endpoint,requestId)
  | top(10, by=$decorator.requestId)
```

**Notes**:
- If no decorator matches, `$decorator.` fields will be `null`
- Multiple decorators matching the same event: first match is used
- Only requested fields from decorator are accessible
- Memory-efficient: decorator fields are lazily accessed

### Select
```
| select(field1, field2, expr1 as alias1, ...)
```

Project specific fields from events, filtering out all other fields. Supports both simple field paths and computed expressions.

**Use Cases**: Reduce output to only relevant fields, compute derived values, transform data, control JSON output structure, focus analysis on specific attributes.

**Parameters**:
- `field` - Simple field path to include in output
- `field as alias` - Field path with custom output name
- `expression as alias` - Computed expression (requires alias)
- Supports nested fields using `/` syntax (e.g., `eventThread/javaThreadId`)

**Behavior**:
- Only specified fields/expressions are included in the output
- Simple fields use leaf segment as column name (or alias if provided)
- Expressions require `as alias` clause
- Works with filters and other query operations
- Expressions are evaluated per row

**Simple Field Selection**:
```
# Select single field
events/jdk.ExecutionSample | select(startTime)

# Select multiple top-level fields
events/jdk.FileRead | select(path, bytes)

# Select nested fields
events/jdk.ExecutionSample | select(eventThread/javaThreadId, eventThread/name)

# Field with alias
events/jdk.ExecutionSample | select(eventThread/javaThreadId as threadId)

# Combine with filters
events/jdk.FileRead[bytes>1000] | select(path, bytes)

# Select decorator fields
events/jdk.ExecutionSample | decorateByTime(jdk.JavaMonitorWait, fields=monitorClass)
  | select(startTime, $decorator.monitorClass)
```

**Computed Expressions**:

Expressions support arithmetic operations, string concatenation, and built-in functions.

**Arithmetic Operators**:
- `+` - Addition or string concatenation
- `-` - Subtraction
- `*` - Multiplication
- `/` - Division

```
# Convert bytes to kilobytes
events/jdk.FileRead | select(bytes / 1024 as kilobytes)

# Calculate throughput
events/jdk.FileRead | select(bytes / duration as bytesPerNs)

# Multiply duration by 1000
events/jdk.FileRead | select(duration * 1000 as micros)

# Complex arithmetic
events/jdk.FileRead | select((bytes * count) / 1024 as totalKb)
```

**String Concatenation**:
```
# Build descriptive string
events/jdk.FileRead | select(path + ' (' + bytes + ' bytes)' as description)

# Combine fields
events/jdk.ExecutionSample | select(thread/name + ' [' + thread/javaThreadId + ']' as threadInfo)

# Format output
events/jdk.FileRead | select('File: ' + path as label)
```

**String Templates**:

String templates provide a cleaner syntax for string interpolation using `${...}` embedded expressions:

```
# Simple field interpolation
events/jdk.FileRead | select("File: ${path}" as description)

# Multiple expressions in one template
events/jdk.FileRead | select("${path} (${bytes} bytes)" as info)

# Arithmetic in templates
events/jdk.FileRead | select("${path}: ${bytes / 1024} KB" as summary)

# Functions in templates
events/jdk.FileRead | select("File: ${upper(path)} - ${bytes} bytes" as info)

# Nested fields in templates
events/jdk.ExecutionSample | select("Thread ${eventThread/name} (ID: ${eventThread/javaThreadId})" as threadInfo)

# Mix templates with regular fields
events/jdk.FileRead | select(path, "${bytes / 1024} KB" as sizeKb)
```

Templates are equivalent to string concatenation but more readable:
- `"${path} (${bytes} bytes)"` is the same as `path + ' (' + bytes + ' bytes)'`
- Use double quotes for templates: `"${expr}"`
- Any expression can be embedded in `${...}`
- Null values render as empty strings

**Built-in Functions**:

**if(condition, trueValue, falseValue)** - Conditional expression
```
events/jdk.FileRead | select(if(bytes, 'large', 'small') as size)
events/jdk.FileRead | select(if(duration, 'slow', 'fast') as speed)
```

**upper(string)** - Convert to uppercase
```
events/jdk.FileRead | select(upper(path) as upperPath)
events/jdk.ExecutionSample | select(upper(thread/name) as threadName)
```

**lower(string)** - Convert to lowercase
```
events/jdk.FileRead | select(lower(path) as lowerPath)
```

**substring(string, start[, length])** - Extract substring
```
events/jdk.FileRead | select(substring(path, 0, 20) as shortPath)
events/jdk.FileRead | select(substring(path, 5) as pathTail)
events/jdk.FileRead | select(substring(path, 0, 10) as prefix)
```

**length(string)** - Get string length
```
events/jdk.FileRead | select(length(path) as pathLength)
events/jdk.ExecutionSample | select(length(thread/name) as nameLen)
```

**coalesce(value1, value2, ...)** - Return first non-null value
```
events/jdk.FileRead | select(coalesce(path, altPath, 'unknown') as finalPath)
events/jdk.ExecutionSample | select(coalesce(thread/name, thread/osName, 'unnamed') as name)
```

**Mixed Fields and Expressions**:
```
# Simple fields with computed expressions
events/jdk.FileRead | select(path, bytes / 1024 as kb, duration)

# Multiple expressions and fields
events/jdk.FileRead | select(
    path as file,
    bytes / 1024 as kilobytes,
    if(bytes, 'large', 'small') as sizeCategory,
    duration * 1000 as microseconds
)

# Transform and compute
events/jdk.ExecutionSample | select(
    startTime,
    upper(thread/name) as threadName,
    thread/javaThreadId as tid,
    length(stackTrace/frames) as stackDepth
)
```

**Expression Evaluation**:
- Field references in expressions access the current row's data
- Arithmetic operations convert values to numbers (null becomes 0)
- String concatenation converts all values to strings
- Division by zero returns NaN
- Functions receive evaluated arguments

**Column Naming**:
- Simple fields: Use leaf segment name (e.g., `javaThreadId` from `eventThread/javaThreadId`)
- Aliased fields: Use the specified alias
- Expressions: Must provide alias (e.g., `bytes / 1024 as kb`)

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

# Monitor contention analysis: samples during lock waits
show events/jdk.ExecutionSample | decorateByTime(jdk.JavaMonitorWait, fields=monitorClass,duration)

# Request tracing: correlate samples with request context
show events/jdk.ExecutionSample | decorateByKey(RequestStart,
                                                  key=sampledThread/javaThreadId,
                                                  decoratorKey=thread/javaThreadId,
                                                  fields=requestId,endpoint)

# GC impact: allocations during GC phases
show events/jdk.ObjectAllocationSample | decorateByTime(jdk.GCPhase, fields=name)
  | groupBy($decorator.name, agg=sum, value=allocationSize)
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

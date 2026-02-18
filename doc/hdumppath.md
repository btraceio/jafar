# HdumpPath Reference

HdumpPath is a query language for analyzing Java heap dumps (HPROF files). Inspired by OQL (Object Query Language), it provides a concise syntax for querying objects, classes, and GC roots with filtering, projection, and aggregation.

## Grammar Overview

```
<query>     ::= <root> ("/" <type-spec>)? ("[" <predicates> "]")? ("|" <pipeline>)*
<root>      ::= "objects" | "classes" | "gcroots"
<type-spec> ::= ("instanceof" "/")? <class-pattern>
<predicates>::= <predicate> (("and" | "or") <predicate>)*
<predicate> ::= <field-path> <op> <literal> | "not" <predicate> | "(" <predicates> ")"
<pipeline>  ::= <operator> ("(" <args> ")")?
```

## Roots

HdumpPath queries start with one of three roots:

### `objects`

Query heap object instances.

```
objects                                    # All objects
objects/java.lang.String                   # String objects only
objects/instanceof/java.util.Map           # Map implementations
objects/java.util.*                        # Glob pattern matching
```

**Available fields:**
| Field | Type | Description |
|-------|------|-------------|
| `id` | long | Object ID (heap address) |
| `class` | String | Fully qualified class name |
| `className` | String | Alias for `class` |
| `shallow` | long | Shallow size in bytes |
| `shallowSize` | long | Alias for `shallow` |
| `retained` | long | Retained size in bytes (if computed) |
| `retainedSize` | long | Alias for `retained` |
| `arrayLength` | int | Length (arrays only) |
| `stringValue` | String | String content (String objects only) |

### `classes`

Query class metadata.

```
classes                                    # All classes
classes/java.util.HashMap                  # Specific class
classes/java.util.*                        # Glob pattern
```

**Available fields:**
| Field | Type | Description |
|-------|------|-------------|
| `id` | long | Class ID |
| `name` | String | Fully qualified class name |
| `simpleName` | String | Simple class name |
| `instanceCount` | int | Number of instances |
| `instanceSize` | long | Size of each instance |
| `superClass` | String | Superclass name |
| `isArray` | boolean | Whether class is an array type |

### `gcroots`

Query garbage collection roots.

```
gcroots                                    # All GC roots
gcroots/THREAD_OBJ                         # Thread roots only
gcroots/JNI_GLOBAL                         # JNI global references
```

**GC Root Types:**
- `UNKNOWN` - Unknown root type
- `JNI_GLOBAL` - JNI global reference
- `JNI_LOCAL` - JNI local reference
- `JAVA_FRAME` - Java stack frame
- `NATIVE_STACK` - Native stack
- `STICKY_CLASS` - System class
- `THREAD_BLOCK` - Thread block
- `MONITOR_USED` - Monitor in use
- `THREAD_OBJ` - Thread object

**Available fields:**
| Field | Type | Description |
|-------|------|-------------|
| `type` | String | GC root type name |
| `objectId` | long | Referenced object ID |
| `object` | String | Object description (class@id) |
| `threadSerial` | int | Thread serial number |
| `frameNumber` | int | Stack frame number |

## Type Specifications

### Exact Match
```
objects/java.lang.String
classes/java.util.HashMap
```

### Glob Patterns
Use `*` for wildcard matching:
```
objects/java.util.*                        # All java.util classes
objects/com.example.*Service               # Classes ending in Service
classes/java.lang.reflect.*                # Reflection classes
```

### Instanceof (Subclass Matching)
Include subclasses and implementations:
```
objects/instanceof/java.util.Map           # HashMap, TreeMap, ConcurrentHashMap, etc.
objects/instanceof/java.io.Serializable    # All serializable objects
```

### Array Types
Query array instances using Java notation or JVM descriptor format:

**Java notation (recommended):**
```
objects/java.lang.Object[]               # Object arrays
objects/java.lang.String[]               # String arrays
objects/int[]                            # int arrays
objects/byte[]                           # byte arrays
objects/java.lang.Object[][]             # 2D Object arrays
```

**JVM descriptor format:**
```
objects/[Ljava.lang.Object;              # Object arrays
objects/[I                               # int arrays
objects/[B                               # byte arrays
objects/[[Ljava.lang.String;             # 2D String arrays
```

Both notations can be combined with predicates and pipeline operations:
```
objects/java.lang.Object[][arrayLength > 1000] | top(10, shallow)
objects/[Ljava.lang.Object;[retained > 100MB] | pathToRoot
```

**Primitive array type codes** (JVM format):
| Java type | Code |
|-----------|------|
| `boolean` | `Z`  |
| `char`    | `C`  |
| `int`     | `I`  |
| `long`    | `J`  |
| `float`   | `F`  |
| `double`  | `D`  |
| `short`   | `S`  |
| `byte`    | `B`  |

## String Literals

HdumpPath supports two quote styles with different semantics:

- **Double quotes (`"..."`)** — escape sequences processed: `\n`, `\t`, `\\`, `\"`
- **Single quotes (`'...'`)** — raw strings: backslashes are literal (only `\'` escapes)

Use single quotes for regex patterns to avoid double-escaping:
```
objects[className ~ '.*\.HashMap']        # Raw: backslash preserved for regex
objects[className ~ ".*\\.HashMap"]       # Escaped: need \\\\ for literal backslash
```

## Predicates (Filters)

Predicates filter results using conditions in square brackets.

### Basic Syntax
```
[field op value]
```

### Comparison Operators
| Operator | Description | Example |
|----------|-------------|---------|
| `=` or `==` | Equal | `[class = "java.lang.String"]` |
| `!=` | Not equal | `[class != "java.lang.Object"]` |
| `>` | Greater than | `[shallow > 1000]` |
| `>=` | Greater or equal | `[instanceCount >= 100]` |
| `<` | Less than | `[shallow < 100]` |
| `<=` | Less or equal | `[instanceCount <= 10]` |
| `~` | Regex match | `[name ~ ".*HashMap.*"]` |

### Size Units
Use convenient size suffixes for byte values:
```
[shallow > 1KB]                            # > 1024 bytes
[shallow > 1MB]                            # > 1048576 bytes
[shallow > 1GB]                            # > 1073741824 bytes
```

Supported: `K`, `KB`, `M`, `MB`, `G`, `GB` (case-insensitive)

### Boolean Operators
Combine conditions with `and`, `or`, `not`:
```
[shallow > 100 and shallow < 1000]
[class = "java.lang.String" or class = "java.lang.StringBuilder"]
[not isArray]
[(shallow > 1MB) or (instanceCount > 1000)]
```

### Examples
```
# Large strings
objects/java.lang.String[shallow > 1KB]

# Classes with many instances
classes[instanceCount > 1000]

# GC roots for specific threads
gcroots/THREAD_OBJ[threadSerial > 0]

# Complex filter
objects[shallow > 100 and shallow < 10KB and class ~ "java.util.*"]
```

## Pipeline Operations

Pipeline operators transform query results. Chain them with `|`:

```
objects/java.lang.String | top(10, shallow) | select(class, shallow)
```

### `select(field1, field2 as alias, ...)`
Project specific fields with optional aliases.

```
objects | select(class, shallow)
objects | select(id, shallow as size)
classes | select(name, instanceCount as count)
```

### `top(n, by=field|field, [asc|desc])`
Get top N results sorted by field. Default is descending. Accepts both named (`by=field, asc=true`) and positional (`field, asc`) styles.

```
objects | top(10, shallow)                 # Positional style
objects | top(10, by=shallow)              # Named style (equivalent)
objects | top(10, shallow, asc)            # Bottom 10 by shallow size
objects | top(10, by=shallow, asc=true)    # Bottom 10 (named style)
classes | top(5, instanceCount, desc)      # Top 5 by instance count
```

### `groupBy(field, agg=operation)`
Group by field and aggregate. Default aggregation is `count`.

```
objects | groupBy(class)                   # Count by class
objects | groupBy(class, agg=count)        # Explicit count
objects | groupBy(class, agg=sum)          # Sum shallow sizes by class
objects | groupBy(class, agg=avg)          # Average shallow size by class
objects | groupBy(class, agg=min)          # Min shallow size by class
objects | groupBy(class, agg=max)          # Max shallow size by class
```

**Parameters:**
- `agg` - Aggregation function (default: `count`)
- `value` - Value expression for sum/avg/min/max
- `sortBy` (or `sort`) - Sort results by `key` (grouping key) or `value` (aggregated value)
- `asc` - Sort ascending (default: `false`, descending)

**Aggregation operations:**
- `count` - Count items in group (default)
- `sum` - Sum first numeric field
- `avg` - Average of first numeric field
- `min` - Minimum of first numeric field
- `max` - Maximum of first numeric field

### `count`
Count total results.

```
objects/java.lang.String | count           # Count all strings
objects[shallow > 1MB] | count             # Count large objects
```

### `sum(field)`
Sum numeric field values.

```
objects/java.lang.String | sum(shallow)    # Total string memory
objects | sum(shallow)                     # Total heap usage
```

### `stats(field)`
Calculate statistics: count, sum, min, max, avg.

```
objects/java.lang.String | stats(shallow)
```

Output:
```
| count  | sum       | min | max    | avg    |
|--------|-----------|-----|--------|--------|
| 238750 | 9072500   | 38  | 38     | 38.0   |
```

### `sortBy(field [asc|desc], ...)`
Sort results by one or more fields. Aliases: `sort`, `orderBy`, `order`.
Accepts both bare keywords (`asc`/`desc`) and named param (`asc=true`/`asc=false`).

```
objects | sortBy(shallow desc)             # Sort by size descending
objects | sortBy(shallow, asc=false)       # Same, named param style
classes | sortBy(name asc)                 # Sort alphabetically
objects | sortBy(class asc, shallow desc)  # Multi-field sort
```

### `head(n)`
Take first N results.

```
objects | head(100)                        # First 100 objects
classes | sortBy(name) | head(10)          # First 10 alphabetically
```

### `tail(n)`
Take last N results.

```
objects | sortBy(shallow) | tail(10)       # 10 largest objects
```

### `filter(predicate)`
Filter results mid-pipeline.

```
objects | groupBy(class, agg=count) | filter(count > 100)
classes | filter(instanceCount > 1000 and name ~ "java.util.*")
```

### `distinct(field)`
Get distinct values of a field.

```
objects | distinct(class)                  # Unique class names
gcroots | distinct(type)                   # Unique root types
```

### `retentionPaths()`
Find all paths to GC root for each input object, then merge them at the class level.
Individual object IDs are discarded; identical class-level paths are counted together.

Output columns: `count`, `depth`, `retainedSize`, `path` (class names from GC root to target, joined with ` → `), sorted by `count` descending.

```
# Which class chains retain the most ConcurrentHashMap instances?
objects/java.util.concurrent.ConcurrentHashMap | retentionPaths()

# Top 5 retention paths for large strings
objects/java.lang.String[retained > 1MB] | retentionPaths() | head(5)
```

Aliases: `classPaths()`, `classPathToRoot()`.

### `retainedBreakdown([depth=N])`
Recursively expands the dominator subtrees of input objects and aggregates the size breakdown at
the class level. Depth 0 = direct dominatees; depth 1 = their dominatees; and so on.

Accepts both **object rows** (`objects/…`) and **class rows** (`classes/…`). When fed class rows,
all instances of those classes are found automatically and used as the starting points.

Requires the dominator tree to have been computed (run `dominators()` first, or any query that
triggers computation).

Output columns: `depth`, `className`, `count`, `shallowSize`, `retainedSize`.
Rows are sorted by depth ascending then retainedSize descending.
An ASCII tree is also printed to stderr for quick visual inspection.

```
# What does a large HashMap actually retain, level by level?
objects/java.util.HashMap[retained > 10MB] | retainedBreakdown()

# Same starting from a class query — all HashMap instances
classes/java.util.HashMap | retainedBreakdown()

# Top 5 classes by retained size: drill into each one
classes | top(5, instanceCount) | retainedBreakdown(depth=3)

# Limit to 3 levels and filter significant classes at depth 2
objects/java.util.HashMap[retained > 10MB] | retainedBreakdown(depth=3) | filter(depth = 2) | top(5, retainedSize)
```

Aliases: `breakdown()`, `expandDominators()`.

### `dominators([mode] [, groupBy="class"|"package"] [, minRetained=size])`
Analyse retained memory using the dominator tree.

**Default (no arguments):** top-10 input objects sorted by retained size (≥ 1 MB).

```
objects | dominators()
```

**`groupBy="class"`:** heap-wide retained-size histogram by class, ignoring the input stream.

```
objects | dominators(groupBy="class")
objects | dominators(groupBy="class", minRetained=5MB)
```

Output columns: `className`, `count`, `shallowSize`, `retainedSize`.

**`groupBy="package"`:** same histogram grouped by package name.

```
objects | dominators(groupBy="package")
objects | dominators(groupBy="package", minRetained=10MB)
```

Output columns: `package`, `count`, `shallowSize`, `retainedSize`.

**`"objects"`:** list dominated objects for each input object (expand the tree).

```
objects[retained > 100MB] | dominators("objects")
objects[retained > 100MB] | dominators("objects", minRetained=1MB)
```

**`"tree"`:** print ASCII dominator tree to stderr and return summary rows.

```
objects[retained > 500MB] | dominators("tree")
objects[retained > 500MB] | dominators("tree", minRetained=10MB)
```

**`minRetained`** accepts size suffixes: `1KB`, `10MB`, `1GB`.

## Complete Examples

### Memory Analysis

```
# Top 10 classes by instance count
classes | top(10, instanceCount)

# Memory by class (sum of shallow sizes)
objects | groupBy(class, agg=sum) | top(10, sum)

# Large object analysis
objects[shallow > 1MB] | groupBy(class, agg=count) | sortBy(count desc)

# String memory statistics
objects/java.lang.String | stats(shallow)
```

### GC Root Analysis

```
# GC root distribution
gcroots | groupBy(type, agg=count) | sortBy(count desc)

# Thread roots
gcroots/THREAD_OBJ | select(type, object, threadSerial)

# Count by type
gcroots | groupBy(type) | top(10, count)
```

### Class Hierarchy

```
# Find Map implementations
objects/instanceof/java.util.Map | groupBy(class) | top(10, count)

# Collection analysis
objects/instanceof/java.util.Collection | groupBy(class, agg=sum) | top(10, sum)
```

### Finding Specific Objects

```
# Large arrays (any type)
objects[isArray and shallow > 1MB] | top(10, shallow)

# Large Object arrays specifically
objects/java.lang.Object[][arrayLength > 1000] | top(10, shallow)

# Find what retains large Object arrays
objects/java.lang.Object[][retained > 100MB] | top(5, retained) | pathToRoot

# String contents (if looking for specific strings)
objects/java.lang.String[stringValue ~ ".*ERROR.*"] | head(10)

# Objects by size range
objects[shallow > 1KB and shallow < 1MB] | groupBy(class) | top(10, count)
```

## Query Composition

Build complex queries by combining features:

```
# Complete memory report
objects
  | groupBy(class, agg=count)
  | filter(count > 100)
  | sortBy(count desc)
  | head(20)
  | select(class, count)

# Detailed class analysis
classes[instanceCount > 1000]
  | sortBy(instanceCount desc)
  | head(10)
  | select(name, instanceCount, instanceSize)
```

## Tips and Best Practices

1. **Start broad, then filter**: Begin with `objects` or `classes`, then add predicates.

2. **Use `instanceof` for polymorphism**: When analyzing interfaces or abstract classes.

3. **Combine with `groupBy`**: Group results for meaningful aggregations.

4. **Check `stats` first**: Get an overview before diving into specifics.

5. **Use size units**: `1MB` is more readable than `1048576`.

6. **Pipeline efficiently**: Apply filters early to reduce data volume.

## See Also

- [JfrPath Reference](jfrpath.md) — Query language for JFR event analysis (same shell, similar syntax)
- [Heap Dump Quick Start](hdump-shell-quickstart.md) — Get started in 5 minutes
- [Heap Dump Tutorial](tutorials/hdump-shell-tutorial.md) — Full analysis guide
- [JFR Shell Tutorial](tutorials/jfr-shell-tutorial.md) — JFR analysis tutorial

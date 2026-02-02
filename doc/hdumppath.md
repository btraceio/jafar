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

### `top(n, field, [asc|desc])`
Get top N results sorted by field. Default is descending.

```
objects | top(10, shallow)                 # Top 10 by shallow size (desc)
objects | top(10, shallow, asc)            # Bottom 10 by shallow size
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
Sort results by one or more fields.

```
objects | sortBy(shallow desc)             # Sort by size descending
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
# Large arrays
objects[isArray and shallow > 1MB] | top(10, shallow)

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

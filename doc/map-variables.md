# Map Variables Tutorial

Map variables provide structured data storage in JFR Shell, enabling cleaner organization, configuration management, and custom report structures.

## Table of Contents
- [Overview](#overview)
- [Creating Maps](#creating-maps)
- [Accessing Map Fields](#accessing-map-fields)
- [Nested Maps](#nested-maps)
- [Using Maps in Queries](#using-maps-in-queries)
- [Map Operations](#map-operations)
- [Practical Examples](#practical-examples)
- [Tab Completion](#tab-completion)

## Overview

Map variables use JSON-like syntax and support immutable structured data storage. They're perfect for:
- **Configuration management**: store thresholds, patterns, and settings
- **Custom reports**: build structured analysis results
- **Cleaner organization**: namespace related values
- **Data transformation**: intermediate processing steps

## Creating Maps

### Basic Syntax

Maps use JSON-like syntax with quoted keys and typed values:

```bash
jfr> set config = {"threshold": 1000, "enabled": true, "pattern": ".*Error.*"}
```

### Supported Value Types

**Strings:**
```bash
jfr> set names = {"first": "John", "last": "Doe"}
```

**Numbers** (integers and decimals):
```bash
jfr> set stats = {"count": 42, "ratio": 3.14, "negative": -5}
```

**Booleans:**
```bash
jfr> set flags = {"enabled": true, "debug": false}
```

**Null values:**
```bash
jfr> set optional = {"required": "value", "optional": null}
```

**Empty maps:**
```bash
jfr> set empty = {}
```

## Accessing Map Fields

Use dot notation to access map fields in variable substitutions:

```bash
jfr> set config = {"threshold": 1000, "pattern": ".*Error.*"}
jfr> echo "Threshold: ${config.threshold}"
Threshold: 1000

jfr> echo "Pattern: ${config.pattern}"
Pattern: .*Error.*
```

### Special Properties

**Get map size** (entry count):
```bash
jfr> set config = {"a": 1, "b": 2, "c": 3}
jfr> echo "Config has ${config.size} entries"
Config has 3 entries
```

### Non-existent Fields

Accessing non-existent fields returns an empty string (no error):

```bash
jfr> set config = {"key": "value"}
jfr> echo "Missing: ${config.missing}"
Missing:
```

## Nested Maps

Maps can contain other maps for hierarchical organization:

```bash
# Create nested structure
jfr> set db = {
...>   "primary": {"host": "db1.local", "port": 5432},
...>   "replica": {"host": "db2.local", "port": 5433}
...> }

# Access nested fields with dot notation
jfr> echo "Primary: ${db.primary.host}:${db.primary.port}"
Primary: db1.local:5432

jfr> echo "Replica: ${db.replica.host}:${db.replica.port}"
Replica: db2.local:5433
```

### Deep Nesting

Arbitrary nesting depth is supported:

```bash
jfr> set app = {
...>   "config": {
...>     "db": {
...>       "connection": {
...>         "host": "localhost",
...>         "pool": {"min": 5, "max": 20}
...>       }
...>     }
...>   }
...> }

jfr> echo "Pool max: ${app.config.db.connection.pool.max}"
Pool max: 20
```

## Using Maps in Queries

Map values can be used in JfrPath queries:

```bash
# Configuration-driven filtering
jfr> set config = {"threshold": 1000, "limit": 10}
jfr> show events/jdk.FileRead[bytes>=${config.threshold}] --limit ${config.limit}

# Pattern matching
jfr> set patterns = {"error": ".*Error.*", "warn": ".*Warning.*"}
jfr> show events/jdk.FileRead[path~"${patterns.error}"]

# Complex conditions
jfr> set limits = {"min": 1000, "max": 10000}
jfr> show events/jdk.FileRead[bytes>=${limits.min} and bytes<=${limits.max}]
```

## Map Operations

### Listing Variables

View all variables including maps:

```bash
jfr> vars
Session variables (session #1):
  config = map{threshold=1000, enabled=true, pattern=".*Error.*"}
  db = map{primary={host="db1.local", port=5432}, replica={...}}
```

### Detailed Variable Info

Use `vars --info` to see map structure:

```bash
jfr> vars --info config
Variable: config
Type: map
Size: 3 entries
Structure: map{threshold=1000, enabled=true, pattern=".*Error.*"}
```

### Removing Maps

```bash
jfr> unset config
```

### Global vs Session Scope

**Session scope** (default) - cleared when session closes:
```bash
jfr> set config = {"key": "value"}
```

**Global scope** - persists across all sessions:
```bash
jfr> set --global defaults = {"threshold": 100, "limit": 50}
```

## Practical Examples

### Configuration Management

Store analysis configuration in one place:

```bash
jfr> set analysis = {
...>   "file_io": {"min_bytes": 1000, "top_n": 10},
...>   "threads": {"sample_threshold": 100},
...>   "gc": {"heap_threshold": 1000000000}
...> }

jfr> show events/jdk.FileRead[bytes>=${analysis.file_io.min_bytes}]
...>   | top(${analysis.file_io.top_n}, by=bytes)

jfr> show events/jdk.ExecutionSample
...>   | groupBy(sampledThread/javaName)
...>   | top(10, by=count)[count>${analysis.threads.sample_threshold}]
```

### Environment-Specific Settings

Switch between dev/prod configurations:

```bash
jfr> set dev = {"log_level": "DEBUG", "timeout": 30, "retries": 3}
jfr> set prod = {"log_level": "ERROR", "timeout": 10, "retries": 5}

# Use dev settings
jfr> set env = ${dev}
jfr> echo "Using timeout: ${env.timeout}s with ${env.retries} retries"
```

### Building Custom Reports

Create structured summary data:

```bash
jfr> set reads = events/jdk.FileRead
jfr> set total = events/jdk.FileRead/bytes | sum()

# Build report structure
jfr> set report = {
...>   "total_reads": ${reads.size},
...>   "total_bytes": ${total.sum},
...>   "timestamp": "2024-01-15"
...> }

jfr> echo "Report: ${report.total_reads} reads, ${report.total_bytes} bytes on ${report.timestamp}"
```

### Path Templates

Store reusable path patterns:

```bash
jfr> set paths = {
...>   "logs": "/var/log/.*",
...>   "temp": "/tmp/.*",
...>   "home": "/home/.*"
...> }

jfr> show events/jdk.FileRead[path~"${paths.logs}"]
jfr> show events/jdk.FileWrite[path~"${paths.temp}"]
```

### Multi-Environment Analysis

Compare settings across environments:

```bash
jfr> set environments = {
...>   "dev": {"host": "dev.local", "threads": 10},
...>   "staging": {"host": "staging.local", "threads": 20},
...>   "prod": {"host": "prod.local", "threads": 50}
...> }

jfr> echo "Dev: ${environments.dev.host} (${environments.dev.threads} threads)"
jfr> echo "Prod: ${environments.prod.host} (${environments.prod.threads} threads)"
```

## Tab Completion

The shell provides intelligent tab completion for map fields:

**Variable names:**
```bash
jfr> echo ${config⇥
```
Shows: `config`, `configBackup`, etc.

**Map field names:**
```bash
jfr> echo ${config.⇥
```
Shows: `threshold`, `enabled`, `pattern`, `size`

**Nested field names:**
```bash
jfr> echo ${db.primary.⇥
```
Shows: `host`, `port`, `size`

**In set command:**
```bash
jfr> set config = ⇥
```
Shows: `{` (with description: "map literal - {\"key\": value, ...}")

**Map literal patterns:**
```bash
jfr> set config = {⇥
```
Shows example patterns:
- `{"key": "value"}` - simple map with string value
- `{"count": 0}` - map with numeric value
- `{"enabled": true}` - map with boolean value
- `{}` - empty map

## Best Practices

1. **Use descriptive keys**: `{"max_bytes": 1000}` is better than `{"mb": 1000}`

2. **Organize hierarchically**: Group related settings in nested maps
   ```bash
   set config = {
     "thresholds": {"bytes": 1000, "duration": 5000},
     "limits": {"top_n": 10, "max_results": 100}
   }
   ```

3. **Keep maps immutable**: Create new maps instead of trying to modify existing ones

4. **Use global scope for shared config**: Put reusable configuration in global scope
   ```bash
   set --global defaults = {"threshold": 100, "limit": 50}
   ```

5. **Document complex structures**: Use comments in scripts
   ```bash
   # Analysis configuration
   # thresholds: filtering criteria
   # limits: result set constraints
   set config = {"thresholds": {...}, "limits": {...}}
   ```

## Limitations

- **Immutable**: Maps cannot be modified after creation; create a new map instead
- **JSON-like syntax only**: Keys must be quoted strings
- **No array literals**: Use nested maps for structure (arrays coming in Phase 2)
- **No expressions in values**: Values must be literals (expression interpolation coming in Phase 2)

## What's Next?

Current implementation is **Phase 1** of map variables. Future phases may include:
- **Phase 2**: Expression interpolation in map values (`{"total": ${reads.size}}`)
- **Phase 3**: Map operations (merge, filter, transform) and pipeline integration

See the [implementation plan](../.claude/plans/piped-plotting-thimble.md) for details.

## See Also

- [Scripting Guide](jfr-shell-scripting.md#variables) - Using maps in scripts
- [Usage Guide](jfr_shell_usage.md) - Complete command reference
- [JfrPath Reference](jfrpath.md) - Query language syntax

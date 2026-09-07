---
name: jfrpath
description: Syntax reference for the query languages behind jfr_query, hdump_query, pprof_query and otlp_query — JfrPath, HdumpPath and SamplesPath. Consult before composing any non-trivial query, and whenever a query returns a parse error, so the syntax is right on the first attempt instead of after three failures.
---

# Query language reference

Four query tools, three languages. All are path-based, not SQL: you address a root, filter
it in brackets, and pipe it through operators.

```
<root>[/<segment>][<filter>] ( | <operator> )*
```

## JfrPath — `jfr_query`

**Roots**: `events/<type>`, `metadata/<type>`, `chunks`, `constants` (alias `cp`).

### Filters go in square brackets

```
events/jdk.FileRead[bytes>1000]
events/jdk.FileRead[path~"/tmp/.*"]
events/jdk.FileRead[bytes>1000 and path~"/tmp/.*"]
```

Operators: `=` `!=` `>` `>=` `<` `<=` `~` (regex). Combine with `and`, `or`, `not` and
parentheses. Functions usable inside a filter: `contains`, `startsWith`, `endsWith`,
`matches(path,"re"[,"i"])`, `exists`, `empty`, `between(path,a,b)`, `len(path)`, and the
time predicates `before`, `after`, `on`.

Filters can be interleaved at any segment:

```
events/jdk.GCHeapSummary[when/when="After GC"]/heapSpace[committedSize>1000000]/reservedSize
```

For list fields, choose the match mode — `any:` (default), `all:`, `none:`:

```
events/jdk.ExecutionSample[none:stackTrace/frames[matches(method/name/string, ".*Test.*")]]
```

### Numeric literals and units

Size suffixes work and are binary: `K`/`KB` = 1024, `M`/`MB` = 1024², `G`/`GB` = 1024³.

```
events/jdk.FileRead[bytes>1MB]
```

Duration suffixes `ns`, `us`, `ms`, `s` are also accepted and convert to nanoseconds, which
is how JFR stores durations:

```
events/jdk.GCPhasePause[duration>10ms]
events/jdk.JavaMonitorEnter[duration>1ms] | count()
```

A bare number in a duration field is nanoseconds: `[duration>10000000]` is the same 10 ms.
There is deliberately no `m` suffix for minutes, because `M` already means mebibytes.

### Pipeline operators

| Group | Operators |
|---|---|
| Aggregate (terminal) | `count()`, `sum([path])`, `stats([path])`, `quantiles(q…[, path=])`, `sketch([path])`, `timerange([path][, duration=][, format=])`, `flamegraph([direction=])`, `stackprofile([direction=][, buckets=][, minPct=])` |
| Group and order | `groupBy(key[, agg=count\|sum\|avg\|min\|max][, value=path][, sortBy=key\|value][, asc=])`, `sortBy(field[, asc=])`, `top(n[, by=path][, asc=])`, `head(n)`, `tail(n)`, `distinct()` |
| Shape | `select(...)`, `filter([predicate])` |
| Correlate | `decorateByTime(...)`, `decorateByKey(...)` |
| Value transforms | `len`, `uppercase`, `lowercase`, `trim`, `abs`, `round`, `floor`, `ceil`, `contains`, `replace`, `formatDuration`, `asDateTime` |
| Maps | `toMap(key, value)`, `merge(...)` |

Two rules that cause most failures:

1. **`sortBy` and `top` default to descending.** Pass `asc=true` for ascending — this matters
   for time series, where `sortBy(startTime)` gives you the recording backwards.
2. **`filter()` takes a bracketed predicate**, unlike root filters:
   `groupBy(path, agg=sum, value=bytes) | filter([sum>1048576])`.

Terminal aggregations consume the stream and cannot be chained with each other.

### select()

Supports aliases, arithmetic, string concatenation, `"${expr}"` templates, and the
scope functions `if()`, `upper()`, `lower()`, `substring()`, `length()`, `coalesce()`,
`asDateTime()`, `truncate(field,"second|minute|hour|day|week|month")`, `formatDuration()`.

```
events/jdk.FileRead | select(path, formatDuration(duration) as dur) | sortBy(duration) | top(10)
```

### Correlation

```
decorateByTime(<type>, fields=f1,f2 [, threadPath=] [, decoratorThreadPath=])
decorateByKey(<type>, key=<path>, decoratorKey=<path>, fields=f1,f2)
```

`decorateByTime` matches events overlapping in time **on the same thread** (thread path
defaults to `eventThread/javaThreadId`). `decorateByKey` joins on a shared correlation id —
prefer it when one exists, as it is exact and cheaper. Decorated fields are read with the
`$decorator.` prefix and work in `groupBy`, `select` and filters.

## HdumpPath — `hdump_query`

**Roots**: `objects`, `classes`, `gcroots`, `clusters`, `duplicates`, `ages`.

Type specs accept exact names, globs (`java.util.*`), `instanceof/` for subclass matching,
and array forms (`int[]` or `[I`). Size units `K/KB/M/MB/G/GB` work in predicates.

Sorting takes a direction word: `sortBy(retained desc)`, `sortBy(name asc)`, and multiple
fields: `sortBy(class asc, shallow desc)`.

Analysis operators unique to heap dumps: `pathToRoot()`, `retentionPaths()`, `dominators()`,
`retainedBreakdown()`, `checkLeaks(detector=…)`, `waste()`, `cacheStats()`, `threadOwner()`,
`dominatedSize()`, `estimateAge()`, `whatif()`, and the cross-session `join(session=…[,
root=…][, by=…])`.

```
classes | sortBy(retained desc) | top(20)
objects/java.util.HashMap | waste() | filter(loadFactor < 0.1) | top(20)
clusters | sortBy(score desc) | top(10)
```

## SamplesPath — `pprof_query` and `otlp_query`

pprof and OTLP share one grammar with a single root, `samples`.

Fields: one per profile sample type (`cpu`, `alloc_objects`, …), `stackTrace` as a leaf-first
list addressable by index (`stackTrace/0/name`), plus label keys such as `thread`.

Operators: `count`, `top`, `groupBy`, `stats`, `head`, `tail`, `filter`/`where`, `select`,
`sortBy`/`sort`/`orderby`, `stackprofile`, `distinct`/`unique`. There is **no** `join` and no
cross-session operator for these formats.

## When a query fails

1. Read the error position — the parser reports `[at N]`, an index into your query string.
2. Check bracket versus parenthesis: root filters use `[...]`, the `filter()` operator takes
   `filter([...])`.
3. Ask the server rather than guessing: `jfr_help topic=filters|pipeline|functions|examples`,
   `hdump_help`, `pprof_help`, `otlp_help`.
4. Verify the field exists before blaming syntax: `jfr_list_types filter=<name>` then
   `jfr_query query="metadata/<type>"` to see the field names.

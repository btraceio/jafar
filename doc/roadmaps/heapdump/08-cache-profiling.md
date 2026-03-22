# Cache Efficiency Profiling

## Goal

Detect, analyze, and report on caching data structures in the heap. Caches are the #1 source of "intended but excessive" memory usage — they're not leaks, they're *misconfigurations*.

A cache holding 500MB of stale session data needs different advice than a memory leak. This feature identifies caches and provides actionable metrics.

## UX

**Pipeline operator** — `cacheStats()`.

Per-object enrichment: takes a stream of cache objects and adds cache-specific analysis columns.

## Syntax

```
# Analyze Guava caches
objects/instanceof/com.google.common.cache.LocalCache | cacheStats() | sortBy(retained)

# Analyze Caffeine caches
objects/instanceof/com.github.benmanes.caffeine.cache.BoundedLocalCache | cacheStats() | sortBy(retained)

# Find LinkedHashMap used as LRU cache (accessOrder=true)
objects/java.util.LinkedHashMap | cacheStats() | filter(isLruMode = true) | sortBy(retained)

# Find caches with high cost-per-entry
objects/instanceof/com.google.common.cache.LocalCache | cacheStats() | sortBy(costPerEntry) | top(10)

# Find oversized caches (few entries, lots of memory)
objects/instanceof/com.google.common.cache.LocalCache | cacheStats() | filter(entryCount < 10 and retained > 10MB) | top(10)

# Aggregate: total memory in caches
objects/instanceof/com.google.common.cache.LocalCache | cacheStats() | groupBy(class) | select(class, count, totalRetained, avgCostPerEntry)
```

## Design

### Enrichment columns added by `cacheStats()`

| Column | Type | Description |
|--------|------|-------------|
| `entryCount` | int | Number of entries in the cache |
| `maxSize` | int | Configured maximum size (-1 if unbounded) |
| `fillRatio` | double | `entryCount / maxSize` |
| `costPerEntry` | long | `retained / entryCount` (bytes per entry) |
| `keyClass` | String | Dominant key class |
| `valueClass` | String | Dominant value class |
| `isLruMode` | boolean | Whether the cache uses LRU eviction (LinkedHashMap: `accessOrder=true`) |
| `avgValueSize` | long | Average retained size per value |

### Supported cache implementations

| Cache type | Detection | Key fields |
|------------|-----------|------------|
| Guava `LocalCache` | Class hierarchy | `segments[].count`, `segments[].table.length` |
| Caffeine `BoundedLocalCache` | Class hierarchy | `data.length`, `maximum` |
| `LinkedHashMap` (LRU) | `accessOrder == true` | `table.length`, `size` |
| `ConcurrentHashMap` (as cache) | Heuristic: annotated field name or large size | `table.length`, size via `mappingCount()` |
| `WeakHashMap` | Class identity | `table.length`, `size` |

### Heuristics for cache detection

Not every `ConcurrentHashMap` is a cache. Heuristics for cache identification:
1. Class is a known cache implementation (Guava, Caffeine) — definite
2. `LinkedHashMap` with `accessOrder=true` — definite (LRU pattern)
3. `WeakHashMap` — likely cache (weak keys = cache semantics)
4. `ConcurrentHashMap` — only if explicitly targeted by the user in the query (no automatic detection)

### Cost analysis

`costPerEntry` = total retained size of the cache / number of entries. This metric reveals:
- **High cost, few entries**: cache holding large objects (e.g., serialized responses, parsed documents)
- **Low cost, many entries**: cache holding small objects efficiently
- **Zero entries, nonzero retained**: empty cache with allocated backing structure (pure waste, overlaps with #2)

## Key decisions

| Decision | Options | Recommendation |
|----------|---------|----------------|
| Cache detection scope | Known implementations only vs heuristic detection | Known implementations + user-directed queries for unknown types |
| ConcurrentHashMap handling | Auto-detect as cache vs user-explicit only | User-explicit only — too many false positives otherwise |
| Value sampling | Analyze all values vs sample | Sample for large caches (>10k entries); full scan for small |
| Cross-reference with #2 | Integrate with waste() vs keep separate | Keep separate; both can run independently, user combines with pipeline |
| Eviction policy detection | Report if possible vs skip | Report when detectable (LRU via accessOrder, Guava via configuration fields) |

## Complexity

**Medium.** Same pattern as #2 (Collection Waste) — read internal fields of known cache classes. The field access mechanism already exists. Main work is cataloging cache class layouts.

## Dependencies

- **HPROF field access API** — reading instance fields (already exists)
- **`instanceof` filtering** — HdumpPath `objects/instanceof/...` (already exists)
- **Retained size computation** — already exists; used for `costPerEntry`

## Verification

- **Synthetic test**: create HPROF with Guava cache holding known entries, verify `entryCount`, `costPerEntry` accuracy
- **LinkedHashMap LRU**: create LinkedHashMap with `accessOrder=true` and `accessOrder=false`, verify `isLruMode` detection
- **Empty cache**: verify empty cache is detected with `entryCount=0` and waste is flagged
- **Large cache**: test with 100k-entry cache, verify sampling produces accurate estimates
- **Unknown type**: verify `cacheStats()` on a non-cache object passes through gracefully (null enrichment columns)

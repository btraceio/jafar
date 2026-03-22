# Collection Right-Sizing & Waste Analysis

**Status:** Implemented (core `waste()` operator)

## Goal

Analyze the internal structure of standard JDK collections to detect wasted memory caused by over-allocation, empty backing arrays, and poor sizing choices.

In production heaps, 20-40% of memory is often wasted in over-allocated collections. This is directly actionable — developers can pass initial capacity hints or switch to specialized collections.

Specific waste patterns:
- **Over-allocated HashMaps** — capacity 1024 but only 3 entries (wastes ~8KB per instance)
- **Empty collections** — `ArrayList` with default backing array but zero elements
- **Boxed primitive waste** — `Long[]` where `long[]` would suffice
- **Sparse arrays** — large `Object[]` arrays that are mostly null
- **String encoding waste** — Latin-1 strings stored in UTF-16 byte arrays (pre-compact-strings JDKs)

## UX

**Pipeline operator** — `waste()`.

Pure per-object enrichment: takes a stream of collection objects and adds waste-related columns.

## Syntax

```
# Analyze all HashMap instances
objects/java.util.HashMap | waste() | sortBy(wastedBytes desc)

# Find the worst offenders — large capacity, few entries
objects/java.util.HashMap | waste() | filter(loadFactor < 0.1) | top(20)

# Analyze ArrayList backing array waste
objects/java.util.ArrayList | waste() | sortBy(wastedBytes desc)

# Aggregate waste by collection class
objects/java.util.HashMap | waste() | groupBy(class, agg=sum, value=wastedBytes)

# Find HashMaps with significant waste
objects/java.util.HashMap | waste() | filter(wastedBytes > 1024) | top(50)
```

## Design

### Enrichment columns added by `waste()`

| Column | Type | Description |
|--------|------|-------------|
| `capacity` | int | Allocated capacity (backing array length) |
| `size` | int | Actual element count |
| `loadFactor` | double | `size / capacity` (0.0 to 1.0) |
| `wastedBytes` | long | Estimated bytes wasted due to over-allocation |
| `wasteType` | String | Category: `overCapacity`, `emptyDefault`, `normal` |

### Collection introspection rules

Each JDK collection type requires specific field-reading logic:

| Collection | Capacity field | Size field | Notes |
|------------|---------------|------------|-------|
| `HashMap` | `table.length` | `size` | Capacity is always power-of-2 |
| `LinkedHashMap` | `table.length` | `size` | Same as HashMap |
| `ConcurrentHashMap` | `table.length` | `baseCount` + `counterCells` | Sums striped counters for accurate size |
| `ArrayList` | `elementData.length` | `size` | Default capacity = 10 |
| `ArrayDeque` | `elements.length` | computed | Circular buffer |
| `HashSet` | delegates to internal `HashMap` | `map.size` | Wrapper around HashMap |
| `TreeMap` | N/A | `size` | Tree-based, no capacity waste |

### Waste calculation

```
wastedBytes = (capacity - size) * entryOverhead
```

Where `entryOverhead` depends on the collection type:
- `HashMap`: ~32 bytes per `Node` (key ref + value ref + hash + next ref + object header)
- `ArrayList`: pointer size (4 or 8 bytes) per unused slot
- `HashSet`: same as HashMap (wraps HashMap internally)

### Implementation approach

1. When `waste()` operator encounters an object, check its class against the known collection types
2. Read the internal fields using the HPROF field access API (same mechanism leak detectors use)
3. Compute waste metrics and append as enrichment columns
4. Non-collection objects pass through with null waste columns

## Key decisions

| Decision | Options | Recommendation |
|----------|---------|----------------|
| Scope | JDK collections only vs also Guava/Eclipse Collections | Start with JDK; extensible via plugin for third-party |
| Unknown collections | Skip silently vs warn | Skip silently; `wasteType = "unknown"` for unsupported types |
| JDK version variance | Hard-code field names vs discover dynamically | Hard-code for JDK 8-21 layouts; the field names are stable |
| Null backing arrays | Treat as zero capacity or skip | Treat as zero capacity, zero waste (freshly constructed) |
| Aggregate mode | Per-instance only vs optional `groupBy(class)` summary | Per-instance enrichment; aggregation via existing `groupBy` operator |

## Complexity

**Medium.** Requires reading internal fields of known JDK collection classes. The field access mechanism already exists in the HPROF parser (used by leak detectors like `GrowingCollectionsDetector`). Main work is cataloging collection layouts across JDK versions.

## Dependencies

- **HPROF field access API** — ability to read instance fields by name from heap objects (already exists)
- **`instanceof` filtering** — HdumpPath `objects/instanceof/...` syntax (already exists)
- **Existing leak detectors** — `GrowingCollectionsDetector` already reads collection internals; reuse its field access patterns

## Verification

- **Unit test**: create HPROF with known HashMap (capacity=1024, size=3) and ArrayList (capacity=10, size=0), verify exact waste calculations
- **JDK version matrix**: test against HPROF files from JDK 8, 11, 17, 21 to verify field layout assumptions
- **Edge cases**: null backing array, concurrent modification during dump (partially initialized), subclassed collections
- **Accuracy**: compare waste numbers against Eclipse MAT's collection analysis for the same heap

## Implementation Notes

The `waste()` operator is implemented in `CollectionWasteAnalyzer.java` as a zero-arg pipeline operator.
Supported collection types: HashMap, LinkedHashMap, HashSet, LinkedHashSet, ArrayList,
ConcurrentHashMap, ArrayDeque. Waste is calculated as `(capacity - size) * refSize` where refSize
is the heap dump's ID size (4 or 8 bytes).

Future work:
- Boxed primitive waste detection
- Sparse array analysis
- String encoding waste (pre-compact-strings JDKs)
- Third-party collection support (Guava, Eclipse Collections)

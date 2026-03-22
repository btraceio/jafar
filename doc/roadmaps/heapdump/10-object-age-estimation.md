# Temporal Object Age Estimation

## Goal

Estimate object "age" from a single heap snapshot using structural signals, without requiring a second dump. This enables leak detection heuristics for production systems where taking multiple dumps is costly or impossible.

Research (e.g., "Cork: Dynamic Memory Leak Detection for Garbage-Collected Languages", Jump & McKinley, POPL 2007) shows that age-based heuristics from structural signals are surprisingly accurate.

## UX

**New query root** — `ages` — and an **enrichment operator** — `estimateAge()`.

The root provides a pre-computed age-classified view of the heap. The operator enriches individual object rows.

## Syntax

```
# Age distribution summary
ages | sortBy(estimatedAge)

# Per-class age breakdown
ages | groupBy(class) | select(class, avgAge, medianAge, count)

# Enrich specific objects with age estimates
objects/instanceof/java.util.HashMap | estimateAge() | sortBy(estimatedAge)

# Find suspiciously old short-lived types (potential leaks)
objects/java.lang.ref.WeakReference | estimateAge() | filter(ageBucket = "tenured") | top(20)

# Find young objects with high retained size (recent accumulation)
objects[retained > 1MB] | estimateAge() | filter(ageBucket = "young") | sortBy(retained)
```

## Design

### Age estimation signals

Each signal contributes to an age score (higher = older):

| Signal | Weight | Rationale |
|--------|--------|-----------|
| Dominator depth | Medium | Objects dominated by old-generation roots (static fields, singletons) are likely old |
| Inbound reference count | Medium | Objects with many inbound references have survived multiple GC cycles (reference accumulation) |
| Dominator class age proxy | High | If the immediate dominator is a known long-lived structure (static field, cache, ClassLoader), the object is likely old |
| Position in dominator tree | Low | Objects closer to the dominator tree root tend to be older |
| GC root type | High | STICKY_CLASS roots → permanent; THREAD_OBJ → request-scoped (shorter-lived); static fields → long-lived |

### Age buckets

| Bucket | Score range | Interpretation |
|--------|-------------|----------------|
| `ephemeral` | 0-25 | Recently allocated, expected to be collected soon |
| `medium` | 26-50 | Survived a few GC cycles, typical for request-scoped data |
| `tenured` | 51-75 | Long-lived, likely cached or accumulated |
| `permanent` | 76-100 | Effectively permanent: static fields, ClassLoader-held, singletons |

### Enrichment columns

| Column | Type | Description |
|--------|------|-------------|
| `estimatedAge` | int | Age score 0-100 |
| `ageBucket` | String | "ephemeral", "medium", "tenured", "permanent" |
| `ageSignals` | String | Debug: which signals contributed (e.g., "dominator:static,refs:47") |

### Algorithm

```
for each object:
    score = 0

    // Signal 1: dominator chain analysis
    if dominated by static field root → score += 30
    if dominated by THREAD_OBJ root → score += 5
    if dominated by ClassLoader → score += 35

    // Signal 2: inbound reference count
    refCount = inboundReferenceCount(object)
    score += min(25, refCount * 2)  // cap at 25

    // Signal 3: dominator depth (closer to root = older)
    depth = dominatorDepth(object)
    maxDepth = maxDominatorDepth()
    score += (1 - depth/maxDepth) * 10

    return clamp(score, 0, 100)
```

### Pre-computation

The age estimation requires a global graph pass to compute inbound reference counts. This should be:
1. Computed lazily on first `ages` query or `estimateAge()` use
2. Cached in the session for reuse
3. Implemented as a single pass over the reference graph: O(edges)

### Calibration

The signal weights and bucket boundaries need calibration against known heaps:
- **Known-leaky heap**: objects identified by diff (#1) as leaked should score "tenured" or "permanent"
- **Known-healthy heap**: short-lived request objects should score "ephemeral" or "medium"
- **Mixed heap**: verify age distribution looks bimodal (young ephemeral + old permanent, few in between)

## Key decisions

| Decision | Options | Recommendation |
|----------|---------|----------------|
| Scoring model | Simple weighted sum vs ML-based | Simple weighted sum; ML requires training data we don't have yet |
| Weight calibration | Hard-coded vs configurable | Hard-coded defaults with `estimateAge(weights=...)` override |
| Inbound reference counting | Exact vs sampled | Exact for heaps <5M objects; sampled for larger |
| GC generation metadata | Use HPROF GC metadata if available vs ignore | Use if available (bonus signal), don't depend on it |
| Cross-validation with #1 | Use diff results to validate age estimates | Recommended for calibration during development, not a runtime dependency |

## Complexity

**Medium.** The dominator tree is already computed. The main new work is the inbound reference count pass (one traversal of the edge list) and the scoring function. The scoring weights need empirical calibration.

## Dependencies

- **Dominator tree** — already computed; provides dominator chain and depth
- **GC root type information** — already available
- **Object graph edge access** — needed for inbound reference counting (exists in the parser)
- **Optional**: #1 (Heap Diff) — useful for calibrating age estimates but not a build dependency

## Verification

- **Static field test**: objects reachable only through static fields should score "permanent"
- **Thread-local test**: objects reachable only through THREAD_OBJ should score lower than static-rooted objects
- **Reference count test**: create objects with known inbound reference counts, verify score increases with count
- **Calibration test**: use heap diff (#1) on two snapshots to identify actually-old objects, compare with age estimates from a single snapshot
- **Distribution test**: verify age scores across a production heap form a reasonable distribution (not all 0 or all 100)

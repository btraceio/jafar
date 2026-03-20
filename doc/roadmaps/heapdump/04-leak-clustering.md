# Automatic Leak Cluster Detection via Graph Analysis

**Status: IMPLEMENTED**

## Goal

Detect unknown memory leak patterns by analyzing the object reference graph structure, rather than relying on hand-written pattern detectors.

The current 6 leak detectors (`ClassLoaderLeakDetector`, `DuplicateStringsDetector`, `FinalizerQueueDetector`, `GrowingCollectionsDetector`, `ListenerLeakDetector`, `ThreadLocalLeakDetector`) only find *known* patterns. Graph-based detection finds *unknown* patterns — the leaks you didn't anticipate.

The approach: identify densely-connected subgraphs with high retained size but weak external anchoring (few GC root paths holding disproportionate memory).

## UX

**New query root** — `clusters`.

Produces a collection of detected leak clusters, not a transformation of an existing stream.

## Syntax

```
# List all detected clusters ranked by suspiciousness
clusters | sortBy(score)

# Show the top leak suspects with details
clusters | top(10)

# Filter for large clusters only
clusters | filter(retainedSize > 10MB) | sortBy(score)

# Drill into a specific cluster's objects
clusters[id = 3] | objects | sortBy(retained)

# Show clusters anchored by specific root types
clusters | filter(anchorType = "THREAD_OBJ") | sortBy(retainedSize)
```

## Design

### Cluster detection algorithm

Use a modified **label-propagation** algorithm (preferred over Louvain for streaming compatibility):

1. **Seed phase**: assign initial labels based on dominator tree structure — objects with the same immediate dominator get the same initial label
2. **Propagation phase**: iteratively propagate labels through the reference graph; each node adopts the most frequent label among its neighbors
3. **Convergence**: stop when labels stabilize (typically 5-15 iterations)
4. **Scoring phase**: for each cluster, compute leak score

### Leak score formula

```
leakScore = retainedSize / rootPathCount
```

Where:
- `retainedSize` = total retained memory of all objects in the cluster
- `rootPathCount` = number of distinct GC root paths reaching the cluster

Interpretation: fewer roots holding more memory = more suspicious (one root holding 500MB is more suspicious than 100 roots holding 500MB, since the latter is likely shared/intended).

### Cluster fields

| Field | Type | Description |
|-------|------|-------------|
| `id` | int | Cluster identifier |
| `objectCount` | int | Number of objects in the cluster |
| `retainedSize` | long | Total retained size of the cluster |
| `rootPathCount` | int | Number of distinct GC root paths |
| `score` | double | Leak suspiciousness score |
| `dominantClass` | String | Most common class in the cluster |
| `anchorType` | String | GC root type of the primary anchor |
| `anchorObject` | String | Description of the anchoring root object |

### Scaling strategy

Graph algorithms on 10M+ node heaps need careful handling:

- **Sampling**: for very large heaps (>5M objects), sample a representative subgraph rather than processing the full graph
- **Streaming labels**: process in chunks aligned with the dominator tree to keep memory bounded
- **Early pruning**: skip clusters below a minimum retained size threshold (default: 1MB)
- **Iteration cap**: limit label propagation to 20 iterations maximum

## Key decisions

| Decision | Options | Recommendation |
|----------|---------|----------------|
| Algorithm | Label propagation vs Louvain vs connected components | Label propagation — good balance of quality and streaming compatibility |
| Minimum cluster size | Fixed threshold vs percentage of heap | Fixed: 1MB default, configurable via `clusters(minSize=...)` |
| Pre-computation | On-demand vs at session open | On-demand (first `clusters` query triggers computation, then cached) |
| Dominator dependency | Require dominator tree vs independent | Require dominator tree (already computed for retained sizes) |
| Cluster drill-down | Separate query vs inline expansion | `clusters[id=N] | objects` syntax for drill-down |

## Complexity

**High.** Graph algorithms on large heaps need careful memory management and may require sampling for production-scale dumps. The dominator tree (already computed) helps with seeding but the label propagation is additional work.

## Dependencies

- **Dominator tree computation** — already exists in hdump-parser; required for seeding and scoring
- **GC root path analysis** — required for `rootPathCount` scoring; the 3-pane GC root browser already provides root path traversal
- **Retained size computation** — already exists; used in scoring

## Verification

- **Synthetic test**: create an HPROF with a known leak pattern (e.g., growing list holding large objects from a single static root), verify it's detected as a high-score cluster
- **False positive check**: analyze a healthy heap (no leaks), verify no clusters score above threshold
- **Scaling test**: run on a 1M+ object heap and verify memory usage stays bounded and runtime is acceptable (<30 seconds)
- **Comparison**: run existing 6 detectors and cluster detection on the same heap; verify clusters subsume known detector findings

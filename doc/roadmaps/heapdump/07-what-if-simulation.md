# Memory Pressure What-If Simulation

## Goal

Simulate the effect of removing specific GC roots or objects to answer prioritization questions:
- "If I fix the ThreadLocal leak, how much memory would be freed?"
- "What if I made this cache weak-referenced?"
- "What's the maximum I could free by fixing the top-3 leak suspects?"

Current tools show retained size per individual object, but don't account for *overlap* between multiple fixes. If leak A and leak B share a large subgraph, fixing both doesn't free `retainedA + retainedB` — it frees less.

## UX

**Command wrapper** — `whatif`.

A scoped analysis command that wraps a query, conceptually modifies the graph, and reports the impact.

## Syntax

```
# What if we removed all instances of a specific class?
whatif remove objects/com.foo.LeakyCache | stats

# What if we removed a specific GC root?
whatif remove gcroots/THREAD_OBJ[threadName = "leaky-worker"] | stats

# What if we made a cache weak-referenced?
whatif weaken objects/instanceof/java.util.concurrent.ConcurrentHashMap[retained > 50MB] | stats

# Simulate removing multiple roots and see combined effect
whatif remove gcroots/THREAD_OBJ[threadName ~ "pool-.*"] | stats

# Compare individual vs combined impact
whatif remove objects/com.foo.CacheA | stats
whatif remove objects/com.foo.CacheB | stats
whatif remove objects/com.foo.CacheA, objects/com.foo.CacheB | stats
```

## Design

### Simulation modes

| Mode | Semantics | Use case |
|------|-----------|----------|
| `remove` | Delete the specified objects/roots from the graph, recompute reachability | "Fix the leak" |
| `weaken` | Convert strong references to/from these objects to weak references | "Make this cache soft/weak" |

### `stats` output

| Field | Type | Description |
|-------|------|-------------|
| `freedBytes` | long | Total bytes that would become unreachable |
| `freedObjects` | int | Number of objects that would become unreachable |
| `freedPct` | double | Percentage of total heap freed |
| `remainingRetained` | long | Heap retained size after simulation |
| `overlapBytes` | long | Bytes shared with other removal targets (multi-target only) |

### Algorithm: incremental reachability

For `remove` mode:
1. Identify the set of objects/roots to remove (evaluate the inner query)
2. Mark them as "removed" in a shadow copy of the root set
3. Run a reachability analysis from the remaining roots
4. Objects reachable in the original graph but not in the modified graph → freed
5. `freedBytes = sum(shallowSize)` of freed objects

For `weaken` mode:
1. Identify references to/from the target objects
2. Mark those references as weak in a shadow reference model
3. Run reachability analysis treating weak references as non-retaining
4. Objects that lose all strong paths → freed

### Optimization: avoid full recomputation

Full reachability from scratch is expensive. Optimization options:
- **Dominator tree shortcut**: if the removed objects form a subtree in the dominator tree, the freed memory is exactly their retained size (no recomputation needed)
- **Incremental approach**: only recheck objects that were dominated by the removed set
- **Overlap detection**: for multi-target removal, check if targets share dominator subtrees

For single-target removal where the target is a dominator tree subtree, this reduces to a simple retained-size lookup (already computed). The full reachability recomputation is only needed for multi-target removal with overlapping subgraphs.

## Key decisions

| Decision | Options | Recommendation |
|----------|---------|----------------|
| Simulation approach | Full reachability recomputation vs dominator tree shortcut | Dominator shortcut for single targets; full recompute for multi-target with overlap |
| Weak reference modeling | Simple (ignore weak refs) vs full (ReferenceQueue, SoftRef semantics) | Simple — treat weak/soft refs as non-retaining |
| Multi-target syntax | Comma-separated vs repeated `whatif` | Both: comma for combined, repeated for individual comparison |
| Result persistence | Ephemeral vs stored for comparison | Ephemeral by default; store via variable assignment `$result = whatif ...` |
| Graph mutation | In-place vs shadow copy | Shadow copy — never mutate the real session state |

## Complexity

**Medium-High.** Single-target removal is straightforward (dominator tree retained size). Multi-target with overlap detection and full reachability recomputation is more complex.

## Dependencies

- **Dominator tree** — already computed; provides retained size and subtree structure
- **Reachability analysis** — exists in the parser for GC root traversal
- **HdumpPath query evaluation** — the inner query (e.g., `objects/com.foo.CacheA`) must be evaluable to identify targets

## Verification

- **Single target**: remove an object whose retained size is known, verify `freedBytes` matches retained size
- **Multi-target with overlap**: remove two objects that share a dominated subgraph, verify `freedBytes < retainedA + retainedB`
- **Multi-target disjoint**: remove two objects with no shared subgraph, verify `freedBytes == retainedA + retainedB`
- **Weaken mode**: convert a strong cache reference to weak, verify the cached objects become freed
- **No mutation**: verify the original session state is unchanged after simulation

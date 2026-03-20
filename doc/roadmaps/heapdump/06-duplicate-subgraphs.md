# Deep Structure Fingerprinting (Duplicate Subgraph Detection)

## Goal

Generalize duplicate-string detection to arbitrary object subgraphs. Find structurally identical subtrees in the heap — e.g., 10,000 identical `Config` objects with the same field values — and estimate memory savings from deduplication or flyweight patterns.

Duplicate strings are the tip of the iceberg. Real-world apps duplicate entire configuration objects, DTOs, parsed XML nodes, compiled regex patterns, etc.

## UX

**New query root** — `duplicates`.

Produces groups of structurally identical subtrees, not a transformation of an input stream.

## Syntax

```
# Find all duplicate subgraphs, ranked by wasted memory
duplicates | sortBy(wastedBytes)

# Limit fingerprint depth (default: 3)
duplicates(depth=5) | sortBy(wastedBytes)

# Filter for specific class
duplicates | filter(rootClass = "com.example.Config") | top(10)

# Show how many copies exist
duplicates | select(rootClass, copies, uniqueSize, wastedBytes)

# Drill into a specific duplicate group
duplicates[id = 7] | objects | top(5)
```

## Design

### Subgraph fingerprinting algorithm

1. **Tree extraction**: for each object, extract its subtree up to `depth` levels following non-null reference fields
2. **Canonical hashing**: compute a hash of the subtree structure:
   - Include: class name, field names, field types, primitive field values
   - Exclude: object identity (address/ID), reference field target identity
   - For reference fields: recursively hash the referenced subtree (up to depth limit)
3. **Grouping**: group objects with identical fingerprint hashes
4. **Dedup estimation**: for each group, `wastedBytes = (copies - 1) * shallowSizeOfSubtree`

### Cycle handling

Object graphs can have cycles. The depth limit naturally prevents infinite recursion, but cycles within the depth window need handling:
- Track visited objects during hash computation
- On revisit within the same subtree, include a "back-edge" marker in the hash instead of recursing
- Two subgraphs with identical cycle structure at the same depth will still produce matching hashes

### Canonical ordering

Reference fields must be hashed in a deterministic order:
- Sort by field name (lexicographic)
- This ensures two objects with the same fields in different declaration order still match

### Duplicate group fields

| Field | Type | Description |
|-------|------|-------------|
| `id` | int | Group identifier |
| `rootClass` | String | Class of the root object in the subtree |
| `fingerprint` | String | Truncated hash (for display) |
| `copies` | int | Number of identical subtrees found |
| `uniqueSize` | long | Shallow size of one copy of the subtree |
| `wastedBytes` | long | `(copies - 1) * uniqueSize` |
| `depth` | int | Depth of the fingerprinted subtree |
| `nodeCount` | int | Number of objects in one copy of the subtree |

### Performance strategy

Fingerprinting every object is O(n * avg_subtree_size). For large heaps:
- **Class-level pre-filter**: only fingerprint classes with >N instances (default: 10)
- **Shallow pre-group**: group by (class, shallow size) first; only fingerprint within groups
- **Depth limit**: default depth=3 keeps subtrees small; deeper analysis opt-in via parameter
- **Lazy computation**: compute on first `duplicates` query, cache results in session

## Key decisions

| Decision | Options | Recommendation |
|----------|---------|----------------|
| Default depth | 1 (shape only) vs 3 (moderate) vs unlimited | 3 — captures meaningful structure without explosion |
| Value comparison | Structure only vs structure + primitive values | Structure + primitive values (otherwise "different" configs look the same) |
| String values | Include string content in hash vs exclude | Include — duplicate strings within duplicate subgraphs are the common case |
| Minimum group size | 2 (any duplicate) vs higher threshold | 2 — even a single duplicate is worth reporting if the subgraph is large |
| Array handling | Hash full array content vs hash length + sample | Length + first/last N elements for large arrays; full content for small (<100) |

## Complexity

**High.** Subgraph hashing is O(n * avg_depth), needs canonical ordering, cycle handling, and careful memory management for the hash map of fingerprints. The pre-filtering heuristics are critical for making this practical on large heaps.

## Dependencies

- **HPROF field access API** — reading instance fields for hashing (already exists)
- **Object graph traversal** — following reference fields (already exists in dominator computation)
- **`DuplicateStringsDetector`** — existing detector for strings; this feature generalizes it

## Verification

- **Synthetic test**: create HPROF with 100 identical `Pair<String, Integer>` objects (same structure and values), verify they form one duplicate group with copies=100
- **Value sensitivity**: create two groups of Pair objects with different values, verify they form separate groups
- **Cycle test**: create circular references within depth window, verify no infinite loop and correct grouping
- **Depth test**: verify depth=1 groups differently than depth=3 (shallower grouping is coarser)
- **Scale test**: run on 1M+ object heap, verify memory stays bounded and runtime is acceptable

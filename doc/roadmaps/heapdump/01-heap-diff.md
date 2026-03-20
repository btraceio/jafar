# Heap Diff / Comparative Snapshot Analysis

## Goal

Compare two or more heap dumps taken at different times to identify memory growth with certainty.
Single-snapshot heuristics (the current 6 detectors) are educated guesses.
Diffing two snapshots gives *proof* — if `HashMap$Node` count grew by 50k between t1 and t2, that's a fact, not a heuristic.

Specific outputs:
- Classes with growing instance counts and retained sizes
- Newly appeared object clusters between snapshots
- Objects that survived but shouldn't have (promotion leaks)

## UX

**Pipeline operator** — `join(session=..., root=..., by=...)`.

This is the same `join` operator used by #3 (JFR correlation). Both #1 and #3 share the cross-session `join` infrastructure; they differ only in what session types sit on each side.

## Syntax

```
# Open two heap dumps in separate sessions
open dump-before.hprof
open dump-after.hprof

# Diff at class level — compare class histograms
classes | join(session="dump-before.hprof", root=classes, by=name) | sortBy(retainedDelta)

# Diff at class level using session alias
use before = dump-before.hprof
classes | join(session=before, root=classes, by=name) | sortBy(retainedDelta)

# Show only classes where instance count grew
classes | join(session=before, root=classes, by=name) | filter(instanceCountDelta > 0) | top(20)

# Select specific delta columns
classes | join(session=before, root=classes, by=name) | select(name, instanceCount, other.instanceCount, instanceCountDelta)
```

## Design

### Data flow

1. User has two heap sessions open (baseline and current)
2. `join` operator evaluates the `root` expression against the referenced session to produce the right-hand side
3. Rows are matched by the `by` key (class name for class-level diff)
4. Delta columns are computed: `retainedDelta = retained - other.retained`, `instanceCountDelta = instanceCount - other.instanceCount`
5. Result stream includes columns from both sides plus computed deltas

### Join semantics

- Default: **left join** — all rows from the active (left) session, nulls for unmatched rows in the baseline
- Inner join available via `join(..., type=inner)` for classes present in both
- Newly-appeared classes have `other.*` = null/0 and positive deltas

### Column disambiguation

When both sides have identically named fields:
- Left side keeps original names: `retained`, `instanceCount`
- Right side prefixed with `other.`: `other.retained`, `other.instanceCount`
- Computed deltas: `retainedDelta`, `instanceCountDelta`

### Memory strategy

Two approaches depending on heap size:
- **Small heaps**: hold both parsed class histograms in memory (class histograms are compact — O(number of classes))
- **Large heaps / object-level diff**: stream one side, build hash index on the other. Object-level diff is a stretch goal.

## Key decisions

| Decision | Options | Recommendation |
|----------|---------|----------------|
| Session identification | By filename, index, or explicit alias | Support all three; alias via `use name = file` |
| Default join type | Left join vs inner join | Left join — shows newly appeared classes |
| Object-level diff | Full object graph diff vs class-level only | Start with class-level; object-level is a future extension |
| Multi-way diff | 2 snapshots vs N snapshots | Start with 2; N-way adds significant complexity |
| Delta computation | Absolute delta vs percentage | Both: `retainedDelta` (absolute) and `retainedPct` (percentage change) |

## Complexity

**Medium.** The `join` operator infrastructure is the main investment. Once built, heap-diff is a straightforward application of it with class-name-based matching. Class histograms are small enough to hold in memory for any practical heap.

## Dependencies

- **Multi-session support** — the shell must support having multiple heap sessions open simultaneously (already supported for JFR sessions)
- **`join` operator in HdumpPath** — new operator; shared with #3 (JFR correlation)
- **Session aliasing** — `use name = file` syntax for ergonomic session references

## Verification

- **Unit test**: open two test HPROF files with known class histogram differences, run diff query, assert expected deltas
- **Edge cases**: class present only in baseline (negative delta), class present only in current (new class), identical heaps (all deltas zero)
- **Large-scale test**: diff two production-scale heap dumps (>1GB) and verify memory stays bounded (no full-heap materialization)
- **Tab completion**: session names and aliases complete correctly in `join(session=...)` context

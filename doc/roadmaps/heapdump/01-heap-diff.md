# Heap Diff / Comparative Snapshot Analysis

**Status: Phase 1 implemented** (class-level diff via `join` operator)

## Goal

Compare two or more heap dumps taken at different times to identify memory growth with certainty.
Single-snapshot heuristics (the current 6 detectors) are educated guesses.
Diffing two snapshots gives *proof* — if `HashMap$Node` count grew by 50k between t1 and t2, that's a fact, not a heuristic.

Specific outputs:
- Classes with growing instance counts and retained sizes
- Newly appeared object clusters between snapshots
- Objects that survived but shouldn't have (promotion leaks)

## What Was Implemented

### `join(session=id|alias [, by=field])` pipeline operator

A cross-session left-join operator in HdumpPath. Works with all three root types (`classes`, `objects`, `gcroots`).

**Infrastructure added:**
- `SessionResolver` functional interface in `shell-core` — resolves session refs by ID or alias
- `QueryEvaluator.evaluate(Session, Object, SessionResolver)` default method — threading resolver to evaluators
- `CommandDispatcher` passes a `SessionResolver` wrapping `sessions.get()` to query evaluation
- `HdumpPathParser` parses `join(session=..., by=...)` syntax
- `HdumpPathEvaluator.applyJoin()` implements the join logic
- Tab completion for `session=` and `by=` parameters

### Syntax (as implemented)

```
open dump-before.hprof
open dump-after.hprof

# Diff class histograms — join key auto-inferred as "name" for classes
classes | join(session=1) | sortBy(instanceCountDelta desc)

# Using session alias (filename)
classes | join(session="dump-before.hprof") | sortBy(instanceCountDelta desc)

# Explicit join key override
classes | join(session=1, by=name) | filter(instanceCountDelta > 0) | top(20, instanceCountDelta)

# Object-level diff (joins by className)
objects | groupBy(class, agg=count) | join(session=1) | sortBy(countDelta desc)

# GC root type diff
gcroots | groupBy(type, agg=count) | join(session=1)

# Find classes only in the new dump
classes | join(session=1) | filter(baseline.exists = false)
```

### Output schema

For each numeric field `F` in the result set:
- `baseline.F` — value from the baseline session (null if absent)
- `FDelta` — `F - baseline.F`
- `baseline.exists` — boolean, true if the row exists in the baseline

Left side keeps original field names unchanged.

### Join key auto-inference

| Root | Default join key |
|------|-----------------|
| `classes` | `name` |
| `objects` | `className` |
| `gcroots` | `type` |

Override with `by=field` when needed.

### Design decisions taken

| Decision | What was implemented |
|----------|---------------------|
| Session identification | By integer ID or string alias (filename or `--alias` name) |
| Join type | Left join only (baseline rows without matches get null baseline columns) |
| Column naming | `baseline.*` prefix for right-side values, `*Delta` suffix for deltas |
| Baseline query | Same root/typePattern/predicates as the left side, empty pipeline |
| Memory strategy | Hash index on baseline results (O(number of baseline rows) memory) |

### Differences from original roadmap

| Roadmap proposed | Implemented | Reason |
|-----------------|-------------|--------|
| `root=` parameter | Not needed — baseline query mirrors the left side automatically | Simpler UX; `root=` will be added later for #3 (JFR correlation) |
| `other.*` column prefix | `baseline.*` prefix | "baseline" is clearer for heap diff context |
| `retainedPct` percentage column | Not yet | Can be computed with value expressions; add if needed |
| `type=inner` join mode | Not yet | Left join covers the primary use case; inner join is filterable via `filter(baseline.exists = true)` |

## Future extensions

- **`root=` parameter** — needed for #3 (JFR↔heap correlation) where the right-hand root differs from the left
- **`type=inner|outer`** — explicit join mode control
- **Percentage deltas** — `*Pct` columns for percentage change
- **Multi-way diff** — comparing N snapshots (significant complexity increase)
- **Object-level diff** — streaming approach with hash index on one side

## Complexity

**Medium.** The `join` operator infrastructure (SessionResolver, evaluator threading) was the main investment. The heap-diff logic itself is straightforward: evaluate baseline, index by key, left-join with deltas.

## Dependencies

- ~~**Multi-session support**~~ ✅ Already existed
- ~~**`join` operator in HdumpPath**~~ ✅ Implemented
- ~~**Session resolution by ID/alias**~~ ✅ Implemented via `SessionResolver`
- **Session aliasing via `use name = file`** — not needed; sessions are referenceable by ID or filename

## Verification

- **Tab completion**: `session=` and `by=` parameters complete correctly in `join(...)` context ✅
- **Unit test**: open two test HPROF files, run diff query, assert expected deltas — TODO
- **Edge cases**: class present only in baseline (null current), class present only in current (`baseline.exists = false`), identical heaps (all deltas zero) — TODO
- **Large-scale test**: diff two production-scale heap dumps (>1GB) and verify memory stays bounded — TODO

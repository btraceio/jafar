# JFR + Heap Dump Cross-Correlation

## Goal

Correlate JFR allocation data with heap dump retention data to answer: "Which method allocated the 2GB of `byte[]` that's still live?"

Heap dumps show *what* is in memory. JFR shows *who put it there*. Together, they answer the full question. This is Jafar's unique differentiator — it's the only tool that has both parsers in a single runtime. No existing open-source tool does this cross-referencing.

## UX

**Cross-session operator** — `join(session=..., root=..., by=...)`.

Same `join` operator as #1 (Heap Diff), but joining a heap session with a JFR session instead of two heap sessions. Requires explicit session identification since multiple sessions of different types can be open.

## Syntax

```
# Open both data sources
open recording.jfr
open dump.hprof

# Correlate: enrich heap class histogram with JFR allocation data
classes | join(session="recording.jfr", root="jdk.ObjectAllocationSample", by=class) | sortBy(allocRate)

# Using session alias
use jfr1 = recording.jfr
classes | join(session=jfr1, root="jdk.ObjectAllocationSample", by=class) | sortBy(allocRate)

# Find classes with high allocation rate but low survival (churn)
classes | join(session=jfr1, root="jdk.ObjectAllocationSample", by=class) | filter(allocCount > 1000 and retained < 1MB) | top(20)

# Find classes with high retained size — where are they allocated?
classes | join(session=jfr1, root="jdk.ObjectAllocationSample", by=class) | filter(retained > 10MB) | select(name, retained, allocCount, topAllocSite)
```

## Design

### Correlation approach: Aggregate-level (A+B)

Object-level join is **impossible** — HPROF records object IDs (native addresses at dump time) which have no relation to anything JFR records. Even if JFR recorded allocation addresses, GC relocation would invalidate them. The two identity spaces are completely disjoint.

Instead, use statistical/aggregate correlation:

**Approach A — Class histogram matching:**
1. From the heap session: compute class histogram (class name → instance count, total retained)
2. From the JFR session: aggregate `jdk.ObjectAllocationSample` events by `objectClass.name` → allocation count, total sampled weight
3. Join on class name

**Approach B — Allocation rate vs survival rate:**
1. From JFR: compute allocation rate per class (samples/sec)
2. From heap: compute survival count per class
3. Ratio `survived / allocated` indicates retention efficiency
4. High allocation + high survival = accumulation (potential leak)
5. High allocation + low survival = healthy churn (short-lived objects)

### Enrichment columns

| Column | Source | Description |
|--------|--------|-------------|
| `allocCount` | JFR | Number of `ObjectAllocationSample` events for this class |
| `allocWeight` | JFR | Total sampled allocation weight (bytes) |
| `allocRate` | JFR | Allocations per second (over recording duration) |
| `topAllocSite` | JFR | Most frequent allocation stack frame |
| `allocSites` | JFR | Top-N allocation stack frames with counts |
| `survivalRatio` | Computed | `instanceCount / allocCount` — fraction that survived to dump time |

### Data flow

1. Evaluate `root` expression against the JFR session → aggregate `ObjectAllocationSample` by class name
2. Build a `Map<String, AllocStats>` from the JFR side
3. For each class row from the heap side, look up matching `AllocStats`
4. Emit merged row with both heap fields and JFR enrichment columns

### Temporal alignment

The JFR recording and heap dump don't need to be from the exact same moment, but should be from the same JVM run within a reasonable time window. The correlation is statistical — class-level allocation patterns are stable over minutes/hours.

## Key decisions

| Decision | Options | Recommendation |
|----------|---------|----------------|
| Join granularity | Class-level only vs also package-level | Class-level primary; package-level via `groupBy` post-processing |
| Missing JFR data | Skip class vs show with null alloc columns | Left join — show all heap classes, nulls for unmatched |
| Stack frame depth | Top-1 allocation site vs top-N | Top-1 as default column; top-N available via `allocSites` |
| Sampling bias | Raw counts vs weight-adjusted | Weight-adjusted (`allocWeight`) accounts for sampling rate |
| Time window filter | Use full recording vs allow time range | Full recording by default; time filtering on JFR side before join |

## Complexity

**High.** Requires:
- Cross-session `join` infrastructure (shared with #1)
- JFR event aggregation within the join operator
- Understanding of `ObjectAllocationSample` event structure and sampling semantics
- Temporal alignment heuristics

## Dependencies

- **Multi-session support** — heap and JFR sessions open simultaneously
- **`join` operator in HdumpPath** — shared with #1 (Heap Diff)
- **JFR event query from HdumpPath context** — the `join` operator must be able to evaluate a JFR query against a JFR session
- **Session aliasing** — ergonomic references to specific sessions

## Verification

- **Synthetic test**: create a JFR recording and heap dump from the same JVM run with known allocation patterns; verify correlation accuracy
- **Edge cases**: class in heap but not in JFR (no allocations sampled), class in JFR but not in heap (all collected), empty JFR recording
- **Sanity checks**: `survivalRatio` should be <= 1.0; classes with 0 allocations in JFR but present in heap should have `survivalRatio = null` (not infinity)
- **Real-world validation**: run against a production heap+JFR pair and verify the top allocation sites make sense (compare with JFR-only analysis)

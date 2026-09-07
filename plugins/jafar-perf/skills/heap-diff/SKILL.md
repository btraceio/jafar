---
name: heap-diff
description: Compare two or more heap dumps taken at different times to prove memory growth rather than infer it — class-level instance and retained-size deltas, newly appeared clusters, and objects that survived when they should not have. Use whenever two .hprof files of the same application are available, or when a single-dump finding needs confirmation.
allowed-tools: mcp__jafar__hdump_open mcp__jafar__hdump_query mcp__jafar__hdump_summary mcp__jafar__hdump_close
---

# Heap diff

Single-snapshot leak analysis produces educated guesses: a large retained size might be a
leak or might be a correctly sized cache. Two snapshots produce facts. If `HashMap$Node`
count grew by 50,000 between t1 and t2 while the workload was steady, that is growth, not
interpretation.

## Taking the dumps

For the comparison to mean anything the two dumps must be separated by a workload, not by
chance. The useful pattern:

1. Warm up, then dump — this is the baseline, after class loading and cache fill.
2. Run a known, repeated workload (N iterations of the same request mix).
3. Dump again.

Anything that grew proportionally to N is a candidate. Both dumps should be taken after a
full GC where possible, so that uncollected garbage does not read as growth.

## Running the diff

Open both, then join the later against the earlier:

```
hdump_open path=/abs/path/dump-before.hprof alias=before
hdump_open path=/abs/path/dump-after.hprof  alias=after
hdump_query query="classes | join(session=before) | sortBy(instanceCountDelta desc) | top(25)"
```

The current session is the one you query; `join(session=...)` names the other side. The join
key is inferred as `name` for the `classes` root; pass `by=field` to override. It is a left
join, so classes absent from the baseline appear with null baseline columns — those are
newly appeared types and deserve attention on their own.

Rank by retained growth rather than instance count when the leak is few-and-large:

```
hdump_query query="classes | join(session=before) | sortBy(retainedDelta desc) | top(25)"
```

## Reading the result

Three shapes, three conclusions:

| Shape | Meaning |
|---|---|
| Count grew, retained grew proportionally | Straightforward accumulation — follow with `pathToRoot()` on the class |
| Count flat, retained grew | Existing objects growing internally — a collection or buffer growing without bound; use `waste()` |
| Count grew, retained flat | Small objects accumulating; often listener or `ThreadLocal` registrations |

A class that grew is a symptom. The finding is the *field that holds it*, so always finish
with a root path in the later dump:

```
hdump_query query="classes/com.example.Entry | retentionPaths()"
```

## Confirming with clusters

Cluster detection run on both dumps shows which suspicious subgraphs are new rather than
long-standing:

```
hdump_query query="clusters | sortBy(retainedSize desc) | top(10)"
```

Run against each session (switch with the `sessionId` parameter) and compare the cluster
anchors. A cluster present in both at the same size is structural, not a leak.

## Controlling for noise

Growth between two dumps is only evidence if the workload explains it. Before reporting:

- Was the same workload applied, and how many iterations?
- Did the heap have a full GC before each dump?
- Is the growth larger than the variation you would see between two baseline dumps with no
  workload at all? When in doubt, take that third dump and diff it against the first — that
  is your noise floor.

State the workload and the interval in the report. A delta without them is not
reproducible, and a leak claim that cannot be reproduced will not be believed.

## Correlating growth with allocation

Once a growing class is identified, JFR from the same interval names the code that created
the instances:

```
hdump_query query="classes | join(session=rec, root=\"jdk.ObjectAllocationSample\") | filter(retained > 1MB) | select(name, retained, allocCount, topAllocSite)"
```

See the `memory-leak` skill for the full cross-format workflow.

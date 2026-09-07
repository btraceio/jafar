---
name: memory-leak
description: Hunt memory leaks and wasted heap in a Java heap dump (HPROF) — retained sizes, dominator tree, GC root paths, known leak patterns, duplicate strings, collection waste, and correlating retained objects back to their JFR allocation sites. Use for OutOfMemoryError, heap that never comes back after GC, container OOM kills, or any .hprof file.
allowed-tools: mcp__jafar__hdump_open mcp__jafar__hdump_summary mcp__jafar__hdump_report mcp__jafar__hdump_query mcp__jafar__hdump_help mcp__jafar__hdump_close
---

# Memory leak analysis

A leak is *unintended retention*: objects reachable from a GC root that the program will
never use again. Heap dumps show what is retained and by whom. They cannot show intent — so
the deliverable is always "X is retained by Y along path Z", plus a judgement about whether
that retention is intended.

## 1. Open and orient

```
hdump_open path=/abs/path/dump.hprof
hdump_summary
```

`hdump_summary` is deliberately fast: it does not compute retained sizes. It gives object and
class counts, total heap size, top classes by shallow size, and GC root types.

## 2. Run the health report first

```
hdump_report focus=leaks
```

Returns severity-ranked findings — `CRITICAL`, `WARNING`, `INFO` — each with a `category`,
`title`, `description`, `retainedSize`, `affectedObjects`, an `action`, and a follow-up
`query` you can run directly. Start from the highest severity with a large `retainedSize`.

Other focuses: `waste`, `duplicates`, `histogram`.

## 3. Shallow versus retained

This distinction decides the whole investigation:

- **Shallow size** — the object's own bytes. `char[]` and `byte[]` always dominate; that is
  never itself a finding.
- **Retained size** — everything that becomes collectable if this object goes. This is what
  a leak is measured in.

Retained sizes need the dominator tree, which is computed on demand and cached in an on-disk
index, so the first query that needs it is slow and later ones are fast.

```
hdump_query query="classes | sortBy(retained desc) | top(20)"
hdump_query query="objects | dominators() | sortBy(retained desc) | top(20)"
```

## 4. Named detectors

Six known patterns, each answering "is this the usual suspect?":

```
hdump_query query="objects | checkLeaks(detector=threadlocal-leak)"
```

| Detector | Finds |
|---|---|
| `threadlocal-leak` | `ThreadLocal` values held by pooled threads after the request ended |
| `classloader-leak` | Class loaders kept alive after undeploy/redeploy |
| `duplicate-strings` | Identical string values held separately |
| `growing-collections` | Collections far larger than their live content |
| `listener-leak` | Registered listeners never unregistered |
| `finalizer-queue` | Objects piled up awaiting finalization |

Detectors find *known* patterns. For unknown ones, use graph structure:

```
hdump_query query="clusters | sortBy(score desc) | top(10)"
```

`clusters` finds densely-connected subgraphs with large retained size and weak external
anchoring — the shape a leak has when nobody wrote a detector for it. Drill in with
`clusters[id = N] | objects | sortBy(retained desc)`.

## 5. Prove retention with a path to a GC root

A finding without a root path is a guess. This is the single most important step:

```
hdump_query query="objects/com.example.CacheEntry | pathToRoot() | head(5)"
hdump_query query="classes/com.example.CacheEntry | retentionPaths()"
```

`pathToRoot()` gives the chain per object; `retentionPaths()` merges paths at class level,
which is what you want when thousands of instances leak through the same field. The path
names the field that holds the reference — that field is the fix.

## 6. Waste that is not a leak

Not all recoverable memory is leaked. These are often larger and easier to fix:

```
hdump_query query="objects/java.util.HashMap | waste() | sortBy(wastedBytes desc) | top(20)"
hdump_query query="duplicates | sortBy(wastedBytes desc) | top(20)"
hdump_query query="objects/com.example.Cache | cacheStats()"
```

`waste()` reports over-allocated capacity (a 1024-slot map holding 3 entries). `duplicates`
finds structurally identical subgraphs, which is a stronger signal than duplicate strings
alone. `cacheStats()` gives `fillRatio` and `costPerEntry` for cache-shaped objects.

## 7. Who allocated it — heap plus JFR

This is the question a heap dump alone cannot answer, and Jafar's differentiator: the heap
shows *what* is retained, JFR shows *who* created it.

```
jfr_open   path=/abs/path/recording.jfr alias=rec
hdump_open path=/abs/path/dump.hprof
hdump_query query="classes | join(session=rec, root=\"jdk.ObjectAllocationSample\") | filter(retained > 10MB) | select(name, retained, allocCount, topAllocSite)"
```

Adds `allocCount`, `allocWeight`, `allocRate`, `topAllocSite` and `survivalRatio`.
`topAllocSite` is the method to fix. A high `allocCount` with low `retained` is churn — a
`gc` problem, not a leak. Low `allocCount` with high `retained` is a leak of few, large,
long-lived objects.

Both sessions must be open in the same server for the join to resolve.

## 8. Two dumps beat one

Single-snapshot analysis is inference. Two snapshots are proof — see the `heap-diff` skill.

## What not to conclude

- `byte[]`/`char[]`/`String` at the top of a shallow histogram is normal in every Java heap.
  Only their *dominator* is a finding.
- A large retained size is not a leak if the retention is intended. A 2 GB cache that is
  configured to be 2 GB is working correctly; the finding is that it is too large for the
  container, which is a different recommendation.
- A dump taken without a preceding full GC contains garbage that is simply not yet
  collected. Check whether the dump was triggered on OOM (post-GC, trustworthy) or taken ad
  hoc (may overstate retention).

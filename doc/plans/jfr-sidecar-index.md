# JFR sidecar index: persisted per-chunk statistics for a recording

Status: design accepted 2026-11-26; every decision in section 14 was taken as recommended. No
production code exists yet; phase 0 (section 13) is next. Step 4 of "JFR as a query engine"; steps 1
and 2 (type filter, time-range chunk pruning) are implemented
([jfrpath-predicate-pushdown.md](jfrpath-predicate-pushdown.md), sections 10 and 11). The numbers
in section 3.3 come from throwaway code that was not committed; every other number is cited.

## 1. Why: the floor, with evidence

[MultiGbQueries.md](../performance/MultiGbQueries.md) measured JfrPath on a 4.16 GB recording of
real profiling events (4 CPUs, 2 chunk-parser threads, `MultiGbQueries.md:17-20`):

- Every query takes 7 to 40 s warm and 10.5 to 39 s cold (`MultiGbQueries.md:8-10`).
- The floor is the walk over event headers: about 1.5 s per GB warm and about 3 s per GB cold
  (`MultiGbQueries.md:54-56`). The rarest type's count is that floor: 6.96-7.54 s warm, 12.85 s
  cold (`MultiGbQueries.md:45`).
- Step 3 (constant-pool predicate rewriting) can at best bring a predicate on a pool field down to
  that floor (`MultiGbQueries.md:60-65`).

What an index can collapse, and what it cannot (`MultiGbQueries.md:67-72`):

| Query row (`MultiGbQueries.md:43-52`) | 4.16 GB warm | An index of counts and times |
|---|---|---|
| `events/jdk.ResidentSetSize \| count()` | 6.96-7.54 s | answered from the index |
| `events/jdk.ExecutionSample \| count()` | 12.38-12.82 s | answered from the index |
| `events/jdk.ThreadPark[duration>1ms] \| count()` | 7.70-8.25 s | unchanged: the predicate needs decoded values |
| `events/jdk.ExecutionSample[sampledThread/javaName=...] \| count()` | 16.03-18.74 s | unchanged, unless the index stores constant-pool sets (section 4.2) |
| the three `groupBy` rows | 14.17-39.85 s | unchanged: they group decoded values |

Chunk skipping by type pays only for types missing from most chunks; periodic and sampled events
are in every chunk of a continuous recording (`MultiGbQueries.md:70-72`).

## 2. Goals and non-goals

Goals:

1. Answer without reading the recording: event counts per type, presence of a type, the time
   bounds of a type, and the chunk list.
2. Skip chunks the index proves cannot hold a match, by type and by both time bounds.
3. Results identical to a full scan, always (section 9).
4. One store shared by jfr-shell (both shells), the TUI and jfr-mcp.

Non-goals: aggregates over decoded values (`groupBy`, `top`, stacks); constant-pool value sets in
the first version (section 4.2); recordings still being written (section 7.2); the JDK backend
(`jfr-shell-jdk`); live sessions (`jfr-shell/.../live/LiveJfrSession.java`); session-open metadata
(`JFRSession` reads every chunk's metadata at open, `shell-core/.../JFRSession.java:58-76`).

## 3. What exists to build on

### 3.1 In the parser

- A chunk header holds offset, size, constant-pool and metadata offsets, start (ns), duration
  (ns), start ticks, tick frequency and a features word:
  `parser-core/src/main/java/io/jafar/parser/internal_api/ChunkHeader.java:24-58`, 68 bytes
  (`:22`). It holds no per-type information.
- Chunk headers are read one after another on the calling thread; each chunk is then sliced and
  parsed on the pool (`StreamingChunkParser.java:408-443`). A listener skips a chunk before its
  metadata by returning `false` from `onChunkStart` (`StreamingChunkParser.java:293-298`), or
  after it from `onMetadata` (`:311-315`).
- Time pruning (#206) decides after the metadata, in `UntypedJafarParserImpl.java:346-378`, with
  one rule: skip a chunk that ends, plus a 1 s margin, before the range begins
  (`parser-core/.../impl/ChunkPruning.java:49-55`). The upper bound prunes nothing, because
  `datadog.HeapLiveObject` events in `tck-test.jfr` (no `duration` field) start up to 36 minutes
  before their chunk (`ChunkPruning.java:22-26`; `jfrpath-predicate-pushdown.md:443-447`;
  `doc/agents/DataShapes.md:298-310`). **So the index must store each type's real minimum and
  maximum `startTime` per chunk, never the chunk's header bounds.**
- Header handlers deliver each event's type, size and `startTime` without decoding it
  (`UntypedJafarParser.java:130-150`, `:395-433`); `countEvents` counts with one
  (`UntypedJafarParserImpl.java:194-221`). A header handler disables time pruning for that run
  (`UntypedJafarParser.java:416-417`).
- Gap: a header handler sees the chunk only through `Control.ChunkInfo`
  (`parser-core/.../api/Control.java:17-50`), whose `chunkId` is a JVM-wide counter
  (`ChunkInfoImpl.java:11`, `:28`), not the chunk's order or offset. The Go `EventHeader` carries
  its chunk (`go-parser/jfr/parser.go:66-80`), whose header has `Order` and `Offset`
  (`go-parser/jfr/chunk.go:21-24`).

### 3.2 In the shells

- Every query that reads events goes through `forEachMatching`
  (`shell-core/.../jfrpath/JfrPathEvaluator.java:212-229`); all three places that open a parser
  register through `handleTypes` (`JfrPathEvaluator.java:87-105`, callers `:353-354`, `:677-678`,
  `:786-793`). That is the one point where chunk skipping by index plugs in.
- `count()` has no header-only path: it decodes every event of the type
  (`shell-core/.../jfrpath/Aggregations.java:332-354`).
- `countAllEventTypes` decodes **every event of every type**
  (`JfrPathEvaluator.java:146-154`). It is called, uncached
  (`shell-core/.../analysis/JfrQuerySource.java:29`, `:46-47`), by `jfr_list_types` with `scan`
  (`jfr-mcp/.../jfr/JfrSessionTools.java:292-297`), by the summary analysis
  (`shell-core/.../analysis/JfrAnalyses.java:69`) and by each event-type detector
  (`JfrAnalyses.java:509`, `:708`, `:719`, `:734`): one analysis can pay several full decoding
  passes before its own query.
- `TypeDiscovery.getTypeCounts` (`shell-core/.../TypeDiscovery.java:114-118`) and the TUI summary
  (`jfr-shell/.../JfrTuiAdapter.java:159-173`) use the header-only `countEvents`.
- `validateEventTypes` checks declared types from metadata, not counts
  (`JfrPathEvaluator.java:702-720`).

### 3.3 Measured for this document

Throwaway code on 2026-11-26, same machine and files as `MultiGbQueries.md`, `jfr-shell` fat jar
built at `6e7e8c6a` plus that document's fix; times are in-process (no JVM start-up).

| What | 1.03 GB, 170 chunks | 4.16 GB, 690 chunks |
|---|---|---|
| Walk the 68-byte chunk headers (Python, `seek`+`read`) | not run | 39.9 ms cold, 0.94-1.39 ms warm |
| `countEvents()` | 2.13-2.92 s (3 runs) | 8.18-10.40 s (2 runs) |
| Build the index of section 4.1 with `handleHeaders` (start times on) | 2.98-3.36 s (3 runs) | 12.15-12.78 s (2 runs) |
| (chunk, type) entries; serialized size, uncompressed | 15,980; 583,561 bytes | 64,860; 2,361,961 bytes |

The realistic file is one 6.0 MB recording repeated (`MultiGbQueries.md:31-37`), so all 94 event
types are in every chunk (15,980 = 170 x 94); `jfr summary` of the source recording lists 96 of its
196 types with events. The builder used one `ConcurrentHashMap` lookup and a lock per event, so
its time is an upper bound for a parser-internal builder.

### 3.4 Precedents for a persisted index

| | `hdump-parser/.../index/` | `jfr-shell/.../cli/EventCountCache.java` |
|---|---|---|
| Location | a directory `<dump>.idx` beside the dump, created unconditionally (`HeapDumpImpl.java:244-246`) | `$XDG_CACHE_HOME/jafar/event-counts`, else `~/.cache/...`, never beside the recording, because recordings sit in read-only or shared directories (`EventCountCache.java:24-28`, `:109-119`) |
| Format | binary, big-endian `DataOutputStream`; header `[magic:4][version:4][entryCount:8][flags:4]` (`IndexFormat.java:72-79`, `IndexWriter.java:66-75`); ASCII magics `J...` (`IndexFormat.java:46-68`) | `java.util.Properties` (`EventCountCache.java:45-48`, `:78-81`) |
| Versioning | one `FORMAT_VERSION` (`IndexFormat.java:41-42`); a mismatch rebuilds (`HeapDumpImpl.java:526-535`) | none |
| Invalidation | magic and version only (`HeapDumpImpl.java:425-540`): **nothing ties the index to the dump**, so a dump replaced under the same name reuses a stale index | key = digest of absolute path, size and mtime (`EventCountCache.java:95-107`) |
| Writes | temp file, then `ATOMIC_MOVE` + `REPLACE_EXISTING` (`IndexWriter.java:63`, `:136-141`) | in place (`EventCountCache.java:80`): a concurrent reader can load a truncated file |
| Failure | rebuild | silent, "never ... a wrong number" (`EventCountCache.java:30-32`, `:53-56`) |

Reused: big-endian binary with magic, version and flags (hdump); temp file plus atomic rename
(hdump); a user cache directory and a source identity check (EventCountCache); every failure
degrades to a scan. Not reused: hdump's missing identity check, and the in-place write.

## 4. Contents

### 4.1 Per chunk (version 1)

| Field | Source |
|---|---|
| order, offset, size | `ChunkHeader.order`, `.offset`, `.size` |
| startNanos, durationNanos, startTicks, frequency | `ChunkHeader` (`:45-55`) |
| per event type with at least one event: count | header walk |
| per type: min and max `startTime` | the header handler's value, the same a decoded event carries (`UntypedJafarParser.java:144-147`); absent for `NO_START_TIME` types |
| per type: total bytes | the header handler's `size` (`:143`); free, and gives `jfr summary`'s size column |

Type names go in one string table. The decoded `startTime` value is stored, in whatever unit the
decoded event has, so comparing it with a query's bound needs neither the 1 s margin nor the
timestamp-kind check of `ChunkPruning.java:29-34`, `:79-82`. It must be produced by the same
conversion code as decoding (rule R7), which `handleHeaders` already is.

### 4.2 Optional: constant-pool value sets (for step 3)

Per chunk, the set (or a Bloom filter) of thread names, class names or other strings would let
`[sampledThread/javaName="no-such-thread"]` return 0 without reading the file, the row that costs
17.48-18.74 s (`MultiGbQueries.md:49`). Costs: every pool entry of every chunk must be decoded,
where today pools are indexed and decoded on demand (the separate `constants.indexed` and
`constants.decoded` work counters, `doc/agents/Gates.md:305-317`); a size proportional to the
pools, not measured; and an invalidation hazard: `tools/.../Scrubber.java:165-172` rewrites
strings in place at the same length, so a scrubbed copy has identical size and chunk headers but
different strings. Version 1 leaves them out (decision O5).

## 5. Ownership: which module

Rule (a) of the architecture gate: only `parser-core` and `parser-codegen` may depend on
`io.jafar.parser.impl..` or `io.jafar.parser.internal_api..` (`doc/agents/Gates.md:95-96`).
Building needs `ChunkHeader` (internal API) or chunk order and offset that the public `ChunkInfo`
does not expose (section 3.1), and skipping a chunk before its metadata needs `onChunkStart`
(internal API). The pushdown work hit the same wall and moved pruning into the parser
(`jfrpath-predicate-pushdown.md:438-442`). So:

- **parser-core**, public API in `io.jafar.parser.api`: an immutable `RecordingIndex` of
  `ChunkStats` (plain longs and strings), `UntypedJafarParser.buildIndex()`, the binary codec
  (`write(OutputStream)`, `read(InputStream)`), and `withIndex(RecordingIndex)`, which skips a
  chunk when no registered `FilteredHandler` can match in it. With a `TypeFilter.named` filter
  (`parser-core/.../TypeFilter.java:30-32`) the decision is made in `onChunkStart`; with any other
  filter, in `onMetadata`, by testing each class and looking its name up. An unfiltered handler or
  a header handler reads every chunk. Implementation under `parser/impl`, with per-chunk arrays
  keyed by type id instead of the shared map of section 3.3.
- **shell-core**, new package `io.jafar.shell.index`: where the file lives, identity checks,
  opt-in, trust (sections 6 to 12). jfr-shell and jfr-mcp reach it through shell-core, the
  allowed direction (rule d, `Gates.md:104-106`). `EventCountCache` moves onto it.

The chunk-skip and answer-only paths apply only to `DefaultEventSource`
(`JfrPathEvaluator.java:767`); a substituted `EventSource` keeps today's behaviour.

## 6. File format and location

One file, big-endian, written with `DataOutputStream` like hdump:

```
header    magic "JFRI" (0x4A465249) u4 | formatVersion u4 | flags u4
identity  recordingSize u8 | mtimeMillis u8 | chunkCount u4 | chunkHeaderDigest (SHA-256 of
          every chunk's 68 header bytes, in order) 32 bytes | builder (Jafar version) UTF
strings   count u4, then count x (UTF)
chunks    per chunk: order u4 | offset u8 | size u8 | startNanos u8 | durationNanos u8 |
          startTicks u8 | frequency u8 | typeCount u4, then typeCount x
          (typeName u4 | count u8 | minStart u8 | maxStart u8 | bytes u8)
trailer   CRC-32 of everything above u4
```

At the measured density this is about 3.4 KB per chunk, 0.06% of the file (section 3.3).

Location (decision O2): by default `$XDG_CACHE_HOME/jafar/jfr-index/<digest>.jfri`, else
`~/.cache/jafar/jfr-index/`, with `<digest>` derived from the absolute path as in
`EventCountCache.java:95-107`. A sidecar `<recording>.jfri` is written only on request and read
only when trusted (section 11). An unwritable cache directory means no index and a scan; it is
reported at debug level, as pruning is (`jfrpath-predicate-pushdown.md:414`), and by an
`index status` command, so the fallback is visible (rule R3).

## 7. Building, invalidation, growing files

### 7.1 Build cost

One full header walk with start times: 12.15-12.78 s on 4.16 GB against 8.18-10.40 s for
`countEvents` (section 3.3). Not free during a normal query: a query skips chunks by time and
decodes only its types, and registering a header handler beside it disables time pruning
(`UntypedJafarParser.java:416-417`). It is close to free when the pass already reads every event
header of every chunk: `countEvents`, the TUI summary, `TypeDiscovery.getTypeCounts`, and
`countAllEventTypes` once it uses headers (task 0.2). Those build it as a by-product.

### 7.2 Invalidation

An index is used only if, at query time: magic, format version and trailer CRC match; the builder
version equals the running Jafar version; the recording's size and mtime equal the stored ones; and
the digest of the chunk headers, recomputed by walking them (39.9 ms cold, about 1 ms warm for 690
chunks, section 3.3), matches. Any mismatch: ignore the file, scan, rebuild if allowed.

Recordings still being written: a JFR repository chunk that is not finished has zero metadata and
constant-pool offsets, and the parser refuses it (`StreamingChunkParser.java:299-310`); a header
with no duration cannot be pruned (`ChunkPruning.java:50-52`). Rule: never write an index when any
chunk has `duration == 0` or the chunk sizes do not add up to the file size. A file that grows
changes size and mtime and misses. Extending an index by the chunks appended since is phase 4.

### 7.3 Concurrency

Writers build into a temp file in the target directory and rename it with `ATOMIC_MOVE` and
`REPLACE_EXISTING` (`IndexWriter.java:136-141`); two concurrent builders produce equal files and
the last rename wins. Readers read the whole file (megabytes) and check the CRC, so a torn read
fails closed. The builder's per-chunk state is chunk-local: chunks are parsed on several threads
(`StreamingChunkParser.java:62-69`), and the race fixed in #204 came from shared state
(`jfrpath-predicate-pushdown.md:426-427`). The builder joins the processor-count matrix
(`doc/agents/Gates.md:628`).

### 7.4 Versioning

`formatVersion` changes with the layout, as hdump's (`IndexFormat.java:41-42`). The builder version
covers value semantics: an index built by a Jafar that converted a time differently would answer
differently from a scan by the running one. A rebuild costs one header walk, so a new release
rebuilding every index is acceptable (decision O4).

## 8. Which queries change (expected, not measured)

| Caller | Today | With a valid index |
|---|---|---|
| `events/T \| count()`, no predicate (`Aggregations.java:332-354`) | decodes T: 6.96-12.82 s at 4.16 GB | sum of the index's counts: start-up plus reading the index and walking the chunk headers |
| `events/T[startTime...] \| count()`, bounds only | prunes by lower bound only | chunks wholly inside the range add their count; chunks wholly outside are skipped; only straddling chunks are read |
| any query on a type with 0 events | full scan for no rows | empty result without parsing |
| any filtered query on T | lower-bound pruning | skips chunks without T and chunks whose T `[min, max]` misses either bound; the predicates still run on every event read |
| `countAllEventTypes` and its five callers (section 3.2) | full decoding pass each | the index |
| `TypeDiscovery.getTypeCounts`, TUI summary, `ask`'s counts (`CommandDispatcher.java:562-585`) | header pass, or `EventCountCache` | the index |
| `chunks/` root (`shell-core/.../providers/ChunkProvider.java:58-60`) | parses | could read the index (not required) |
| `groupBy`, `top`, stack queries, non-time predicates on every-chunk types | 14-40 s | unchanged |

## 9. Correctness rule and the differential test

**Rule: any answer or skip from the index equals what the same query returns with the index
off.** Skipping may only remove chunks the index proves have no matching event; predicates still
run on every event read, as for pruning (`jfrpath-predicate-pushdown.md:292-293`).

Tests, in the pattern of `TypePushdownTest.timeFilteredResultsEqualTheUnprunedReference`
(`shell-core/src/test/java/io/jafar/shell/jfrpath/TypePushdownTest.java:164`), which caught the
`HeapLiveObject` loss (`DataShapes.md:308-309`):

1. **Index equals a decoded scan.** For every corpus recording (`tck-test.jfr`, the generated
   recordings, the adversarial seeds that parse), the built index equals one computed by decoding
   every event and aggregating its `startTime` and count from the decoded maps.
2. **Queries equal the reference.** For every type, `count()`, presence and
   `[startTime>=t]`, `[startTime<=t]`, `between` with `t` taken from every chunk boundary and every
   per-type min and max plus and minus 1 ns, results with the index equal results under a
   test-only `jafar.index=false` (like `jafar.jfrpath.pushdown`, `JfrPathEvaluator.java:71-80`),
   on both untyped readers.
3. **R5.** The test of 2 fails when the builder stores chunk bounds instead of per-type min and
   max: `tck-test.jfr`'s `datadog.HeapLiveObject` count under an upper bound goes wrong.
4. **Stale and hostile files miss.** Appended chunk, truncation, same-size rewrite with other
   headers, changed mtime, wrong version, flipped byte, truncated index, oversized counts: each
   gives the scan's answer, never another one.
5. **Work budget.** A new cell: `count()` with a valid index reads 0 chunks (`Gates.md:305-317`).

## 10. Default or opt-in

Recommended (decision O1): using a valid index from the user cache directory is on by default
once tests 1 to 4 pass; building happens as a by-product of full header passes (section 7.1) and
through an explicit `index` command in jfr-shell and an `index` argument to jfr-mcp's `jfr_open`.
No background build in version 1: it would compete with the query for the two parser threads of a
4-CPU machine (`MultiGbQueries.md:74-76`).

## 11. Security

An index is a set of claims about a recording, and a wrong count is worse than none, "because the
model acts on it" (`EventCountCache.java:53-56`). The identity check of section 7.2 binds an index
to the chunk headers but does not authenticate the counts: anyone who can write the index can make
it lie. Hence:

- Read from the user's own cache directory by default; refuse it when it is not owned by the user
  or is writable by others.
- Never read a sidecar beside the recording by default: recordings arrive in shared or untrusted
  directories (`EventCountCache.java:24-28`). Read it only when the user names it or turns on a
  setting, and say in the result that the answer came from it.
- The reader is a parser of untrusted input: check every length against the file size before
  allocating, require `chunkCount` and every order and offset to match the header walk, and fail
  closed. It joins the Jazzer corpus (`Gates.md:411`).

## 12. Go parser parity

The parity gate watches `parser-core/src/main/java/io/jafar/parser/(impl|internal_api)/` and
`go-parser/jfr/` (`scripts/parity-check.sh:42-43`), so the builder and `withIndex` under
`parser/impl` trip it. The Go module is a parser only, with no query language or CLI
(`AGENTS.md:112`), and it already exposes what a Go index would need without a parser change
(section 3.1). Proposed statement: `Parity: not applicable — the index serves JfrPath and jfr-mcp,
which have no Go counterpart; the Go EventHeader already carries its chunk's order and offset`,
plus a line under "Differences from the Java parser" (`go-parser/README.md:326`). If the file
format is meant to be shared with Go later, section 6 is already language-neutral (decision O6).

## 13. Plan

Each task is one commit with its tests (section 9 numbers them). Phase 0 needs no index; it takes
the wins section 3.2 shows.

| Task | Module | What | Tests |
|---|---|---|---|
| 0.1 | shell-core | `count()` without predicates uses `countEvents(TypeFilter.named(types))` | existing count tests, both readers |
| 0.2 | shell-core | `countAllEventTypes` counts headers (an `EventSource` method with a decoding default); `JfrAnalyses` counts once per analysis, not once per detector | equal counts before and after |
| 1.1 | parser-core | `ChunkStats`, `RecordingIndex`, `buildIndex()`, codec | 1, 3; Jazzer target for the reader; allocation and work budget cells |
| 1.2 | parser-core | `withIndex(RecordingIndex)`: chunk skipping, in `onChunkStart` for named filters | parser-level form of 2 |
| 2.1 | shell-core | `io.jafar.shell.index`: location, identity checks, atomic write, trust rules | 4 |
| 2.2 | shell-core | `handleTypes`/`forEachMatching` pass the index; `count()`, `countAllEventTypes`, `TypeDiscovery`, TUI summary answer from it | 2 on both readers; 5 |
| 2.3 | jfr-shell | `EventCountCache` moves onto the store and is deleted | its test, retargeted |
| 3 | jfr-shell, jfr-mcp, docs | `index`, `index status`; `jfr_open` argument; `doc/cli/JFRPath.md`, changelog | R1: the queries of section 8 typed into the fat jars and timed on the 4.16 GB file against `MultiGbQueries.md` section 2 |
| 4 | later | incremental extension for grown files; constant-pool sets (4.2) | |

## 14. Decisions

**Decided 2026-11-26 with the maintainer: every recommendation below, O1 to O9, as written.**

| # | Question | Recommendation |
|---|---|---|
| O1 | Default or opt-in? | Use by default once the differential tests pass; build only on full header passes and on request. |
| O2 | Cache directory or sidecar? | Cache directory by default; sidecar only on request, for both writing and reading. |
| O3 | Key the cache by path or by content (size plus header digest)? | By path, and check size, mtime and header digest: a copied file rebuilds once, and a same-headers rewrite such as the scrubber's never answers from the original's index. |
| O4 | Tie validity to the Jafar version? | Yes; a rebuild is one header walk. |
| O5 | Constant-pool sets in version 1? | No; measure their size and build cost first, and key them on a digest of each chunk's pool bytes. |
| O6 | Port the index or `withIndex` to Go? | No; state parity not applicable and keep the format language-neutral. |
| O7 | Per-type byte totals? | Yes; 8 bytes per entry, free in the header walk. |
| O8 | Background build on open? | No in version 1; revisit after the parallel-path work of `MultiGbQueries.md:74-76`. |
| O9 | Do phase 0 first? | Yes; it needs no format, removes repeated full decoding passes from jfr-mcp analyses, and sets the baseline the index is judged against. |

## 15. What I did not verify

- No index code exists; sections 5 to 13 are design statements. The "with index" column of section
  8 is expected, not measured.
- The build and header-walk times come from throwaway code, two or three runs, one machine, on
  concatenated copies of one recording in which every type is in every chunk; a real long
  recording has more varied chunks, so the per-chunk entry count and the index size will differ.
  A parser-internal builder was not measured; section 3.3's, with per-event locking, is an upper
  bound.
- Whether `countEvents`'s header pass costs the same as the header walk under the type-filtered
  count of `MultiGbQueries.md` (8.18-10.40 s here against 6.96-7.54 s there) was not explained:
  different process (in-JVM against whole CLI) and a different session.
- Phase 0's gains and the size and cost of constant-pool sets (4.2) were not measured.
- The behaviour of JDK repository files while being written (header fields during a write, the
  features word) was not examined beyond the parser's existing checks.
- That a concurrent reader of `EventCountCache` can see a truncated file is read from the code
  (`EventCountCache.java:80`), not reproduced.
- `hdump-parser`'s missing identity check was read from `HeapDumpImpl.checkValidIndexes`; I did not
  check whether another layer guards it.

# JfrPath queries on multi-GB recordings

Measured 2026-11-26, to settle the condition the brainstorm put on steps 3 and 4 of "JFR as a
query engine" (constant-pool predicate rewriting and a per-chunk sidecar index): build them "only
if multi-GB recordings are still not interactive". Until now every benchmark used the 124.8 MB
generated recording (`doc/plans/jfrpath-predicate-pushdown.md`, section 3).

**Answer: they are not interactive.** On a 4.1 GB recording of real profiling events, every query
takes 7 to 40 s with a warm page cache and 10.5 to 39 s cold. The floor is the walk over event
headers, about 1.5 s per GB warm, which neither step 3 nor a faster decoder removes.

Measuring also found a bug that made every filtered query 3.4x slower than it needed to be. It is
fixed in the same change (section 4).

## 1. Method

- **Machine.** 4 processors (Intel Xeon @ 2.10 GHz), 15 GB RAM, so a 4.1 GB file fits in the page
  cache. The chunk parser uses `max(availableProcessors - 2, 1)` threads by default
  (`StreamingChunkParser.java:78`), 2 here, so a larger machine
  will be faster on everything except the single-threaded parts.
- **Build.** `jfr-shell` and the `jafar` backend built from `main` at `6e7e8c6a`, plus the fix in
  section 4 where noted, run on JDK 25 with an empty plugin directory and
  `-Djfr.shell.backend.jars` pointing at the built backend.
- **Timing.** The wall time of a whole `jfr-shell show <file> <query>` process, three runs per
  cell. It includes JVM start-up, which is about 0.9 s (the 120 MB rare-type row). There is no
  error estimate beyond the spread of the three runs.
- **Cold runs.** One run per query, after `sync; echo 3 > /proc/sys/vm/drop_caches`.
- **Synthetic recordings.** `PushdownRecording` (the generator `QueryPushdownBenchmark` uses)
  with 32, 256 and 1024 segments: 124.9 MB, 1.01 GB and 4.14 GB. Per segment: 100,000
  `bench.Common`, 10,000 `bench.Sampled` (with stack traces) and 10 `bench.Rare` events.
- **Realistic recordings.** One 6.0 MB, 96 s recording of a busy JVM made with `settings=profile`
  and `jdk.ExecutionSample#period=2ms`: jfr-shell itself running queries over the 1 GB synthetic
  file. It holds 71,718 execution samples, 27,293 allocation samples, 2,322 park events and 95
  `jdk.ResidentSetSize` events. It was concatenated 20, 170 and 690 times, giving 120.7 MB,
  1.03 GB and 4.16 GB. A JFR chunk is self-contained, so concatenation gives a valid recording,
  which is also how `jfr assemble` works. The copies repeat the same constant pools and
  timestamps, which no query below depends on.

## 2. Realistic recordings

Seconds, three warm runs, then one cold run on 4.16 GB. Built with the fix in section 4.

| Query | 120.7 MB | 1.03 GB | 4.16 GB warm | 4.16 GB cold |
|---|---|---|---|---|
| `events/jdk.ResidentSetSize \| count()` (rarest type) | 0.97-1.08 | 2.37-2.61 | 6.96-7.54 | 12.85 |
| `events/jdk.ThreadPark[duration>1ms] \| count()` | 0.91-1.07 | 2.23-2.66 | 7.70-8.25 | 10.56 |
| `events/jdk.ExecutionSample \| count()` | 1.01-1.25 | 3.82-4.06 | 12.38-12.82 | 16.34 |
| `events/jdk.ExecutionSample[sampledThread/javaName="main"] \| count()` | 1.38-1.43 | 4.48-4.95 | 16.03-16.65 | 19.83 |
| `events/jdk.ExecutionSample[sampledThread/javaName="no-such-thread"] \| count()` | 1.29-1.48 | 4.70-5.23 | 17.48-18.74 | 20.36 |
| `events/jdk.ObjectAllocationSample \| groupBy(objectClass/name, ...)` | 1.29-1.34 | 4.16-4.36 | 14.17-14.46 | 17.70 |
| `events/jdk.ExecutionSample \| groupBy(sampledThread/javaName, ...)` | 1.38-1.53 | 5.30-5.71 | 19.01-21.04 | 21.69 |
| `events/jdk.ExecutionSample \| groupBy(stackTrace/frames[0]/method/name, ...)` | 1.91-2.41 | 9.80-11.53 | 37.08-39.85 | 38.71 |

Every query is linear in the file size. The rarest type's count rises by 4.83 s between 1.03 GB
and 4.16 GB, so the header walk alone costs about 1.5 s per GB warm and, from the cold column,
about 3 s per GB when the file has to be read from disk.

## 3. What this says about steps 3 and 4

**Step 3, constant-pool predicate rewriting, saves at most about a third.** It would turn
`sampledThread/javaName = "no-such-thread"` into an empty set of thread ids and skip the type's
events without decoding them. The best that can give is the cost of walking those events' headers.
That is between the rarest-type count (6.96-7.54 s) and the count of the same type (12.38-12.82 s),
against 17.48-18.74 s today: still 7 to 13 s on 4.16 GB. Step 3 does not make the query
interactive, and it helps only predicates on constant-pool fields.

**Step 4, a per-chunk sidecar index, removes the floor only for what it stores.** It can answer
counts by type, the presence of a type and per-chunk time bounds without reading the recording,
so those queries would cost little more than start-up (expected, not measured). It does not help a `groupBy` over decoded values, the 14 to 40 s
rows, unless the index also stores those aggregates. One more limit: chunk skipping by type pays
only for types that are absent from most chunks. Periodic and sampled events appear in every
chunk of a continuous recording, as they do in every chunk here.

**What would move every row** is the per-event cost on the parallel path: the parser ran 2 chunk
threads on this 4-processor machine, so neither the header walk nor decoding used half the CPUs.
That was not measured here.
It has since been measured: [ParserParallelism.md](ParserParallelism.md). The default became one
thread per processor, and four aggregations stopped serializing on a lock.

## 4. Found while measuring: filters threw an exception per event

On the 1 GB synthetic file, `events/bench.Common[bucket=7] | count()` took 27.1-29.4 s, but
`events/bench.Common | groupBy(bucket)`, which reads the same field from the same events, took
7.9-8.7 s. A JFR profile of the filtered query, compared with `jfr-shell compare`, put
`Integer.parseInt` and `NumberFormatException` construction on 26% of the stacks, under
`PathValues.buildPathTokens`. A filter resolves its field path for every event it tests, and
`buildPathTokens` told a field name from an index by calling `Integer.parseInt` and catching the
exception: one throw per path segment per event. `groupBy` builds its tokens once per query
(`Aggregations.java:575`), so it never paid this.

The fix checks the characters before parsing. Synthetic recordings, seconds, three runs each:

| Query | File | Before | After |
|---|---|---|---|
| `events/bench.Common[bucket=7] \| count()` | 124.9 MB | 4.34-4.43 | 1.77-1.96 |
| | 1.01 GB | 27.09-29.45 | 7.99-8.33 |
| | 4.14 GB | 110.6-117.1 | not re-run |
| `events/bench.Common[payload="payload-7-no-such"] \| count()` | 124.9 MB | 4.09-4.37 | 1.72-2.10 |
| | 1.01 GB | 23.64-24.34 | 8.13-8.73 |
| | 4.14 GB | 107.8-118.6 | not re-run |

The counts are the same before and after (12,512 and 100,096 matches; 0 for the payload filter).
In the same session, the unaffected `groupBy(bucket)` on 1.01 GB took 9.38-9.80 s after the
change against 7.92-8.66 s before it. Its code did not change, so that spread is how much two
sessions on this machine differ, and it bounds how exact the "after" numbers above are.

The same profile comparison exposed a second bug: `JfrCompare.stableName` kept the per-JVM
`/0x...` address of hidden classes whose name has no `$`, such as Jafar's generated
`UntypedDeserializer_<type>_<n>` classes, so `jfr-shell compare` and perf-ab attribution showed
each of them as removed on one side and new on the other. It is fixed in the same change.

## 5. Not verified

- Recordings from real production services. The realistic file is one JVM's 96 s of profiling,
  repeated; a service with more threads, deeper stacks or more event types will decode
  differently.
- Larger machines. Every number comes from one 4-processor machine with 2 parser threads.
- Files larger than the page cache. 4.16 GB fit in 15 GB of RAM; a cold run is the only case
  where reading from disk was measured, and only once per query.
- The peak resident set sizes (up to 6.8 GB for the 4.14 GB file) were read from `wait4` and were
  not broken down. Mapped file pages would count toward them; whether the heap grows with the file
  was not checked.
- The JDK backend (`jfr-shell-jdk`) was not measured.

# Parser parallelism: the default thread count, and locks in the aggregations

Measured 2026-11-27. It answers a question from `MultiGbQueries.md` section 3: the chunk parser
ran 2 threads on a 4-processor machine, `max(availableProcessors - 2, 1)`, so did every query leave
half the machine idle? No commit gives a reason for the `- 2`. It arrived with the thread pool
itself, in the bulk commit `125c3da4`.

**Answer.**

- **The parser alone** scales to the processor count and no further. With 4 threads instead of
  2, it is 1.6 to 1.7 times faster at walking headers, decoding one type, and decoding every
  event. The default is now one thread per processor.
- **The aggregations did not scale.** `groupBy`, `stats()`, `quantiles()` and `timerange()` each
  updated one shared accumulator under a lock for every event, so they got slower with more
  threads. Each now accumulates per thread and merges after the parse. With the new default they
  run 1.4 to 2 times faster than before.

## 1. Method

- **Machine and data.** The same machine and the same 4.16 GB realistic recording (`r690`) as
  `MultiGbQueries.md`: 4 processors, 15 GB RAM, JDK 25. The page cache was warmed before each set
  of runs.
- **Concurrent load.** Another process, a Gradle build and test agent, ran at the same time; the
  load average was 1.2 to 5.0 across the runs. Every table interleaves its configurations, in a
  shuffled or alternating order per round, so that the load falls on all of them alike. Three runs
  per cell.
- **The parser alone.** A small harness (not committed) opens the file with
  `ParsingContext.create().withParallelism(n)` in a fresh JVM per run. Three modes:
  - `countEvents()`: the header walk;
  - one type decoded through a type filter;
  - every event decoded.
- **End to end.** `jfr-shell show <file> <query>` with the built jars. The parser thread count was
  set with `-XX:ActiveProcessorCount`, because the shell has no option for it. That flag also
  changes the JVM's GC thread counts, so every end-to-end table pins
  `-XX:ParallelGCThreads=4 -XX:ConcGCThreads=1`, what a 4-processor JVM uses, unless it says
  "default".

## 2. The parser alone

Seconds per run on the 4.16 GB file; the results (event counts) are identical in every cell.

| Threads | Header walk, all types | Decode `jdk.ExecutionSample` | Decode every event |
|---|---|---|---|
| 1 | 11.38-12.72 | 14.52-15.02 | 34.81-39.66 |
| 2 (old default) | 6.61-7.14 | 7.63-8.66 | 18.75-19.68 |
| 3 | 4.42-5.42 | 5.52-6.64 | 13.29-15.21 |
| 4 | 4.08-4.43 | 4.73-5.25 | 10.75-11.92 |
| 6 | 3.88-4.36 | 5.25-5.78 | 11.68-12.65 |
| 8 | 4.20-4.53 | 5.11-5.35 | 12.34-13.04 |

Going from 1 to 4 threads is close to linear. Beyond 4 threads, on 4 processors, nothing improves
and decoding every event gets slightly slower. One thread per processor is therefore the fastest
setting, or ties for fastest, in every column.

## 3. The aggregations

### 3.1 What a profile showed

End to end, the counts got faster with 4 threads, but `groupBy` got slower: 15.5-16.7 s against
21.2-26.3 s for `groupBy(sampledThread/javaName)`. Pinning the GC threads did not change that:
12.6-14.9 s against 16.6-19.7 s. A JFR recording of each case, compared with
`jfr-shell compare`, located the cause (1 GB file, warm):

- `Aggregations.groupFor` rose from 15% to 37% of inclusive samples;
- `HashMap.hash` rose from 5% to 19% of self samples;
- `jdk.JavaMonitorEnter` events rose by 254%.

Every event's update ran inside `synchronized (groups)`, one lock shared by every chunk thread.
`stats()` (a `synchronized add`), `quantiles()` (a list appended under a lock) and `timerange()`
(a `synchronized` update) had the same shape. `count()` and `sum()` already used `LongAdder` and
`DoubleAdder`.

### 3.2 The change

Each thread now updates an accumulator of its own, through `Aggregations.PerThread`, without a
lock. The accumulators are merged once the parse is over; the parse joins its chunk tasks with
`Future.get`, so their writes are visible to the merge.

| Operator | Merge |
|---|---|
| `groupBy` | Groups are merged; count, sum, min and max add up. The distinct-group cap is checked per thread while streaming, and again on the merged map. |
| `stats()` | Count, min and max add up. Mean and the sum of squared deviations use the pairwise update of Chan, Golub and LeVeque for Welford's algorithm. |
| `quantiles()` | The value lists are concatenated, then sorted as before. |
| `timerange()` | Min, max and count. |

Tests:

- `GroupByMergeTest` and `AccumulatorMergeTest` check that a merge equals one accumulator.
  Removing the merge's cap check, or Chan's correction term, makes them fail.
- `JfrPathConcurrentAggregationTest` checks exact results on a 16-chunk recording, five times
  each. It covers all four operators and passes.

### 3.3 Before and after

Seconds per run on the 4.16 GB file. "Before" is the build without the change; for `groupBy`
that is `main` at `ba040e1e`, and for the other three the build with only the `groupBy` change.

| Query | Before, 2 threads | Before, 4 threads | After, 4 threads | After, default (4) |
|---|---|---|---|---|
| `groupBy(sampledThread/javaName)` | 12.6-16.7 | 16.6-26.3 | 7.1-8.6 | — |
| `groupBy(stackTrace/frames[0]/method/name)` | 28.4-29.7 | 32.8-35.9 | 17.4-20.3 | — |
| `stats(weight)` | 7.9-10.1 | 9.6-12.3 | 5.2-7.3 | 5.7-6.1 |
| `quantiles(0.5, 0.99)` | 15.7-18.5 | 16.6-18.1 | 10.7-11.5 | 11.5-12.6 |
| `timerange()` | 15.3-18.9 | 22.6-25.6 | 7.3-10.5 | 8.6-9.4 |

The results are the same before and after:

- the same groups and counts;
- the same `stats` count, min and max, with the mean and standard deviation differing only in
  their last digits, as they already did between two runs of the old build, because chunks
  finish in a different order each time;
- the same quantiles.

The `groupBy` "before, 2 threads" row comes from two sessions (12.6-14.9 s and 15.5-16.7 s).

## 4. What changed for users

- **The default thread count.** `StreamingChunkParser.defaultParallelism()` is now
  `availableProcessors`, so every parser created without `ParsingContext.withParallelism(n)` uses
  one thread per processor. A program that ran a parse alongside other work and relied on 2 spare
  processors should pass `withParallelism(n)`.
- **The allocation gate** (`allocationTest`) needs a pool of exactly 2 threads, matching
  `tck-test.jfr`'s 2 chunks. It now pins `-XX:ActiveProcessorCount=2` instead of `4`. The budgets
  did not change.
- **Go parser parity** does not apply: the Go parser has no thread pool; it parses chunks
  sequentially.

## 5. Not verified

- **Other machines.** Only one machine was measured, a 4-processor virtual machine, under
  concurrent load from another process. Machines with many more processors, and machines with
  slow disks, where more threads may mean more random reads of a file that is not cached, were
  not measured.
- **Applications embedding the parser.** Whether one that consumes events on other threads loses
  throughput with no processor left over was not measured.
- **Operators that materialize every row.** Projections and string transforms still append to a
  shared list under a lock. They hold every row in memory anyway; whether the lock costs them
  time was not measured.
- **Memory.** A `groupBy` now holds up to one map per thread, each capped at
  `jafar.groupby.maxGroups`. The peak memory of a high-cardinality `groupBy` was not measured.

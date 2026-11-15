# JfrPath predicate pushdown, phase 1: event-type filter and time-range chunk pruning

Status: design for review. The baseline benchmark (section 3) is implemented and measured; no
pushdown code exists yet. Decisions marked **Decided** were taken with the maintainer on
2026-11-15; everything under "Open questions" is not decided.

Scope: the two cheapest steps of "JFR as a query engine". Constant-pool predicate rewriting and a
persisted per-chunk index are out of scope here and depend on the numbers this phase produces.

## 1. The problem, with evidence

A JfrPath query for one event type decodes every event of every type.

- The Jafar backend registers one catch-all handler:
  `jfr-shell-jafar/src/main/java/io/jafar/shell/backend/impl/JafarBackend.java:125-128`.
- The evaluator compares the type name after the event has been decoded into a map:
  `shell-core/src/main/java/io/jafar/shell/jfrpath/JfrPathEvaluator.java:124-140`.
- Decoding is unconditional in both live untyped readers:
  `parser-core/src/main/java/io/jafar/parser/impl/EventStream.java:112-119` (generic) and
  `parser-codegen/src/main/java/io/jafar/parser/impl/EventStreamGenerated.java:126-127`
  (generated).
- The SPI cannot carry a filter: `EventSource.streamEvents(Path, Consumer<Event>)`,
  `shell-core/src/main/java/io/jafar/shell/backend/EventSource.java:20`.

What already exists and is reused:

- The typed parser skips types nobody registered for, before decoding:
  `parser-core/src/main/java/io/jafar/parser/impl/TypedJafarParserImpl.java:339-346`.
- The Go untyped parser has a type filter applied before decoding:
  `go-parser/jfr/parser.go:69-71` and `:528`. Its README reports "3× faster" for one type on its
  reference recording (`go-parser/README.md:213-215`). The Java untyped parser has no equivalent.
- A parser listener can skip a chunk (`onChunkStart` returning `false`,
  `parser-core/src/main/java/io/jafar/parser/internal_api/StreamingChunkParser.java:231`) or the
  rest of it after metadata (`onMetadata` returning `false`, `:251`). The untyped streams pass both
  through to a delegate listener: `EventStream.java:48-75`.
- The chunk header carries the chunk's start and duration in nanoseconds:
  `parser-core/src/main/java/io/jafar/parser/internal_api/ChunkHeader.java:46-49`.
- `startTime` reaches JfrPath as epoch nanoseconds computed from the same header:
  `parser-core/src/main/java/io/jafar/parser/impl/ChunkInfoImpl.java:68-70`.

## 2. Decisions

| # | Question | Decided |
|---|---|---|
| D1 | Deliverable of this pass | This document plus the baseline benchmark. Implementation follows review. |
| D2 | Parser API for the type filter | Per handler: `handle(TypeFilter, EventHandler)`. An event is decoded only if a registered handler accepts its type. |
| D3 | Time pruning and duration events | Sound only: results always equal the unpruned query. Lower bound prunes for every type; upper bound only for types without a `duration` field. |
| D4 | Benchmark data | Generated at benchmark setup with the running JDK. Nothing downloaded or committed. |

## 3. Baseline benchmark

### 3.1 What it is

`parser-codegen/src/main/jmh/java/io/jafar/parser/benchmark/QueryPushdownBenchmark.java`, with its
recording generator `PushdownRecording.java` next to it. It lives in `parser-codegen` because that
JMH source set can reach both untyped readers; `parser-core`'s can reach only the generic one.

The recording is `chunks` separate recordings concatenated (a JFR chunk is self-contained). Each
segment holds fixed counts, so the file has the same shape on every machine:

| Type | Events per segment | Stack trace |
|---|---|---|
| `bench.Common` | 100,000 | no |
| `bench.Sampled` | 10,000 | yes |
| `bench.Rare` | 10 | no |

Three benchmarks:

| Benchmark | What it measures |
|---|---|
| `decodeAllFilterInHandler` | Today's behaviour: decode everything, keep one type in the handler. Parameters: target type, reader (generated or generic). |
| `ceilingSkipEveryEvent` | Metadata and constant pools read, every event header walked, nothing decoded. The best a type filter can do when no event matches. |
| `ceilingSkipEveryChunk` | Chunk headers only. The best chunk pruning can do when no chunk overlaps. |

Every benchmark compares what it counted with the recording's known shape and throws on a
mismatch. That guard exists because this repository has already had a JMH class whose every
iteration failed while the Gradle task exited 0 (`doc/agents/Gates.md`, "JMH trend").

### 3.2 How to run it

```bash
mkdir -p build/jmh
```

```bash
./gradlew --no-daemon :parser-codegen:jmh -PjmhArgs="-rf json -rff build/jmh/pushdown-baseline.json QueryPushdownBenchmark"
```

A short run for checking that it works (4 segments, one iteration):

```bash
./gradlew --no-daemon :parser-codegen:jmh -PjmhArgs="-wi 0 -i 1 -f 1 -r 1s -p chunks=4 QueryPushdownBenchmark"
```

To write the same recording to a file, for timing shell queries by hand:

```bash
java -cp parser-codegen/build/classes/java/jmh io.jafar.parser.benchmark.PushdownRecording /tmp/pushdown.jfr 32
```

### 3.3 Baseline numbers

Measured 2026-11-15 at commit `dd57e12`, with the command in 3.2 and its default settings (3
warm-up and 5 measured iterations of 3 s, one fork). JDK 21.0.12, JMH 1.37, 4 processors, so the
chunk parser used 2 threads. The generated recording was 125,046,713 bytes in 32 chunks with
3,520,320 `bench.*` events.

| Benchmark | Reader | Target type | ms per parse | ± error |
|---|---|---|---|---|
| `decodeAllFilterInHandler` | generated | `bench.Rare` | 507.0 | 51.4 |
| `decodeAllFilterInHandler` | generated | `bench.Sampled` | 518.4 | 29.2 |
| `decodeAllFilterInHandler` | generated | `bench.Common` | 563.3 | 96.4 |
| `decodeAllFilterInHandler` | generic | `bench.Rare` | 726.4 | 162.5 |
| `decodeAllFilterInHandler` | generic | `bench.Sampled` | 708.4 | 135.4 |
| `decodeAllFilterInHandler` | generic | `bench.Common` | 804.5 | 192.9 |
| `ceilingSkipEveryEvent` | n/a | n/a | 45.7 | 1.7 |
| `ceilingSkipEveryChunk` | n/a | n/a | 0.71 | 0.16 |

What the table says:

- Asking for the rarest type costs the same as asking for the commonest one: 507 ms against 563 ms
  with the generated reader, inside each other's error bars. Today's cost does not depend on the
  query's selectivity.
- Not decoding at all costs 45.7 ms. For a type with no events that is 11× less than the generated
  reader's 507 ms and 16× less than the generic reader's 726 ms. That is the ceiling for step 1.
- Reading only chunk headers costs 0.71 ms, about 700× less than a full decode. That is the
  ceiling for step 2 on a query no chunk can match.

The same recording through the built shell, timed by hand (wall clock of one process, three runs
each, `jafar-shell` fat jar on JDK 25.0.5):

| Command | Seconds |
|---|---|
| `--help` (start-up only) | 0.42 |
| `show <file> "events/bench.Rare \| count()"` | 1.61, 1.47, 1.66 |
| `show <file> "events/bench.Common \| count()"` | 1.52, 1.53, 1.60 |
| `show <file> "events/bench.Rare[after(startTime, \"2030-01-01T00:00:00\")] \| count()"` | 1.52, 1.52, 1.46 |

The last query can match nothing, because every chunk ends years before its lower bound, and it
costs the same as a full scan. That is the case step 2 removes.

A failed self-check shows up as an exception in the log and an **empty** result file, while Gradle
still exits 0. This was checked by making the expected count wrong on purpose: the log held
`IllegalStateException: Counted 20 events of bench.Rare, expected 22`, the JSON was `[]`, and the
exit status was 0. Read the result file, not the exit status (rule R9).

### 3.3.1 A bug the hand timing exposed

`events/bench.Sampled | count()` returned 317233, 316995 and 316700 on three runs; the recording
holds exactly 320,000. `events/bench.Common | count()` returned 3144757, 3156218 and 3155073
instead of 3,200,000. `aggregateCount` increments a plain `long[]` from a consumer that several
chunk threads call at once (`shell-core/src/main/java/io/jafar/shell/jfrpath/Aggregations.java:388-407`).
The parser delivers every event: the benchmark counts the same file exactly with a `LongAdder`.

This is independent of pushdown and is tracked separately. It matters here because the
differential test in section 6 compares exact results, so it needs this fixed first.

### 3.4 Reading the numbers

- The gap between `decodeAllFilterInHandler` and `ceilingSkipEveryEvent` is the most step 1 can
  recover for a type with no events. For a real type the gain shrinks by the cost of decoding the
  events that do match.
- `ceilingSkipEveryChunk` is the cost floor of a fully pruned query. A query that keeps a fraction
  `f` of the chunks should cost about `f` times the unpruned query plus that floor.
- These are synthetic events. Real recordings have wider events and larger constant pools, so the
  ratios will differ; the benchmark shows the mechanism, not a promise for a given recording.

## 4. Step 1: event-type filter before decoding

### 4.1 Parser API (D2)

Add to `io.jafar.parser.api.UntypedJafarParser`:

```java
default HandlerRegistration<?> handle(TypeFilter filter, EventHandler handler) {
  throw new UnsupportedOperationException(getClass().getName() + " does not support type filters");
}
```

It is a default method that throws, the same shape as `onDecodeError`
(`UntypedJafarParser.java:251-255`), so other implementations of the interface keep compiling.
`io.jafar.parser.TypeFilter` already exists (`parser-core/src/main/java/io/jafar/parser/TypeFilter.java`)
and is a `Predicate<MetadataClass>`.

Contract:

1. A handler registered with a filter receives exactly the events whose type the filter accepts.
2. `handle(handler)` keeps its meaning: it accepts every type.
3. An event is decoded if and only if at least one currently registered handler accepts its type.
4. The filter is evaluated at most once per chunk and type, not per event. Metadata and type ids
   are chunk-local, so the decision cannot be cached across chunks.
5. The filter is called on chunk-parsing threads and must be thread-safe and free of side effects.
6. Registration and deregistration during `run()` keep their current semantics
   (`UntypedJafarParser.java:207-222`).
7. A skipped event is still reported to a parser listener's `onEvent`, as an undecodable event is
   today (`UntypedJafarParser.java:241-243`).

Behaviour change to state in the changelog: an undecodable event of a type nobody asked for no
longer fails the parse, because it is never decoded. The Go parser already behaves this way
(`parser.go:528` returns before `readValue`).

### 4.2 Parser implementation

- `HandlerRegistry<EventHandler>` entries gain the filter.
- Both live readers check, before decoding, whether any handler accepts the type:
  `EventStream.onEvent` and `EventStreamGenerated.onEvent`. The decision is cached per chunk in the
  chunk's `ParserContext`, keyed by type id, and invalidated when the handler set changes.
- Dispatch passes the decoded event only to the handlers that accept its type.
- `EventStreamLazy` and `EventStreamBaseline` are referenced only by a benchmark and tests (grep
  for their names outside their own files finds `UntypedParserBenchmark` and four test classes).
  They stay unchanged.

### 4.3 Shell SPI

Add to `io.jafar.shell.backend.EventSource` a default method that takes a selection and falls back
to filtering after decoding:

```java
default void streamEvents(Path recording, EventSelection selection, Consumer<Event> consumer)
    throws Exception {
  streamEvents(recording, ev -> { if (selection.acceptsType(ev.typeName())) consumer.accept(ev); });
}
```

`EventSelection` holds the wanted type names in step 1 and gains the time bounds in step 2.

- `JafarEventSource` overrides it and registers its handler with a filter.
- `JdkJfrBackend` inherits the default. `jdk.jfr.consumer.RecordingFile` offers `readEvent()` and
  no way to skip a type before it is read, so there is nothing to push down there.
- `BackendTck` gains the contract: for every selection, the new method delivers the same events as
  the old method filtered by type.

### 4.4 Evaluator

Every call site that knows its type set passes it. Type names match exactly, as today
(`JfrPathEvaluator.java:136`); there is no subtype matching.

There are two execution routes, and both must change (rule R2 in `AGENTS.md`):

1. Through the SPI: the `streamEvents` call sites in `JfrPathEvaluator`, `Aggregations`,
   `Decorators` and `Profiles`.
2. Around the SPI: `evaluateWithLimit` and `evaluateValuesWithLimit` open their own untyped parser
   (`JfrPathEvaluator.java:258-259`, `:592`), as does `DefaultEventSource` (`:676`). These call
   `handle(filter, handler)` directly.

`countAllEventTypes` (`JfrPathEvaluator.java:71-80`) needs every type and stays as it is.

Decorated queries read two types; their selection is the union.

### 4.5 Gates this step trips

| Gate | Consequence |
|---|---|
| API compatibility (`gradle/api-surfaces.txt`: `io.jafar.parser.api`, `io.jafar.shell.backend`) | Both additions are default methods and one new class. Expected to pass without an `API-Break:` statement; to be confirmed by running the gate. |
| Java/Go parity (`scripts/parity-check.sh`, watches `parser/impl` and `parser/internal_api`) | The change is Java-only. PR body: `Parity: not applicable — the Go parser already has Options.TypeFilter (parser.go:69)`. Record the per-handler versus per-parse difference in `go-parser/README.md`. |
| Allocation budgets | Add a budget for the filtered path: an event skipped by the filter allocates nothing. |

## 5. Step 2: chunk pruning by time range

### 5.1 What the format guarantees, and what it does not

Measured on JDK 21.0.12 with a recording made for the purpose: 189,590,217 bytes, 99 chunks,
8,628,213 events, `-XX:FlightRecorderOptions:maxchunksize=1M`, `settings=profile`, one thread
sleeping 2.5 s at a time while another emitted events. Each chunk's header range was compared with
the `startTime` of every event in it.

| Observation | Value |
|---|---|
| Events with a non-zero duration: earliest `startTime` relative to their chunk's start | 2499.014 ms **before** it (`jdk.ThreadSleep`); 98 of 99 chunks held such an event |
| Events with zero duration: any `startTime` before their chunk's start | none |
| Any event: latest `startTime` relative to their chunk's end | 0.014 ms before it; none after |

The reason for the first row: an event with a duration is written when it ends, into the chunk
that is current then. Its `startTime` can be arbitrarily far before that chunk began.

This is evidence from one JDK version and one recording, not a specification. Section 6 turns the
two invariants the rule relies on into tests.

### 5.2 The rule (D3)

For a chunk: `chunkStart = startNanos`, `chunkEnd = startNanos + duration`. For a query: `lo` and
`hi` are the inclusive bounds its predicates place on `startTime` (either may be absent).
`MARGIN` is 1 second.

1. If `duration == 0`, read the chunk. An unfinished chunk has no usable range.
2. If `lo` is present and `chunkEnd + MARGIN < lo`, skip the chunk. This holds for every type,
   because no event starts after its chunk ends.
3. If `hi` is present and `chunkStart - MARGIN > hi`, skip the chunk **only if** none of the
   selected types has a `duration` field in this chunk's metadata.
4. Otherwise read the chunk.

The query's predicates are still evaluated on every event that is read. Pruning can therefore only
remove chunks; it can never add or change a row.

Why a margin: `chunkStart` and event times come from the same tick counter and are exact relative
to each other, but `duration` is a separate header field. One second costs at most one extra chunk
on either side and removes any dependence on how exactly the JDK measures it.

Consequence to state in the user documentation: `before(startTime, T)` on a type that has a
duration, such as `jdk.JavaMonitorEnter`, gains nothing from pruning. The type-level test is
deliberately coarse: a Java-defined event that always commits with zero duration still has the
field and is treated as a duration event.

### 5.3 Extracting the bounds

From the query's bracket predicates, which are combined with AND (`MatchEngine.matchesAll`):

| Predicate shape | Contributes |
|---|---|
| `after(startTime, x)` | `lo` |
| `before(startTime, x)` | `hi` |
| `between(startTime, x, y)` | `lo` and `hi` |
| `on(startTime, date)` | `lo` and `hi` |
| `startTime > n`, `>=`, `<`, `<=`, `==` with a numeric literal | the matching bound |
| `a and b` | the intersection of both sides |
| `or`, `not`, any other function, any other field | nothing |

Only the literal path `startTime` on the queried event counts. A predicate inside a pipeline
`filter(...)`, or on a decorator's stream, contributes nothing in this phase.

`MatchEngine` already converts these arguments to nanoseconds (`MatchEngine.java:108-161`),
including the system time zone for `on`. The extractor must call the same conversion code, not a
copy of it (rule R7): extract it into one helper that both use.

### 5.4 Mechanism

No parser change. `JafarEventSource` wraps the parser with `withParserListener`
(`io.jafar.parser.api.JafarParser:85`) and a listener that:

- applies rules 1 and 2 in `onChunkStart`, which skips the chunk before its metadata is read;
- applies rule 3 in `onMetadata`, the first point at which the chunk's field lists are known.

Because nothing under `parser/impl` or `parser/internal_api` changes, step 2 does not trip the
parity gate. The Go parser's `OnChunkStart` can stop a parse but not skip one chunk
(`parser.go:73-76`); that difference already exists and is unaffected.

### 5.5 Making the pruning visible

A query that silently reads a tenth of the file is hard to trust and hard to debug (rule R3).
The event source counts chunks read and chunks pruned per query and logs them at debug level.
Whether to show them to the user is open question Q1.

## 6. Verification

| What | How |
|---|---|
| Pushdown never changes a result | A differential test: for every test recording and a fixed query set, rows with pushdown equal rows without it, compared by content. The reference run uses the system property `jafar.jfrpath.pushdown=false`. |
| Rule 3 is necessary (R5) | A recording built with `test-recordings/.../adversarial/RawChunkWriter` holding a duration event whose `startTime` precedes its chunk's start. The test must fail if rule 3 ignores the `duration` field. |
| Rule 2's invariant | A test over the generated recordings asserting no event's `startTime` exceeds its chunk's end. If a JDK ever breaks this, the test names the event type. |
| Both backends agree | The `BackendTck` contract from 4.3. |
| Both readers | The differential test runs with `jafar.untyped.generated` set to `true` and to `false`. |
| The built shell (R1) | Type the queries into the fat jar: one type, two types, a decorated query, `--limit`, each time predicate. |
| Speed | `QueryPushdownBenchmark` gains `typeFilterInParser` and `chunkPruned`, reported next to the baseline in the PR. |

Acceptance for the speed claim, measured once and reported in the PR, not enforced in CI (a
wall-clock gate is flaky or inert, `nightly-jmh.yml:6-8`):

- `bench.Rare` with the filter is within 2× of `ceilingSkipEveryEvent`.
- `bench.Common` with the filter is not slower than `decodeAllFilterInHandler` beyond the run's
  error bars.

## 7. Tasks

Each is one commit with its own tests, in dependency order.

0. Prerequisite, separate change: make the aggregations' counters thread-safe (3.3.1). Without it
   the differential test cannot compare exact results.
1. Parser: `handle(TypeFilter, EventHandler)` in both live readers, with unit tests, the
   allocation budget and the `typeFilterInParser` benchmark.
2. Shell SPI: `EventSelection`, the default method, the `JafarEventSource` override, the TCK
   contract.
3. Evaluator: pass selections on both routes; the differential test.
4. Time bounds: the shared conversion helper and the bound extractor, with unit tests per
   predicate shape.
5. Chunk pruning: the listener, the adversarial recording, the invariant test, the `chunkPruned`
   benchmark.
6. Documentation: `doc/cli/JFRPath.md` performance section, `PERFORMANCE.md` "Filter Early",
   `doc/parser/UntypedAPITutorial.md`, changelog, `go-parser/README.md` differences.
7. Nightly: a step in `nightly-jmh.yml` running `:parser-codegen:jmh` with the
   `QueryPushdownBenchmark` filter, writing `build/jmh/parser-codegen.json`.

## 8. Open questions

- **Q1.** Should pruning be visible to the user, for example as a line under the result or in an
  `explain`-style command, or only in the debug log?
- **Q2.** Should the kill switch `jafar.jfrpath.pushdown=false` be a documented user option or a
  test-only property?
- **Q3.** Should `jfr-mcp` tools that take a time window pass it as a bound, or is that a later
  step? Not examined here.

## 9. What is not verified

- No pushdown code exists; every claim in sections 4 to 7 about behaviour and gates is a design
  statement, not a test result.
- The time invariants in 5.1 were measured on one JDK version (21.0.12) and one recording. Other
  JDK versions, async-profiler recordings and recordings still being written were not examined.
- The baseline numbers come from one machine with 4 processors. The chunk parser uses
  `max(processors - 2, 1)` threads (`build.gradle:155`), so absolute times will differ elsewhere.
- The end-to-end cost of a shell query, which adds the evaluator's per-event work to the parser's,
  was not benchmarked with JMH. The hand timings in 3.3 are three wall-clock runs each, include
  JVM start-up, and carry no error estimate.
- The race in 3.3.1 was observed in `count()` only. Whether other aggregations lose updates the
  same way was not checked.
- The baseline was run once. Run-to-run variation between JMH forks was not measured; the error
  column is JMH's interval within one fork.
- The nightly workflow was not changed, so the benchmark has no trend line yet (task 7).

## 10. Addendum, 2026-11-15: decisions and corrections after review

The sections above are left as proposed. This records what was decided and found afterwards.

Decisions on section 8:

- **Q1.** Pruning is reported in the debug log only.
- **Q2.** `jafar.jfrpath.pushdown=false` is a test-only property, not a user option.
- **Q3.** `jfr-mcp` tools that take a time window pass it as a bound in this phase, with step 2.

Corrections:

- **Sections 1 and 4.3 name the wrong production path.** Event queries do not go through
  `io.jafar.shell.backend.EventSource`. `JfrPathEvaluator` has its own nested `EventSource`, and
  every production caller constructs the evaluator without one, so `DefaultEventSource` opens the
  Jafar untyped parser directly. `JfrBackend.createEventSource` is called by `BackendTck` only.
  The type selection was therefore added to `JfrPathEvaluator.EventSource`, and the backend SPI
  and the TCK were not changed.
- **Section 3.3.1 understated the race.** Every operator shared state across chunk threads, not
  only `count()`. It was fixed in btraceio/jafar#204, consumer by consumer.

Status: step 0 (the race) and step 1 (the type filter: tasks 1 to 3, and the documentation of
task 6) are implemented. Step 2 (tasks 4 and 5) and the nightly wiring (task 7) are not. Measured
results of step 1 are in the changelog entry's benchmark, `QueryPushdownBenchmark`, and in the
commit that added it.

## 11. Addendum, 2026-11-15: step 2 as implemented

Section 5 is left as proposed. Three things in it did not survive implementation.

- **Section 5.4, "No parser change", was wrong.** The architecture gate (rule a in
  `doc/agents/Gates.md`) forbids `shell-core` from using the parser's chunk listener, chunk header
  and metadata types, and section 5.2's rules need all three. Decided: the pruning lives in the
  parser behind a public API, per handler: `UntypedJafarParser.handle(TypeFilter, TimeRange,
  EventHandler)`. `shell-core` only extracts the range (`StartTimeBounds`).
- **Rule 3 of section 5.2 was unsound and was removed.** A type without a `duration` field can
  still start long before its chunk: `datadog.HeapLiveObject` in `tck-test.jfr`, 14 events, up to
  36 minutes early. Under D3 ("results always equal the unpruned query") that leaves the lower
  bound only. `before(...)` and an upper bound alone now skip nothing, for any type. The case file
  is in `doc/agents/DataShapes.md`.
- **The decision is taken after the chunk's metadata is read, not in `onChunkStart`.** The rule
  must know that every wanted type's `startTime` is a tick timestamp, which is in the metadata.

Also decided: the Go parser gets the same feature in the same change (`Options.StartTime`,
`Options.OnChunkSkipped`).

Section 4.3's `jfr-mcp` question (Q3) needed no code: the analyses behind `jfr_use` and `jfr_tsa`
already build `[startTime>=N and startTime<=M]` (`JfrAnalyses.buildTimeFilter`), which the bound
extractor reads.

Found on the way and not fixed here:

- `ChunkInfoImpl` (Java) and `ChunkInfo.Duration()` (Go) convert the header's duration as ticks; it
  is nanoseconds. See `doc/agents/DataShapes.md`.
- The schema of `jfr_use` and `jfr_tsa` describes `startTime` and `endTime` as "nanoseconds from
  recording start", but `buildTimeFilter` compares them with event start times, which are epoch
  nanoseconds, without converting.

Both were fixed afterwards, in separate commits: the chunk duration in `ChunkInfoImpl` and the Go
`ChunkInfo.Duration()`, and the time window in `JfrAnalyses.resolveTimeWindow`.

Task 7 followed: `nightly-jmh.yml` runs `QueryPushdownBenchmark` and the trend step requires
each of its methods to have a result (`jmh_trend.py --require`).

# Work budgets: deterministic counts of what a parse does

Status: built, 2026-11-26. This is decision Q5 of [perf-ab.md](perf-ab.md) (section 8, "Deterministic
work counters"; section 9, Q5). The gate's row is in [Gates.md](../agents/Gates.md).

## 1. Why

The repository gates on bytes and names, never on time ([Gates.md](../agents/Gates.md), principle
3). `perf-ab` reports timing changes on a PR but cannot block one: runner noise is larger than most
real changes. What a gate *can* hold is the amount of work a parse does, counted rather than timed:
how many events it decoded, how many varints it read, how many chunks it read. Those numbers depend
only on the parser's code and the input, so they can be budgeted exactly, like allocation bytes.

The regressions this catches are algorithmic: a type filter that decodes the events it then drops, a
time range that stops skipping chunks, `countEvents` reading payloads it does not need, constant-pool
entries resolved eagerly instead of on first use. Each of these keeps every result the same, so the
tests that check results stay green, and each costs time that a benchmark sees only as noise on a
small fixture.

## 2. How the counting works

A test-only `java.lang.instrument` agent, built from `test-support/src/agent` into
`test-support/build/work-agent/jafar-work-agent-*.jar` (manifest `Premain-Class`, ASM 9.7.1 bundled),
inserts `WorkCounters.hit(<counter>)` at fixed points in the parser classes:

- at the **entry** of a named method; or
- just before a named **call** inside a named method, where the work has no method of its own.
  `GenericValueReader.readValue` decodes events and constant-pool entries alike, so only its call
  from `EventStream.onEvent` means "an event was decoded".

Nothing in `parser-core` or `parser-codegen` main sources changes; the parser carries no counting
code. `WorkCounters` keeps one `LongAdder` per counter, because the chunk pool decodes on several
threads at once. It lives in `test-support`, which the work tests have on their classpath next to the
parser, so the inserted call resolves through the instrumented class's own loader. The agent jar
holds only the agent, the probe list and ASM.

**Generated classes are not instrumented.** parser-codegen defines one deserializer class per event
type at runtime, under per-type names. Their work is counted at the stable methods around them: a
decode at the call that runs the generated deserializer, a varint inside
`RecordingStream.readVarint`, which generated code calls like any other code. The typed parser reads
events in an anonymous listener class inside `TypedJafarParserImpl`; its numbered name is not
stable, so that probe's owner is written `TypedJafarParserImpl$*` (any nested class), and the method
and callee pin it.

**No silent zero (R3).** The JVM ignores an exception thrown by a transformer and loads the class
unchanged, which would leave a counter at 0 and let a budget of 0 pass. So:

- the agent records every probe it cannot place in a class it loads (method renamed, call removed)
  and every transform failure in the system property `jafar.work.errors`, and `WorkCounters.measure`
  fails on it, naming the probe;
- `measure` also fails when the agent is not installed (`jafar.work.agent` unset);
- `WorkProbesTest` (in `:test-support:test`) runs the agent's transformation over the compiled
  parser classes and requires every probe to be placed exactly once, whether or not a work test loads
  the class. A second decode call added next to the counted one fails it;
- `WorkAgentTest` defines every instrumented class from its new bytes and initializes it, so the
  JVM's verifier checks every method with the inserted calls.

Every run prints which probes were placed in which class (`work probes applied: …`).

## 3. The counters

Each counter is two words, so a budget key splits unambiguously into entry point and counter. The
list lives once, in `io.jafar.testsupport.work.WorkProbes` (R7); this table mirrors it.

| Counter | Probe (E = method entry, C = call inside a method) | Counts |
|---|---|---|
| `chunks.started` | E `StreamingChunkParser.processChunk(RecordingStream, ChunkHeader, ChunkParserListener, long)` | every chunk of the recording |
| `chunks.read` | E `StreamingChunkParser.readConstantPool(RecordingStream, ChunkHeader, ChunkParserListener)` | chunks whose metadata was accepted: their constant pools and events are read. `chunks.started - chunks.read` is the chunks a time range (or a listener) skipped |
| `events.read` | C `ChunkParserListener.onEvent` in `StreamingChunkParser.processChunk` | event records (type id above 1) handed to the parser listener |
| `events.decoded` | C `GenericValueReader.readValue(RecordingStream, MetadataClass)` in `EventStream.onEvent`; C `UntypedEventDeserializer.deserialize` in `EventStreamGenerated.onEvent`; C `MetadataClass.read(RecordingStream)` in `TypedJafarParserImpl$*.onEvent` | events whose payload was decoded, by the generic untyped reader, the generated untyped reader, or the typed parser. `events.read - events.decoded` is the events skipped without decoding |
| `varints.read` | E `RecordingStream.readVarint()` | every varint read, from event headers, payloads, metadata and constant pools; the generated deserializers call it too |
| `values.skipped` | E `TypeSkipper.skip(RecordingStream, TypeSkipper.Listener)` | values skipped by the skip interpreter: the fields before a header's start time, and every constant-pool entry while indexing (`skip(RecordingStream)` delegates here, so each skip counts once) |
| `constants.indexed` | C `MetadataClass.skipDirect(RecordingStream)` in `CheckpointEvent.readConstantPools` | constant-pool entries located while the checkpoints are read |
| `constants.decoded` | E `MutableConstantPool.decodeAsMap(long, long)`; C `MetadataClass.readPooled(RecordingStream)` in `MutableConstantPool.get(long)` | constant-pool entries decoded into a value, which happens lazily, on first use |

`chunks.skipped` and `events.skipped` are derived, printed by every run and not budgeted: budgeting
them would fail one regression twice.

**Considered and left out.** Bytes read: there is no single choke point (`RecordingStream` reads
through `read`, `readVarint`, `readUTF8`, `skip` and repositioning, and the generated code calls
several of them), so a count would have needed a probe per method and still miss bytes consumed by a
`position()` jump.
Metadata classes parsed (a fixed function of the fixture; nothing a parser change would move without
also moving `varints.read`). Deserializer generations (cached across chunks in shared caches, so
which thread generates first, and whether a cache from an earlier parse is reused, would make the
count depend on timing; see section 6).

## 4. Entry points and fixtures

The fixtures are the allocation gate's: the committed two-chunk `tck-test.jfr` (67,657 events) and
the generated `test-ap.jfr` (8,325 events, one chunk). Each cell parses on a fresh
`ParsingContext.create()`.

| Key prefix | Parse | Reader |
|---|---|---|
| `parser-core.untyped.tck-test.jfr` | `newUntypedParser` + one counting handler: every event decoded | generic (`EventStream`) |
| `parser-core.untyped-filtered.tck-test.jfr` | the same with `handle(TypeFilter.named("jdk.GCHeapSummary"), …)`: 1,892 decoded, 65,765 skipped | generic |
| `parser-core.count-events.tck-test.jfr` | `countEvents()`: counts from headers, nothing decoded, no payload read | generic |
| `parser-core.untyped-headers.test-ap.jfr` | `handleHeaders(type -> true, …)`, which wants start times: each header skips two fields and reads one varint | generic |
| `parser-core.untyped-time-range.tck-test.jfr` | `handle(type -> true, TimeRange.since(<end of chunk 1> + 1 s + 1 ns), …)`: chunk 1 skipped after its metadata | generic |
| `parser-codegen.untyped.tck-test.jfr` | as `parser-core.untyped` | generated (`EventStreamGenerated`) |
| `parser-codegen.untyped-filtered.tck-test.jfr` | as `parser-core.untyped-filtered` | generated |
| `parser-codegen.typed.tck-test.jfr` | `newTypedParser` + one `JFRGCHeapSummary` handler: 1,892 decoded | typed |

The parser-core tests assert `usesGeneratedReader()` is false and the parser-codegen ones that it is
true, so each cell measures the reader it names. Every cell also asserts its delivered event count,
so a swapped fixture fails on that rather than on a budget. The two generated-reader cells count
exactly what their generic twins count. The values, with how each one breaks down, are in
`gradle/work-budgets.properties`.

## 5. The budget file and the ratchet

`gradle/work-budgets.properties`, one line per counter:
`<module>.<entry>.<fixture>.<counter>=<count>`, with `#` comments recording how each cell is
measured, in the allocation file's style. `WorkBudgets.check` follows `AllocationBudgets`'
no-silent-outcome rules:

- a missing `jafar.work.budgets` property, an unreadable file, a missing key or a malformed value
  fails, naming the key and the line to add;
- a key for a counter that is no longer measured fails ("stale work budget … remove the line");
- every check prints `work <key> measured=<n> budget=<b>` for every counter;
- counts are exact (tolerance 0): measured above the budget fails "over budget by n"; measured
  below it fails "under budget by n — re-tighten: set <key>=<n>", as the allocation gate's D2 does,
  so the file always holds current counts;
- all of a cell's problems are listed in one failure, not only the first.

Each cell parses three times and requires identical counts before it checks the third, so a counter
that depends on thread timing or on an earlier parse fails as such instead of wobbling the budget.

`-Pjafar.work.update` (with `--rerun`) rewrites the cell's lines from the measurement in place,
keeping comments and order: a new counter is added after the entry's last line, a stale one is
removed. parser-core and parser-codegen may update the file at once from two JVMs, so the rewrite
holds a file lock.

**The statement.** `scripts/work-budget-check.sh --base <ref> [--body-file <PR body>]` compares the
file against the base ref. Raising a value, or adding a key with a nonzero value to an entry point
that already has keys at base, needs a `Work-Budget: <non-empty reason>` line in the PR body.
Lowering a value, adding a new entry point, adding a zero key, or leaving the file unchanged passes.
A value that is not a count is treated as a raise; deleting the file is refused, and so is an
unreadable base ref. The `work-budget` job in `pr-tests.yml` runs it on pull requests only, like
`alloc-budget`. Tests: `scripts/tests/test_work_budget_check.sh` (15 cases, run by
`scripts/tests/run_all.sh`).

Removing a key, together with its counter, is not a statement case: the counter's removal is a
`WorkProbes` change, visible in review, and `WorkBudgets` fails on a key whose counter is still
measured but missing from the file.

## 6. Determinism evidence

The counts must not depend on time, on the JIT, on the JDK, or on how many threads parse the chunks.
Measured on 2026-11-26, all eight cells:

- **5 consecutive runs** of both `workTest` tasks with `--rerun` (Ubuntu OpenJDK 21.0.12.1+1, 4
  processors): identical audit lines in all five.
- **Pool size**: `-Pjafar.activeProcessorCount=1` and `=16` (the processor-count matrix flag,
  which reaches `workTest` like any test task; chunk pool 1 and 14 threads), with the
  `availableProcessors=1` / `=16` audit value in each cell's line: identical counts.
- **JDK**: both tasks pointed at an Eclipse Adoptium 25.0.5+8-LTS launcher for one run: identical
  counts to JDK 21.
- Each run is itself three parses compared in-JVM, so every cell was counted 24 times without a
  difference.

No counter had to be dropped or redefined. The candidates that would have been nondeterministic are
the ones left out in section 3: anything counted inside the caches shared across chunks
(`UntypedDeserializerCache`, the typed `DeserializerCache`), where the first thread to need an entry
does the work. Everything counted here happens inside one chunk, on the one thread parsing it: the
constant pools and their lazily decoded entries are chunk-local, and a chunk's work does not depend
on which thread runs it or when.

So there is one cell per entry point, run in the JDK 21 `build` (`check` depends on `workTest`), and
no JDK 8 cell: the counts do not depend on the JDK, and the parser classes the probes name are the
same bytes on every JDK: parser-core's multi-release overlays (`META-INF/versions/9`, `13`, `21`)
replace `CachedStringParser`, `CustomByteBuffer`, `SplicedMappedByteBuffer`, `TypeGenerator`,
`JafarRecordedEvent` and `Values`, none of them a probed class.

## 7. Proof that it fires

Each mutation below was applied to `parser-core` main sources, `:parser-core:workTest` and
`:parser-codegen:workTest` run with `--rerun`, and the mutation reverted:

1. **The type filter decodes every event** (`UntypedJafarParserImpl.select` returns an empty handler
   array instead of `null` for a type no handler wants). Fails `parser-core.untyped-filtered` and
   `parser-codegen.untyped-filtered` ("events.decoded over budget by 65765 (measured 67657, budget
   1892)", plus `varints.read` +477,552 and `constants.decoded` +26) and `parser-core.count-events`
   ("events.decoded over budget by 67657"). `untyped-headers.test-ap.jfr` fails with an
   `IOException` instead: decoding after the start-time peek, which leaves the stream inside the
   payload.
2. **The time range skips no chunk** (`PruningListener.onMetadata` returns `true` where it decided to
   skip). Fails `parser-core.untyped-time-range`: "chunks.read over budget by 1 (measured 2, budget
   1)", `events.read` and `events.decoded` +40,058, `varints.read` +343,808.
3. **`countEvents` asks for start times it does not use** (its header handler registered with
   `startTime = true`). Fails `parser-core.count-events`: "varints.read over budget by 67657
   (measured 384338, budget 316681)" — one varint per event.

And the budget file in both directions: `parser-codegen.typed.tck-test.jfr.events.decoded` raised by
1 fails "under budget by 1 — re-tighten: set parser-codegen.typed.tck-test.jfr.events.decoded=1892";
`parser-core.count-events.tck-test.jfr.varints.read` lowered by 1 fails "over budget by 1". A probe
with a wrong descriptor (`readVarint()I`) fails every parser-core cell ("probe varints.read:…
found no method readVarint()I of io/jafar/parser/internal_api/RecordingStream") and
`WorkProbesTest` ("placed 0 times").

## 8. What this gate cannot catch

- **Constant-factor regressions.** The varint inlining fix in #250 made `readVarint` cheaper per
  call and changed no counter; undoing it would not either. Slower code that does the same number of
  the same operations is invisible here. That is `perf-ab`'s territory.
- **Work inside generated classes that does not reach a probe.** A generated deserializer that, say,
  skipped a field by reading it byte by byte instead of as one varint would move `varints.read` only
  if it stops calling `readVarint`; a change confined to how generated code uses the stream's bytes is
  not counted. There is no bytes-read counter (section 3).
- **Work in caches shared across chunks**, such as deserializer generation. Left out on purpose
  (section 6).
- **Paths no cell exercises**: the `InputStream` parse, `JafarRecordedEvent`, the shells, hdump,
  pprof and otlp. A new cell is a new entry point: free to add.
- **Code outside the probed methods doing the same work another way.** A new decode path that does
  not go through one of the three counted calls is not counted. `WorkProbesTest` requires each call
  probe to be found exactly once, which catches a second call added beside a counted one in the same
  method, but not a decode added elsewhere.

## 9. Cost

`./gradlew :parser-core:workTest --rerun :parser-codegen:workTest --rerun`: 3.8 s wall, warm daemon,
the two tasks in parallel (measured); each test class runs in about 1.2 s.

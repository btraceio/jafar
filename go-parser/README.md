# jafar go-parser

A pure Go implementation of the Jafar **untyped** JFR (JDK Flight Recorder) parser.

This module is deliberately narrow: it parses recordings and hands the events to
a callback. It carries no query language, no shell, no CLI — those live in the
Java modules of this repository (`jfr-shell`, `jfr-mcp`, …).

* Import path: `github.com/btraceio/jafar/go-parser/jfr`
* No external dependencies — Go standard library only
* Go 1.21+

```bash
go get github.com/btraceio/jafar/go-parser@v0.27.0
```

The module is versioned in step with the Java artifacts, so `go-parser@v0.27.0` is the same commit
as `io.btrace:jafar-parser:0.27.0`. Because the module lives in a subdirectory, its git tags carry
the directory prefix (`go-parser/v0.27.0`); the release workflow creates them. Only the module
subtree is downloaded — the recordings and the Java sources are not part of it.

## Why only the untyped parser

The Java parser has two front ends. The typed API binds JFR types to annotated
Java interfaces and generates a deserializer per type with ASM at run time (or
with an annotation processor at build time). That design is tied to the JVM and
does not port: Go has no equivalent of loading a generated class for an
interface discovered at run time.

The untyped API has no such dependency. It walks the chunk metadata and builds a
`map[string]any` per event, which maps onto Go directly. That is what this module
implements.

## Usage

```go
parser, err := jfr.Open("recording.jfr")
if err != nil {
    log.Fatal(err)
}

err = parser.Parse(func(e *jfr.Event) error {
    if e.Type.Name != "jdk.ExecutionSample" {
        return nil
    }
    thread, _ := jfr.GetString(e.Values, "sampledThread", "javaName")
    method, _ := jfr.GetString(e.Values, "stackTrace", "frames", 0, "method", "name", "string")
    fmt.Println(thread, method)
    return nil
})
```

Skipping the events you do not care about is much cheaper than decoding and
discarding them, and a handler that keeps nothing can let the parser recycle
what it decodes into:

```go
opts := jfr.Options{
    TypeFilter:  func(t *jfr.ClassType) bool { return t.Name == "jdk.ExecutionSample" },
    ReuseValues: true, // events are only valid until the handler returns
}
err = parser.ParseWith(opts, handler)
```

Returning `jfr.ErrStop` from the handler ends parsing successfully - the whole
recording, not only the current chunk; no event is delivered after it. Any other
error aborts it and is returned from `Parse` unchanged.

## Value model

Event fields arrive in `Event.Values` keyed by field name:

| JFR type              | Go type                                    |
|-----------------------|--------------------------------------------|
| `boolean`             | `bool`                                     |
| `byte`                | `int8`                                     |
| `char`                | `uint16`                                   |
| `short`               | `int16`                                    |
| `int`                 | `int64` (sign extended from 32 bits)       |
| `long`                | `int64`                                    |
| `float`               | `float32`                                  |
| `double`              | `float64`                                  |
| `java.lang.String`    | `string`, or `nil` for a null string       |
| inline complex value  | `map[string]any`                           |
| constant pool value   | `*jfr.Ref`                                 |
| array                 | `*jfr.Array` (see [Arrays](#arrays))       |

Fields annotated `@Timestamp(TICKS)` and `@Timespan(TICKS)` are normalised:
timestamps become nanoseconds since the epoch and timespans become nanosecond
durations, so callers never handle raw ticks. `ChunkInfo` exposes the same
conversions for values you convert yourself.

### Arrays

An array field decodes to a `*jfr.Array` whose `Elems` field holds the elements
in a slice. Its Go type follows the element type, so numeric elements are stored
unboxed and decoding them allocates nothing per element:

| `ElementType`  | `Elems`     | Elements                                             |
|----------------|-------------|------------------------------------------------------|
| `int`, `long`  | `[]int64`   | normalised like a scalar field of the type           |
| `float`        | `[]float32` |                                                      |
| `double`       | `[]float64` |                                                      |
| `short`        | `[]int16`   |                                                      |
| `char`         | `[]uint16`  |                                                      |
| anything else  | `[]any`     | each with the Go type of a scalar field of the type  |

Constant pool references are always `*Ref` elements in an `[]any`, whatever
their type. Switch on `Elems` to read the elements in bulk; `Len` and `At` work
on any array, but `At` boxes a numeric element:

```go
if arr, ok := e.Values["values"].(*jfr.Array); ok {
    switch elems := arr.Elems.(type) {
    case []int64:
        for _, v := range elems { /* int64, no allocation */ }
    case []any:
        for _, v := range elems { /* *jfr.Ref, map[string]any, string, ... */ }
    }
}
```

`Get` indexes arrays through `At`, and `ResolveDeep` turns a numeric array into
a copy of its slice and any other array into an `[]any`.

Arrays inside constant pool entries are allocated 64 at a time, as long-lived
as their pool. An event's arrays are allocated individually, so keeping one
keeps nothing else alive.

### Constant pool references

Most of a JFR recording's payload — threads, stack traces, methods, classes,
symbols — lives in per-chunk constant pools. A field pointing into one decodes to
a `*Ref` which resolves on demand and caches the result per pool entry, so a
stack trace shared by ten thousand samples is decoded once.

```go
ref := e.Values["eventThread"].(*jfr.Ref)
thread := ref.Map()          // map[string]any, or nil when the entry is missing
name := thread["javaName"]
```

`Get`, `GetString`, `GetInt` and `GetMap` walk paths of field names and array
indices, resolving references as they go. `ResolveDeep` materialises a whole
value graph into plain maps and slices, breaking the reference cycles that JFR
constant pools routinely contain.

A `*Ref` stays resolvable after `Parse` returns, and it keeps its chunk's bytes
alive for as long as it is reachable.

## Performance

`Options.ReuseValues` recycles the maps, arrays and references an event decodes
into, per event type. After the first event of a type, decoding it allocates
almost nothing:

| Workload (tck-test.jfr, 67 657 events) | ns/op | B/op | allocs/op |
|---|---:|---:|---:|
| Decode every event | 66 981 347 | 44 502 849 | 504 661 |
| Decode every event, `ReuseValues` | 37 963 037 | 3 015 857 | 217 077 |
| One event type, two fields | 23 477 273 | 12 575 478 | 116 785 |
| One event type, two fields, `ReuseValues` | 18 692 524 | 1 458 193 | 45 507 |

The same shape holds on larger recordings, and cost scales linearly with them -
a 61 MB, 2.2 M event recording costs 8.07x the time and 8.0x the allocations of
the 7.6 MB, 276 k event recording it is eight copies of:

| Workload (7.6 MB, 275 972 events) | ns/op | B/op | allocs/op |
|---|---:|---:|---:|
| Decode every event | 163 738 400 | 119 392 558 | 1 897 483 |
| Decode every event, `ReuseValues` | 114 632 376 | 11 217 370 | 1 051 854 |

It comes with a contract: an `Event` and everything reachable from its `Values`
are only valid until the handler returns. Leave it off if you keep events, and
it is off by default. Values resolved from a constant pool are cached in their
pool and stay valid either way.

### On large recordings

Measured on the benchmark corpus `./get_resources.sh` downloads, on a GitHub
`ubuntu-latest` runner (AMD EPYC 7763), one iteration per benchmark:

| Recording | Size | Events | Decode every event | …with `ReuseValues` |
|---|---:|---:|---|---|
| `tck-test.jfr` | 2.2 MiB | 67 657 | 44.8 ms, 44.5 MB, 505 k allocs | 35.3 ms, **3.0 MB**, 217 k allocs |
| `test-ap.jfr` | 171 MiB | 2 914 799 | 2.27 s, 1.26 GB, 11.7 M allocs | 1.94 s, **45 MB**, 3.0 M allocs |
| `test-jfr.jfr` | 1.65 GiB | 12 972 157 | 15.98 s, 5.89 GB, 67.9 M allocs | 14.74 s, **486 MB**, 28.6 M allocs |

Per-event cost stays flat as the recording grows — 663, 778 and 1232 ns/event
respectively — and `ReuseValues` removes 92–96% of the bytes at every size. That
is the property that matters.

One thing does not scale, and it is worth knowing before you write a consumer:

**Resolving a constant pool reference on every event pulls the whole pool into
memory.** Entries are cached for the life of the chunk, so reading one stack
frame per event materialises the entire stack trace, frame, method and symbol
graph and keeps it there. The same one-line access costs 23 ms and 153 k
allocations on `tck-test.jfr` (14 202 events), **12 s and 11 GB** on
`test-ap.jfr` (2.9 M events), and **69 s and 69 GB** on `test-jfr.jfr` (10.7 M
events). `ResolveDeep` additionally retains a materialised copy per entry, and
exhausted a 16 GB CI runner on the 171 MiB recording.

The caching is what makes the common case fast — a stack trace shared by ten
thousand events is decoded once — but it is unbounded and scales with the pool,
not with the work you asked for. If you need stack traces from a subset of
events, filter the events down first (`TypeFilter`, or a predicate in the
handler) and resolve only what survives. If you need them from all events of a
multi-gigabyte recording, budget the memory for the whole pool.

Two other things matter more than they look:

* **Filter early.** `TypeFilter` skips an event before it is decoded. On the
  reference recording, filtering to one type is 3× faster than decoding
  everything and then ignoring most of it.
* **`ResolveDeep` is memoised but not free.** It materialises everything an
  event transitively references. Prefer `Get`/`GetString`/`GetInt` for the few
  fields you actually need.

Run `go test -bench . ./...` to reproduce. Every benchmark runs once per
available recording, and picks its workload event types per recording rather
than by name, so the numbers stay comparable across files. Run
`./gradlew :test-recordings:generateTestRecordings` (or `./get_resources.sh` for
the real ones) from the repository root first to add the larger recordings;
set `JAFAR_BENCH_JFR` to benchmark one specific file, for example one made with
`./gradlew :test-recordings:generateBenchmarkRecording -Pchunks=400 -Poutput=/tmp/large.jfr`.

## Error handling

Problems that leave the parser without a resynchronisation point — a bad chunk
magic, an out-of-range chunk size, undecodable chunk metadata — always abort
parsing.

Recoverable problems — a checkpoint chain that cannot be followed, a constant
pool referring to an undeclared type or holding an entry that cannot be read, a
single event that fails to decode (a reference to a string pool entry that does
not exist included) — are reported through `Options.OnError`. An unreadable pool
entry ends that checkpoint: the entries indexed before it stay usable, and each
reference to a later one is reported as an event error. When `OnError` is nil they are ignored and
parsing continues, which is what reading a truncated or non-conforming recording
on a best-effort basis needs. Pass `jfr.FailOnError` to turn them into failures:

```go
err := parser.ParseWith(jfr.Options{OnError: jfr.FailOnError}, handler)
```

A checkpoint problem that can leave entries unindexed is remembered for the
chunk. From then on, an event holding a reference from any pooled field (a
string, a thread, a stack trace, a class) to an entry missing from its pool
fails to decode, for example with `constant pool reference 3 to test.Node does
not resolve: the pool has no such entry (constant pool indexing stopped early in
this chunk: ...)`. The indexing failure is wrapped, so `errors.Is`/`errors.As`
reach it. The indices writers use for an absent value stay a `*Ref` that
resolves to nil: 0 from HotSpot, and `math.MinInt64` from the JMC writer, which
writes a null value that way. So does a dangling reference in a chunk that was
indexed completely, as in the JDK's reader (strings excepted: a string
reference that does not resolve is always an error).

An indexed entry whose own pooled field refers to an abandoned entry is found
when it is resolved rather than when the event is decoded: it cannot be decoded,
so `Ref.Value` returns nil and `Ref.Err` returns the error, which wraps the
indexing failure. The error is kept per entry and returned again without
decoding the entry again. `ResolveDeep` resolves such an entry to nil, as it
does any entry that cannot be decoded; check `Err` on the references where that
matters. Pinned by `TestReferenceToUnindexedPoolEntryIsReportedNotResolvedToNil`
(with `ReuseValues` off and on),
`TestUndeclaredPoolTypeFailsEveryReferenceIntoTheAbandonedPools`,
`TestUndecodableEntryFailsOnResolutionTheSameWayEachTime` and
`TestDanglingReferenceInIntactChunkResolvesToNil`, which read the same
recordings as the Java parser's `UnindexedPoolReferenceTest`
([jfr/testdata](jfr/testdata/README.md)).

## Differences from the Java parser

* **Simple-type references are not collapsed.** Java now offers
  `Values.resolvedDeepCollapsed`, which turns a reference to a JFR simple type into its scalar.
  Here such a reference still decodes to the one-entry map `{field: value}` and no helper collapses
  it; not ported yet (btraceio/jafar#108).
* **Untyped only.** No typed API, no code generation, no annotation processor.
* **Sequential.** The Java parser hands chunks to a thread pool and calls
  handlers from those threads. This one decodes chunks in recording order and
  calls the handler on the calling goroutine, so events arrive in file order.
  It also makes stopping exact: `ErrStop` and Java's `Control.abort()` both end
  the whole recording, but in Java another chunk's thread may still deliver the
  one event it was already dispatching, while here nothing follows `ErrStop`.
* **In memory.** `Open` reads the whole file rather than memory-mapping it. Use
  `NewParser` when you already hold the bytes.
* **An undecodable event is skipped by default.** Here a nil `Options.OnError`
  ignores it and parsing resumes at the next event, using the event size the
  parser has already read. The Java untyped parser fails the parse by default
  (`EventDecodingException`, naming the type, chunk and position) and skips only
  when `UntypedJafarParser.onDecodeError` returns `true`, the counterpart of an
  `OnError` that returns nil. Neither delivers the bad event, even partially.
  Pinned by `TestUndecodableEventIsReportedAndSkipped` here and
  `EventDecodeFailureTest` in `parser-core`.
* **A corrupted constant pool entry is recoverable here.** Go reports it
  through `Options.OnError` and keeps the entries indexed before it; Java fails
  the parse, naming the pool and index, even with a decode error handler, since
  that handler is about one event. The same holds for a pool of a type the
  chunk metadata does not declare. So only Go can see a partially indexed pool,
  and only Go needs the per-reference check described under
  [Error handling](#error-handling).
* **An undecodable constant pool entry resolves to nil.** Resolving a reference
  to an entry that cannot be decoded returns nil here, with the reason in
  `Ref.Err`. The Java parser throws from `getValue()` instead. Both decode such
  an entry once and keep the failure: later resolutions return the same error
  here and rethrow the same exception there. Pinned by
  `TestReferenceToUnindexedPoolEntryIsReportedNotResolvedToNil` here and
  `UnindexedPoolReferenceTest` in `parser-core`. Both refuse to skip an entry whose string
  encoding is unknown, and both report a reference to a missing string entry
  instead of reading it as null. Pinned by
  `TestCorruptedPoolStringIsReportedNotMisaligned` and
  `TestDanglingStringReferenceIsReported` here.
* **A misframed checkpoint is recoverable here.** Both parsers check that a
  checkpoint's constant pools end exactly where the event's size says, as the
  JDK's own reader does, and both name the checkpoint (`checkpoint at N:
  constant pools end at X, but the event size S says it ends at Y`). This
  parser also stops at an entry that runs past the event. It reports either
  through `Options.OnError` and carries on: since the entry that went wrong
  cannot be known, a checkpoint whose pools end short keeps none of the entries
  it indexed, and references into them fail their events with the framing error
  as the cause. The Java parser fails the parse. Pinned by
  `TestCheckpointPoolsEndingShortOfTheEventSizeAreReported` here and
  `CheckpointFramingTest` in `parser-core`.
* **Type IDs are chunk-scoped**, as they are in the format. A recording whose
  chunks come from different producers can map the same type ID to different
  type names, and each chunk is decoded against its own metadata.
* **`ResolveDeep` breaks reference cycles and memoises what it resolves.** The
  Java `Values.resolvedDeep` does neither: it re-materialises the shared
  constant pool graph for every event, and follows a cyclic class/class loader
  graph until the stack runs out. No recording in this repository contains such
  a cycle, so the Java behaviour is a latent risk rather than an observed bug.
* **Decoded strings are cached per chunk**, so a recording that repeats a string
  - a GC phase name, a flag name, a thread state - holds one copy of it rather
  than one per event. This is Jafar's `CachedStringParser` widened from its
  single entry to a small direct-mapped table.
* **Unboxed arrays are typed slices.** The Java parser stores `int`/`long`,
  `float`, `double`, `short` and `char` array elements in a `long[]`,
  `float[]`, `double[]`, `short[]` or `char[]` behind `ArrayType`; this one
  stores an `[]int64`, `[]float32`, `[]float64`, `[]int16` or `[]uint16` in
  `Array.Elems`. Same elements, same values; neither boxes them. `byte` and
  `boolean` elements go in an `[]any` (Java: `byte[]`, `boolean[]` filled by
  boxing): converting a single-byte value to an interface never allocates in
  Go, and every `Byte` and `Boolean` box is cached in Java.
* **Optimizations that do not port.** The typed API and the generated untyped
  deserializers (`UntypedStrategy`, `LazyEventMap`) rest on run-time bytecode
  generation. `LazyEventMap` in particular works because Java lets a lazy object
  implement `Map`; Go's `map[string]any` is a builtin, so the equivalent would
  have to change the type of `Event.Values`. `ReuseValues` takes the other route
  and recycles the map instead. Jafar's reusable `MultiTypeStack` has no Go
  counterpart either: recursion over the goroutine stack allocates nothing.

## Not supported

* Chunks that do not use the compressed (varint) integer encoding. Every
  JVM-written recording does; the parser reports the chunk feature flags when
  one does not.
* Compressed recording files (gzip, LZ4, zstd). The parser detects them and says
  how to decompress.

## Development

```bash
cd go-parser
go test ./...            # unit tests plus the recordings checked into the repo
go test -short ./...     # skips the slowest end-to-end comparison
go test -bench . ./...   # throughput and allocation benchmarks
go vet ./...
gofmt -l .
```

The tests and benchmarks pick up whatever recordings are present.
`./gradlew :test-recordings:generateTestRecordings` in the repository root
generates them into `parser-core/src/test/resources/` and
`demo/src/test/resources/` (`./get_resources.sh` downloads the real ones to the
same place); both the suite and the benchmarks find them there and report each
file separately. Without them they fall back to the recordings checked into the
repository.

The **Go Parser Benchmarks** GitHub Actions workflow does this download and runs
the benchmarks on demand, weekly, and on pushes to `main`, publishing the numbers
to the job summary. Run it manually to benchmark a branch, optionally against a
baseline ref for a benchstat diff. A branch that has not been merged yet cannot
be dispatched - GitHub only offers `workflow_dispatch` for workflows already on
the default branch - so push it as `bench/<something>`, which the workflow also
triggers on.

The tests are in two groups. `parser_test.go` and `reader_test.go` build JFR
chunks byte by byte (see `testrecording_test.go`) and need no fixtures.
`recording_test.go` parses the recordings checked into the repository —
`jfr-shell-tck/src/main/resources/tck-test.jfr` and the stripped dd-trace-java
fixtures under `parser-core/src/test/resources/` — and skips itself when the
module is built outside the repository tree.

The event counts pinned by `recording_test.go` were cross-checked against the
JDK's own `jfr summary` tool.

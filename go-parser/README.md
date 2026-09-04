# jafar go-parser

A pure Go implementation of the Jafar **untyped** JFR (JDK Flight Recorder) parser.

This module is deliberately narrow: it parses recordings and hands the events to
a callback. It carries no query language, no shell, no CLI — those live in the
Java modules of this repository (`jfr-shell`, `jfr-mcp`, …).

* Import path: `github.com/btraceio/jafar/go-parser/jfr`
* No external dependencies — Go standard library only
* Go 1.21+

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

Returning `jfr.ErrStop` from the handler ends parsing successfully; any other
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
| array                 | `*jfr.Array`                               |

Fields annotated `@Timestamp(TICKS)` and `@Timespan(TICKS)` are normalised:
timestamps become nanoseconds since the epoch and timespans become nanosecond
durations, so callers never handle raw ticks. `ChunkInfo` exposes the same
conversions for values you convert yourself.

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
`./get_resources.sh` from the repository root first to add the larger
recordings; set `JAFAR_BENCH_JFR` to benchmark one specific file.

## Error handling

Problems that leave the parser without a resynchronisation point — a bad chunk
magic, an out-of-range chunk size, undecodable chunk metadata — always abort
parsing.

Recoverable problems — a checkpoint chain that cannot be followed, a constant
pool referring to an undeclared type, a single event that fails to decode — are
reported through `Options.OnError`. When `OnError` is nil they are ignored and
parsing continues, which is what reading a truncated or non-conforming recording
on a best-effort basis needs. Pass `jfr.FailOnError` to turn them into failures:

```go
err := parser.ParseWith(jfr.Options{OnError: jfr.FailOnError}, handler)
```

## Differences from the Java parser

* **Untyped only.** No typed API, no code generation, no annotation processor.
* **Sequential.** The Java parser hands chunks to a thread pool and calls
  handlers from those threads. This one decodes chunks in recording order and
  calls the handler on the calling goroutine, so events arrive in file order.
* **In memory.** `Open` reads the whole file rather than memory-mapping it. Use
  `NewParser` when you already hold the bytes.
* **Framing is enforced.** Constant pool entries have to lie within their
  checkpoint event; the Java parser does not check this. It only matters for
  malformed recordings, where this parser drops the pool rather than indexing
  entries at garbage offsets.
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
`./get_resources.sh` in the repository root downloads the larger ones from
Dropbox into `parser-core/src/test/resources/` and `demo/src/test/resources/`;
both the suite and the benchmarks find them there and report each file
separately. Without them they fall back to the recordings checked into the
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

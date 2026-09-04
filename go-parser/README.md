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
discarding them:

```go
opts := jfr.Options{
    TypeFilter: func(t *jfr.ClassType) bool { return t.Name == "jdk.ExecutionSample" },
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
go test -bench . ./...   # throughput benchmarks
go vet ./...
gofmt -l .
```

The tests are in two groups. `parser_test.go` and `reader_test.go` build JFR
chunks byte by byte (see `testrecording_test.go`) and need no fixtures.
`recording_test.go` parses the recordings checked into the repository —
`jfr-shell-tck/src/main/resources/tck-test.jfr` and the stripped dd-trace-java
fixtures under `parser-core/src/test/resources/` — and skips itself when the
module is built outside the repository tree.

The event counts pinned by `recording_test.go` were cross-checked against the
JDK's own `jfr summary` tool.

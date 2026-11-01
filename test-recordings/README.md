# test-recordings

Generates the JFR recordings the test suites and benchmarks read, so neither depends on
downloading them. A build-time tool: it is not published and no library depends on it.

It uses the JMC flight recorder writer, one chunk at a time. A JFR file is a sequence of
self-contained chunks, so a recording of any size is written by appending chunks, and only one chunk
is in memory at once. The writer allocates heavily (about 33 KB per event, measured), which is why
writing a large recording as a single chunk fails.

## Test recordings

`./gradlew :test-recordings:generateTestRecordings` writes the files where the suites have always
read them:

| File | Stands in for | What the suites need from it |
|---|---|---|
| `demo/src/test/resources/test-ap.jfr` | an async-profiler recording | `jdk.ExecutionSample` with `sampledThread`, `state` and multi-frame stack traces that share constant-pool ids; `jdk.CPULoad` (`machineTotal` in 0..1); `jdk.GCHeapSummary` (`when` = "After GC", inline `heapSpace`); `jdk.InitialSystemProperty` with `java.home` as an inline string (the scrubber rewrites inline strings in place) |
| `demo/src/test/resources/test-jfr.jfr` | a JDK recording | `jdk.ExecutionSample`, `jdk.GCHeapSummary`; type ids that differ from `test-ap.jfr`'s (`ParsingContextReuseTest`) |
| `demo/src/test/resources/test-dd.jfr` | a Datadog profiler recording | **a copy of the checked-in `jfr-shell-tck/src/main/resources/tck-test.jfr`**, a real Datadog profiler recording. jfr-mcp's end-to-end tests pin its shape (204 available types, two chunks, two `jdk.JVMInformation` events), which a generated file would have to imitate value by value. The generated `dd` shape remains for benchmarks |
| `parser-core/src/test/resources/test-{ap,jfr,dd}.jfr` | copies | `test-dd.jfr` there is a copy of `test-jfr.jfr`, as `get_resources.sh` has always made it |

Every test task, and every test-resource task, depends on this task (root `build.gradle`), so a plain
`./gradlew test` generates them first. Output is deterministic: fixed seeds, a fixed chunk start
(2024-01-01T00:00:00Z) and fixed tick base.

Each generated recording is a single chunk, like the recordings the suites were written against. Some tests
assume that (an abort in one chunk does not stop chunks parsing in parallel; constant-pool ids are
chunk-local), and jfr-shell's constant-pool source races across parallel chunks. Multi-chunk output
is exercised by the benchmark mode and this module's tests.

### Real recordings

The real recordings are optional: `./get_resources.sh` downloads them. Each generated file has a
`.generated` marker next to it. `get_resources.sh` replaces marked files with real ones and removes
the markers; the generator leaves an unmarked recording alone and says so in the build output
("Using real recording …"). To go back to generated files, delete the real ones.

CI never downloads, so it always tests against the generated recordings.

## Benchmark recordings

```bash
./gradlew :test-recordings:generateBenchmarkRecording -Pchunks=400 -PsamplesPerChunk=200000 \
    -Pshape=ap -Poutput=/tmp/large.jfr
```

Defaults: 50 chunks, 200,000 samples per chunk, `ap` shape, written to
`test-recordings/build/recordings/benchmark.jfr`. A 200,000-sample `ap` chunk is about 2.7 MB and
takes about 1.8 s (measured: 10 chunks, 26,983,208 bytes, 18.0 s), so 400 chunks is about 1.1 GB in
about 12 minutes. The Go parser benchmark workflow generates one this size and points
`JAFAR_BENCH_JFR` at it.

## JMC writer limits worth knowing

- Every string set through the writer's API is pooled. `ChunkContent.inlineString` writes one
  inline using the writer's `TypedValueImpl` constructor, which is outside its `api` package.
- Custom types get a constant pool unless registered with `Types.getOrAdd(name, false, …)`, which is
  how `jdk.types.VirtualSpace` is written inline, as the JDK writes it.
- `withStartTicks(0)` is ignored (0 means unset); the generator uses a non-zero tick base.

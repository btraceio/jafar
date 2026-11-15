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
chunk-local). Multi-chunk output is exercised by the benchmark mode, this module's tests, the
two-chunk `tck-test.jfr` the backend TCK runs against, and jfr-shell's
`ConstantPoolChunkLocalityTest`, which concatenates a recording's chunk to make one.

### Real recordings

The real recordings are optional: `./get_resources.sh` downloads them. Each generated file has a
`.generated` marker next to it. `get_resources.sh` replaces marked files with real ones and removes
the markers; the generator leaves an unmarked recording alone and says so in the build output
("Using real recording …"). To go back to generated files, delete the real ones.

CI never downloads, so it always tests against the generated recordings.

## Adversarial recordings

`./gradlew :test-recordings:generateAdversarialRecordings` writes six deterministic adversarial recordings
for the fuzz corpus: hand-built shapes that target the parsing paths random bit flips cannot reach (the
measured baseline: 0 crashes in 200 random mutations). The cases live in the
`io.jafar.testrecordings.adversarial.AdversarialRecordings` enum, written by a byte-level chunk writer
(`RawChunkWriter` — a deliberate copy of parser-core's test `SyntheticRecording`, because this module cannot
see parser-core's test classes). Each case is built only from fixed constants — no clocks, no randomness —
so generating twice yields identical bytes, and `AdversarialRecordingsTest` fails when a committed seed
drifts from the generator output, naming the file and the regeneration command.

| Seed | Corrupts | Intended path (stack-verified) | Pinned outcome |
|---|---|---|---|
| `adv-valid-base` | nothing (control) | parses and delivers events | 4 events delivered exactly |
| `adv-string-table-size` | the metadata string table declares 25 entries while the element tree references index 108 | the string lookup in `MetadataEvent.readElement` | legal escape: `IOException` "String index 108 out of bounds (string table size: 25)" |
| `adv-huge-length` | a UTF-8 string length of `1<<24` (16 MiB) declared against a ~405-byte recording, with no payload behind it | the length check in `ParsingUtils.readUTF8`, before anything is allocated | legal escape: `IOException` "String of 16777216 byte(s) exceeds the remaining 278 byte(s)" |
| `adv-bad-cp-id` | a constant-pool entry of an undeclared type | `CheckpointEvent.readConstantPools` | legal escape: `IOException` "the type is not declared in the chunk metadata" |
| `adv-deep-metadata` | `'deep'` elements nested 200,000 deep | the nesting bound in `MetadataEvent.readElement` (formerly the SOE vector) | legal escape: `IOException` "Metadata element nesting exceeds 64 levels" |
| `adv-bad-chunk-offsets` | chunk 1's size field disagrees with chunk 2's offset | the next `ChunkHeader` read | legal escape: `IOException` "Invalid JFR Magic Number: 20001" |

The seeds are written to `parser-core/src/test/resources/io/jafar/parser/fuzz/UntypedParserFuzzTestInputs/parseUntyped/`
— Jazzer's regression-inputs directory, so every ordinary `./gradlew :parser-core:test` run replays them.
Each file's specific outcome is additionally asserted by parser-core's `AdversarialOutcomeTest` ("did not
crash" is not an outcome). No seed escapes the fuzz oracle's cause-chain contract today, so
`parser-core/src/test/resources/io/jafar/parser/fuzz/known-escapes.txt` (shrink-only; a stale entry fails)
is empty: `string-table-size`, `huge-length` and `deep-metadata` were pinned there until the parser checked
what they corrupt, and the oracle then forced the entries out.

The seed files carry **no `.jfr` extension**: `.gitignore` ignores `*.jfr`, and the seeds must be committed —
`git check-ignore` exits 1 for `adv-valid-base`. Jazzer replays every file in the directory regardless of its
name. Separately, the `huge-length` case declares `1<<24` and not the exact `2^31`: when the case was
written, a 2^31 declared length narrowed to a negative int and drove an uninterruptible loop in the parser.
The parser now rejects any declared string length beyond the bytes that are left, 2^31 included; the seed
keeps `1<<24` because its bytes are pinned by sha256 — see [the gates document](../doc/agents/Gates.md).

For one-off dumps, `TestRecordingGenerator` has a manual mode: `adversarial <output-dir>`.

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

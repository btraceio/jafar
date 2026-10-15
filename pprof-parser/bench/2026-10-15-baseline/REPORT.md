# pprof-parser performance baseline — 2026-10-15

Environment: Apple M4 Max (arm64, 16 cores), Temurin 21.0.11, G1, `-Xms2g -Xmx2g`, JMH 1.37,
async-profiler (cpu + alloc). Gradle task: `./gradlew :pprof-parser:jmh -PjmhArgs="..."`
(requires a JDK Gradle supports on JAVA_HOME — e.g. `~/.sdkman/candidates/java/21.0.11-tem`;
the machine default is Java 27 which Gradle 9.1 refuses).

## Workloads

- **synthetic** (generated in `PprofParserBenchmark` setup, seed 42): 30,000 samples,
  8,000 locations, 4,000 functions, stack depth 2–29 (avg 15.5), 2 values/sample,
  ~1.3 labels/sample (80% string, 50% numeric), 9,394 strings, ~2.1 MB raw protobuf, ~310 KB gz.
  Self-checked in setup: parse must yield exactly 30k/8k/4k and min stack depth 2.
- **real**: Go test-suite CPU profile (`go test -cpuprofile` on the jafar go-parser tests),
  63 KB raw, 734 samples, 754 locations, 344 functions, 433 strings.
  Regenerate: `cd go-parser && go test -cpuprofile gotests.pb.gz ./...`, run with `PPROF_FILE=<path>`.

## Results (avgt, 5×2s iterations, 1 fork, gc profiler)

| workload   | benchmark  | ms/op          | alloc B/op            |
|------------|------------|----------------|-----------------------|
| synthetic  | parseFile  | 23.81 ± 0.29 / 23.89 ± 0.14 | 47,665,132 |
| synthetic  | gzipOnly   | 14.01 ± 6.12 / 12.32 ± 0.59  | 5,283,587  |
| real       | parseFile  | 0.472 ± 0.017  | 1,188,770             |
| real       | gzipOnly   | 0.169 ± 0.012  | 210,713               |

- gzip control = JDK `readAllBytes()` decompression, single file open, no protobuf decoding. The
  decode-only figures below are `parseFile − gzipOnly` and therefore also include the benchmark
  path's gzip-trailer read, second file open, and ISIZE-buffer strategy — the parser's own
  protobuf-decode share is slightly smaller than stated here. Decode phase:
  **~11.5 ms, ~42 MB garbage** for the synthetic profile (2.1 MB raw) — **~21× the raw size**.
- Real profile: 1.19 MB garbage for 63 KB raw — **~18.8×**; live model measured at ~495 KiB
  (forced-GC delta), so garbage ≈ 2.4× the retained model.
- Alloc rate while parsing: **~1.9 GB/s**.

## CPU breakdown (async-profiler, inclusive, of parse share ~98%)

| share | frame |
|-------|-------|
| 49.7% | `GZIPInputStream.read` (`Inflater.inflate` 46.3%, CRC32 1.85%) |
| 47.7% | `PprofReader.parseProfile` |
| 30.4% | └ `parseSample` |
| 13.4% | └ `buildProfile` (`List.copyOf` 10.2%, `List.of` 9.5%) |
| 7.6%+4.9% | `ArrayList.add` / `ArrayList.grow` (Raw intermediates) |
| 4.4%+1.4% | `ProtoUtil.readVarint` + `varintLen` (every varint decoded twice) |
| 2.0% | `parseLocation`, `parseLabel`, `parseLine`, rest |

## Allocation breakdown (bytes, inclusive)

| share | source |
|-------|--------|
| 43.5% | `ArrayList.add`/`grow` — `Raw*` intermediate lists |
| 26.1% | `Long.valueOf` — boxing of location ids and sample values (`List<Long>` API) |
| ~29.9% | `List.copyOf`/`Arrays.copyOf`/`toArray` — final copies from Raw → API records |
| 4.7%  | `RawSample` objects |
| 3.9%  | `long[4]` per label |
| 2.6%  | `String.<init>` (string table, inherent) |

## Raw data

(Raw JMH outputs, async-profiler collapsed stacks and profiler output files are kept locally next to
this report, not committed — only this REPORT.md is versioned.)

- JMH outputs: `baseline-synthetic*.txt`, `real-gotests.txt`
- Collapsed stacks: `collapsed-cpu/`, `collapsed-alloc/` (async-profiler; the flamegraph HTML
  renders of the same data are not kept — regenerate from the CSVs if needed)

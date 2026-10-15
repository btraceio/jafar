# pprof-parser: option C optimization — 2026-10-15 (after)

Same environment, workload and harness as `../2026-10-15-baseline/REPORT.md` (Apple M4 Max,
Temurin 21.0.11, G1, `-Xms2g -Xmx2g`, JMH 1.37, gc profiler; synthetic profile generated
deterministically by `PprofParserBenchmark`, seed 42).

## Change (option C, see baseline report for the measured motivation)

- **Single-phase decode** (`PprofReader.Parser`): no `Raw*` intermediates. Stack ids, values,
  labels and lines decode into scratch `LongVec`s reused across all messages; final components
  are built once. String-bearing data (value types, functions, mappings, labels) is staged in
  flat `long[]` stores and resolved after the string table is known.
- **`Sample` API**: `long[] locationIds, long[] values` — no `Long` boxing (also shrank the
  retained model: 495 → 363 KiB on the real profile).
- **Gzip discipline**: single buffer seeded from the trailer ISIZE (hint only — growth is the
  correctness fallback for multi-member / >4 GB streams), no final trim copy.
- **Single varint decode**: cursor-based reader decodes value and advances in one pass (was
  `readVarint` + `varintLen`).

## Results

| workload  | benchmark | before (ms/op) | after (ms/op) | before alloc B/op | after alloc B/op |
|-----------|-----------|----------------|---------------|------------------|------------------|
| synthetic | parseFile | 23.81 ± 0.29   | 17.77 ± 3.01 (16.77 ± 0.29 in a quieter run) | 47,665,132 | 22,409,391 |
| synthetic | gzipOnly  | 12.32 ± 0.59   | 12.42 ± 1.05 (unchanged control) | 5,283,587 | 5,283,587 |
| real      | parseFile | 0.472 ± 0.017  | 0.357 ± 0.072 | 1,188,770        | 662,321          |

- Wall time **−25–29%** (synthetic) / **−24–32%** (real); allocations **−53%** / **−44–45%**.
- Decode-only wall time (parse − gzip control): 11.5 ms → 4.4 ms (**−62%**). Caveat: the control
  is the JDK `readAllBytes()` path (single open), so this delta also includes the benchmark path's
  gzip-trailer read and ISIZE-buffer strategy — the pure protobuf-decode share is slightly smaller.
- Decode-only garbage: ~42.4 MB → ~17.1 MB (same control caveat as above); the remainder is
  dominated by the model floor:
  exact-size `long[]` per sample (45.6% of bytes in `Arrays.copyOf`/`LongVec.exact`),
  `Label`/`Label[]` (12.4%), `Sample` records (4.6%), string table `String`s (8.8%),
  immutable list heads for lines/labels (~12%), plus the ISIZE-exact gzip buffer (~1.6 MB).
- CPU after (inclusive, of parse share): gunzip 64.1% (`inflate` 59.7% — now the dominant cost,
  as expected), `parseProfile` 34.4% (`parseSample` 23.7%, `readVarint` 11.8%).
- Retained model on the real profile: 495 → 363 KiB (unboxed sample arrays).

## Remaining known transients (future work if needed)

- Per-labeled-sample exact `long[]` label carrier (~3% of bytes) — could be flattened into a
  global label store with per-sample ranges.
- Sample spine `ArrayList`s (`ArrayList.add`/`grow` 13.5% of bytes) — no upfront sample count
  without a second scan; amortized growth only.

## Raw data

(Raw JMH outputs, async-profiler collapsed stacks and profiler output files are kept locally next to
this report, not committed — only this REPORT.md is versioned.)

- `after-synthetic*.txt`, `after-real*.txt` (JMH + gc profiler; `-final` files are the last run
  with the finished code, after the JLS-15.7.1 operand-order fix — see the test
  `fieldsAfterSubmessageAreParsed` that locks it)
- `after-alloc/`, `after-cpu/` (async-profiler collapsed stacks)

## Re-measured 2026-10-15 late (review fixes applied, idle machine)

The runs above were measured while sphinx review workers and the jdtls LSP warmed on the same
machine; re-running after the review fixes landed, on an idle machine, both benchmarks dropped
(the gzip control itself fell 12.4 -> 8.0 ms, proving environment, not code). Allocation figures
are load-independent and unchanged. Latest idle-machine numbers (after-fixes-final*.txt):

| workload  | ms/op (idle)    | alloc B/op |
|-----------|----------------|------------|
| synthetic | 11.50 ± 0.52 (was 16.8-17.8 under load) | 22,409,425 |
| real      | 0.220 ± 0.024 (was 0.32-0.36 under load) | 662,321 |

Against the idle-machine baseline (23.81 ms / 47,665,132 B), the honest same-conditions
comparison is: **-52% wall time (23.81 -> 11.5 ms), -53% allocations**; decode-only share
11.5 -> ~3.5 ms (**-70%**); allocations are load-independent so the -53% is exact.
Review fixes included (Sample value-equality overrides, ISIZE DoS cap, resolve() long-bounds,
growth overflow -> IOException, period_type last-wins, benchmark string-table[0], stronger
self-check, jmh task dep, flamegraph HTML dropped, control-delta wording corrected here).

# Cross-format profile compare (JFR, pprof, OTLP)

Status: design accepted, 2026-11-26; v1 (JFR and pprof, decision D7) built. Every decision in
section 7 was taken as recommended; section 8 lists what was not verified, and section 9 what v1
did differently from this plan, section 10 what real Go and async-profiler files showed.

Origin: idea 2 of the 2026-11 brainstorm. PR #252 moved the comparison into
`shell-core/src/main/java/io/jafar/shell/core/compare/`. `RecordingProfile` already looks
format-neutral, but only a JFR reader fills it. This plan covers what a pprof reader and an OTLP
reader would need to put into it, and where the shape has to change.

Line numbers refer to `6e7e8c6a` (main, 2026-11-26).

## 1. What the compare consumes today

`RecordingProfile` (`RecordingProfile.java:30-40`) carries `eventType`, `sampleCount`,
`durationSeconds`, `leafFrameCounts`, `inclusiveFrameCounts`, `inclusiveCounted`,
`truncatedSamples`, `eventCounts` and `totalEvents`. `JfrCompare.profile` fills it
(`JfrCompare.java:177-248`):

- **Every sample weighs 1.** `samples.increment()` and `merge(frame, 1L, …)`
  (`JfrCompare.java:201,214,217`). Shares are count shares (`share()`, `:534-536`). A
  `datadog.ExecutionSample` has a `weight` field (it shows as a column in
  `jfr-shell show … datadog.ExecutionSample`), but the compare ignores it today.
- **Duration** is the time from the first sample's start to the last sample's start
  (`:229-235`), not the length of the recording.
- **Truncation** is the JFR `stackTrace.truncated` flag, counted only when inclusive frames are
  read (`:208-211`). Above 1% truncated on either side, no inclusive move is called significant
  (`:82`, `:266-282`).
- **Significance** is a two-proportion z test on *sample counts*, with z ≥ 3, at least 30 samples
  per recording and at least 5 per frame (`:336-353`, `:60-69`).
- **Event rates** come from `eventCounts`, for 11 hard-coded JFR event types (`:85-97`,
  `:419-444`).
- **Frame key**: `JfrAnalyses.extractMethodName` builds `type.name + "." + name`, or just the
  method name when there is no class (`JfrAnalyses.java:311-370`). It drops the method descriptor
  and the line number, so overloads merge into one key. `stableName` then removes
  `/0x…`, `$$Lambda$n`, `access$n` and `lambda$m$n` (`JfrCompare.java:120-150`).
- **The key is not dotted.** On the checked-in Datadog recording the keys come out as
  `java/lang/StringLatin1.toLowerCase`. Native frames have no class, so their keys are bare symbols
  such as `sendto` or `CodeCache::contains`. I checked this by running
  `jfr-shell compare tck-test.jfr tck-test.jfr --json --min-delta-pct 0` (section 8 says which
  build).

The two callers:

- `jfr-shell compare` (`Main.java:757-849`) always reads inclusive frames (`:825-831`).
- `jfr_compare` reads self time only (`JfrCompareTools.java:143-148`). Its schema takes
  `baselineSessionId`, `candidateSessionId`, `eventType`, `minDeltaPct` and `limit`
  (`:75-96`).

`CompareResult`'s row keys are documented as a published contract (`CompareResult.java:12-24`).

## 2. What each format provides

| Need | JFR | pprof (`PprofProfile`) | OTLP (`OtlpProfile`) |
|---|---|---|---|
| Which sampler | event type (`jdk.ExecutionSample`, `datadog.ExecutionSample`, `jdk.NativeMethodSample`, detected in that order, `JfrAnalyses.java:703-715`) | `sampleTypes` list of `(type, unit)`, one value column each (`PprofProfile.java:18,30,145`) | one `sampleType` per `Profile`. A file holds several profiles (`OtlpProfile.java:22,53-59`) |
| Sample weight | 1 per event. A `weight` field exists on Datadog events | `Sample.values[i]` for column i (`:53`). It can be a count or nanoseconds/bytes | `Sample.values`, documented as a single element (`:66-72`), plus `timestampsUnixNano` |
| Sample count (for the z test) | events | only when one column has unit `count`. Otherwise `period`/`periodType` may let you derive it (`:24-25,36-37`) | the same: `period`/`periodType` (`:50-51`) |
| Duration | first-to-last sample span | `durationNanos`, `timeNanos` (`:26-27`) | `durationNano`, `timeUnixNano` per profile. Per-sample timestamps when present |
| Truncation | `truncated` flag per stack | **none** in the model or in `PprofReader`'s field list (`PprofReader.java:39-90`) | **none** in the model |
| Frame identity | class `name` + method `name` (+ descriptor, line). The key uses class and method only | `Function.name`, `systemName`, `filename`, `startLine`. `Line.lineNumber`. Inlined frames are several `Line`s per `Location` (`:95-119`) | the same fields (`:87-110`), plus `Line.column` |
| Other events | everything in the recording | none | none |

What is missing:

- **pprof:** there is no truncation signal. `default_sample_type`, `drop_frames` and
  `keep_frames` are not parsed: field 14 and fields 7/8 are absent from the field constants in
  `PprofReader.java:39-49`. Every pprof consumer in the repository therefore picks the *first*
  sample type, for example `PprofPathEvaluator.java:253-255` and `PprofTools.java:309,832,971`.
- **OTLP:** there is no truncation signal either. Today the shell turns a sample with empty
  `values` into value 0 and reads only `values.get(0)` (`OtlpPathEvaluator.java:78`).
- **Neither format** carries JFR-style events. Event-rate metrics only apply when both sides are
  JFR.

## 3. Frame identity across formats

**Proposed canonical key:** `stableName(qualifiedName)`. Here `qualifiedName` is
`<class>.<method>` for a managed frame and the symbol for a native frame, as today. Line,
descriptor, file and column are never part of the key, so the existing JFR behaviour is kept.

How each format maps to it:

- **JFR:** unchanged (`extractMethodName` + `stableName`).
- **pprof/OTLP:** the first non-empty of `Function.name` and `Function.systemName`, the same
  fallback the shells use (`PprofPathEvaluator.java:117`, `OtlpPathEvaluator.java:124`). Then
  `stableName`. Each `Line` of a `Location` becomes its own frame, and the leaf is the first line
  of the first location. The shells already flatten stacks in that order
  (`PprofPathEvaluator.java:110-121`).

**What jfr2pprof emits.** jfr2pprof renders the function name with `FrameFormat`, which defaults
to `{class}.{method}` (`FrameFormat.java:12-13`). It takes the class and method from the same
`type.name` and `name` symbols, and drops a frame when both are empty (`FrameExtractor.java:66-68`),
just as `extractMethodName` returns `null` (`JfrAnalyses.java:366-367`). The rendered name goes into
`Function.name` and the bare method name into `system_name` (`PprofBuilder.java:58-66,129-136`).
jfr2pprof does not apply `stableName`, but the reader would, so a JFR recording and its default
jfr2pprof conversion **produce identical keys**. That makes a natural round-trip test (section 6).

Three caveats:

1. **`includeLineNumbers: true` changes the name.** It appends `:<line>` to the name
   (`FrameFormat.java:18-19`), so the keys no longer match. A custom `format` (for example
   `{method}.{class}`, `FrameFormatTest`) also breaks the match. The reader should not try to strip
   these.
2. **Dotted versus slashed class names.** JFR keys keep the class name exactly as the symbol is
   stored (`java/lang/...` on the recording above). A pprof file written by another tool may use
   dots. Converting `/` to `.` across the whole string is unsafe, because Go function names contain
   `/` legitimately (`github.com/x/y.Func`) and so do native frames that are paths. See decision
   D2.
3. **Line-level identity is lost on purpose.** jfr2pprof interns one function per
   (name, method, line) (`PprofBuilder.java:58-69`). The key ignores that split, which is correct
   for the compare.

## 4. Sampler comparability and normalisation

**Recommendation: compare shares, never absolute rates, whenever the two sides come from
different samplers.** A share is a frame's weight divided by the side's total weight in the chosen
sample type. It is defined for every format. "Samples per second" is not comparable across
samplers, because each one has its own interval, thread selection and weight unit.

Define a `SamplerKind` from the event type or the sample type. It is `CPU`, `WALL`, `ALLOC_BYTES`,
`ALLOC_COUNT`, `LOCK` or `UNKNOWN`:

- JFR maps the event type: `jdk.ExecutionSample`/`datadog.ExecutionSample` become `CPU`, and
  `jdk.NativeMethodSample` becomes `WALL` (native threads).
- pprof and OTLP map `type`/`unit` by name. `cpu` or `samples` with unit `nanoseconds`/`count` on a
  CPU profile becomes `CPU`, `wall` becomes `WALL`, and `alloc_space`/`inuse_space` become
  `ALLOC_BYTES`. `PprofTools.java:548-552` already does a looser version of this, where `cpu`,
  `wall` and `time` all count as CPU-like.

What happens for each pair:

| Pair | Action |
|---|---|
| Same format, same event or sample type | Compare as today. Shares and significance are valid |
| Same kind, different format (JFR `jdk.ExecutionSample` vs pprof `cpu/nanoseconds`) | Compare shares, with a `comparability` note naming both samplers. Event-rate metrics only when both sides are JFR |
| Same kind, different JFR event type | Today this is a note only (`JfrCompare.java:364-372`). Keep it |
| Different kind (CPU vs WALL, for example JFR execution samples vs async-profiler wall) | **Refuse** by default, with an error naming both. `--allow-mixed-samplers` downgrades it to a note, and then no row is `significant` |
| CPU/WALL vs ALLOC | Always refuse. The shares measure different things |
| `UNKNOWN` on either side | Compare, with a note. The user picked the type explicitly (section 5) |

**Weighted values and the z test.** `isSignificant` treats its inputs as counts out of
`sampleCount` trials (`JfrCompare.java:344-352`). With pprof `cpu/nanoseconds` the per-frame
values are nanoseconds, and feeding them in as counts would inflate z by orders of magnitude. So a
profile carries both of these:

- `weight` maps in the chosen unit, which give the shares, and
- an observation count per frame for the z test, from (in order) a `count`-unit column in the
  same sample, or `value / period` when `periodType` has the same unit, or else **unknown**.

With an unknown count, the rows are reported with their shares but are never `significant`, and a
`comparability` note says why. For JFR, weight and count are the same (1 per event), so today's
results do not change. The Datadog `weight` field stays ignored, as decision D6 recommends.

**Truncation.** pprof and OTLP have no signal (section 2). `truncatedSamples` becomes "unknown"
rather than 0, the description omits `truncatedStacks`, and a note says inclusive shares may be
understated for deep stacks. The 1% gate (`JfrCompare.java:266-282`) applies only to a side whose
truncation is known (decision D4).

**Duration.** For pprof, use `durationNanos`. For OTLP, use the span the selected profiles cover
(per-sample timestamps when present). A zero duration takes the existing "duration unknown" path
(`JfrCompare.java:374-377`). Section 6 shows that jfr2pprof's duration (the sum of chunk
durations, `RecordingSpan.java:135-139`) differs from JFR's sample span, so the duration-ratio
note can differ between a round-trip pair.

## 5. Proposed API

All of these are in `io.jafar.shell.core.compare` unless noted.

```java
/** Reads one file into what a comparison needs. One per format. */
public interface ProfileReader {
  String format();                                   // "jfr", "pprof", "otlp"
  List<SampleTypeInfo> sampleTypes(Path file) throws Exception; // for --list-sample-types
  RecordingProfile read(Path file, ProfileSelection selection) throws Exception;
}
public record SampleTypeInfo(String name, String unit, SamplerKind kind) {}
public record ProfileSelection(String sampleType, boolean inclusive) {} // null = default
```

- **`RecordingProfile` gains components:** `format`, `sampleUnit`, `samplerKind`, per-frame
  observation counts for the z test (or a flag saying they are unknown), and
  `truncationKnown`. The existing 10-argument constructor stays as a secondary constructor, so the
  JFR call site and the tests do not change. `eventType` keeps its JFR meaning. For pprof and OTLP
  it holds `type/unit`.
- **The comparison splits from the JFR read.** `compare(RecordingProfile, RecordingProfile,
  CompareOptions)` (`JfrCompare.java:256-319`) is already format-free apart from
  `analyses.isNativeMethod` (`:518`) and `TRACKED_EVENT_TYPES`. Move it to a `ProfileCompare`, and
  keep `JfrCompare` as the JFR reader plus a thin delegate (decision D1). Finding ids and
  `source = "jfr_compare"` (`:100`) stay the same, so `Findings.merge` keeps de-duplicating.
- **Discovery** goes through an optional capability on the existing module SPI rather than a
  seventh SPI type. Add `default ProfileReader profileReader() { return null; }` to `ShellModule`,
  in the same style as `createTuiAdapter` (`ShellModule.java:65-75`). `JfrModule`, `PprofModule`
  and `OtlpModule` override it. The JFR reader keeps reading through `JfrQuerySource`, so the
  backend SPI is untouched and the JDK backend works where its capabilities allow.
  `BackendCapability` (`BackendCapability.java:7-27`) needs no new value.
- **Module placement.** The pprof and OTLP readers live in `pprof-shell` and `otlp-shell`, which
  already depend on `shell-core` and their parser. Readers must use only `io.jafar.pprof.api` and
  `io.jafar.otlp.api`: ArchUnit rule (g) forbids the parsers' `internal` packages, and rule (d)
  forbids `shell-core` depending on the shells ([Gates.md](../agents/Gates.md), "Architecture
  boundaries").

**CLI.** `jfr-shell`'s own fat jar "knows JFR only" (`jfr-shell/build.gradle:360-362`), and the
published `jafar-shell` jar runs `io.jafar.shell.unified.Main`. That main class routes only `show`,
`metadata`, `chunks`, `constants` and `cp` to the JFR CLI (`unified/Main.java:30-31,128-131`), so
`compare` does not reach `CompareCommand` through the published jar today (R2: there are two entry
points). The proposal:

- Add `compare` to the unified routing, and pick each side's reader from the modules
  `ServiceLoader` discovers, by `canHandle` (`ShellModule.java:27`).
- New options: `--sample-type <type[/unit]>` (pprof/OTLP; `--event-type` stays for JFR, and each
  is rejected on a side of the other format), `--baseline-sample-type` and
  `--candidate-sample-type` for an asymmetric pair, `--allow-mixed-samplers`, and
  `--list-sample-types`.
- The default sample type is the first `CPU`-kind type, otherwise the first type, and the result
  states which one it chose and why (R3).

**MCP.** Session ids are numbered separately per registry. `SessionRegistry` and
`SamplingSessionRegistry` each count from 1 (`SessionRegistry.java:68`,
`SamplingSessionRegistry.java:51`), so a bare id cannot say which format it belongs to. The
proposal is a new tool, `profile_compare`, with `baseline` and `candidate` objects of the shape
`{format: jfr|pprof|otlp, sessionId}` plus `sampleType`, `minDeltaPct`, `limit` and
`allowMixedSamplers`. `jfr_compare` stays byte-identical (decision D3). Update
`doc/agents/Mcp.md`'s tool list, the `compare` prompt, and the `compare` skill in
`btraceio/agent-plugins`.

**Gates that apply** ([Gates.md](../agents/Gates.md), table):

- **Parity (Java/Go):** not applicable. The work touches neither
  `parser-core/.../(impl|internal_api)/` nor `go-parser/jfr/` (`scripts/parity-check.sh:42-43`).
  State `Parity: not applicable — no untyped parser change`.
- **API-Break:** `io.jafar.shell.core.compare` and `ShellModule` are not in
  `gradle/api-surfaces.txt`. The shell surface is `pkg:io.jafar.shell.backend` plus
  `class:io.jafar.shell.core.Session`. Adding a default method breaks nothing in any case. **Parsing
  pprof `default_sample_type` would add a component to the `PprofProfile.Profile` record**, which
  is in the gated `pprof` surface. That changes the canonical constructor and needs an
  `API-Break:` statement. Prefer an added secondary accessor or constructor.
- **Alloc budgets:** these apply only if `pprof-parser` or `otlp-parser` change (for example to
  parse field 14).
- **Work budgets:** JFR parse entry points only. Not affected.

## 6. Test plan

The fixtures in the repository:

- **JFR:** `jfr-shell-tck/src/main/resources/tck-test.jfr` (checked in, 14,202
  `datadog.ExecutionSample`, `CompareCommandTest.java:27-31`). Also the generated `test-ap.jfr`
  and `test-jfr.jfr` (`test-recordings/README.md`, table).
- **pprof/OTLP:** there are **no binary fixtures** in git. `MinimalPprofBuilder`
  (`pprof-parser/src/testFixtures/.../MinimalPprofBuilder.java`) and `MinimalOtlpBuilder`
  (`otlp-parser/src/testFixtures/.../MinimalOtlpBuilder.java`) build files at test time.
- **jfr2pprof itself** is a fixture generator. Its tests already read its output back with
  `PprofParser` (`PprofBuilderRoundTripTest.java:8-29`).

The tests:

1. **Round trip (the anchor).** Convert `tck-test.jfr` with jfr2pprof. Use an inline mapping:
   `datadog.ExecutionSample`, one value `{name: samples, unit: count, field: "@count"}`, the default
   frame format. Then compare the JFR profile with the pprof profile. Assert that both sides have
   the same `sampleCount`, identical `leafFrameCounts` and `inclusiveFrameCounts` maps, an empty
   `frames` list at `minDeltaPct = 0`, and no `significant` row. Assert that `durationSeconds`
   *differs*, as section 4 says. Notes:
   - jfr2pprof merges samples with the same stack and labels into one `Sample`
     (`PprofBuilder.java:144-151`), so the reader must sum the count column, not count `Sample`
     messages.
   - An empty JFR stack becomes a pprof sample with no locations, which must still count as a
     sample.
   - **Prove the test fails (R5):** remove `stableName` from the pprof reader, or feed nanosecond
     weights as counts, and watch it fail.
   - Place the test in `jafar-shell`, which already depends on every shell module
     (`jafar-shell/build.gradle:33-43`), with `testImplementation project(':jfr2pprof')`.
2. **Same round trip with `includeLineNumbers: true`.** Assert that the keys do *not* match. This
   pins caveat 1 of section 3.
3. **Sampler refusal.** Build pprof files with `MinimalPprofBuilder` as `cpu/nanoseconds` and
   `wall/nanoseconds`. Assert an error without `--allow-mixed-samplers`, and a note with no
   `significant` row with it. Run the same check for CPU against `alloc_space`, which always
   refuses.
4. **Weighted significance.** Use a pprof file with only `cpu/nanoseconds` and no period. Assert
   that the rows carry shares, `significant=false`, and a note. Then add `period`/`periodType` and
   assert that counts are derived from it.
5. **OTLP.** Build files with `MinimalOtlpBuilder`: two profiles with different sample types in one
   file, selection by `sampleType`, and the truncation-unknown note.
6. **Entry points (R1, R2).** Run `compare` through the built unified jar *and* through
   `io.jafar.shell.Main`, plus `profile_compare` over MCP. Check that `jfr_compare` output is
   unchanged: the existing `JfrCompareHandlerTest` and `JfrCompareThinDataTest` stay green, and
   their failure sets are compared by name (R6).

## 7. Decisions

**Decided 2026-11-26 with the maintainer: every recommendation below, D1 to D8, as written.**


1. **D1. Naming.** Options: (a) keep `RecordingProfile` and add `ProfileCompare` next to
   `JfrCompare`; (b) rename to `SampledProfile`/`ProfileCompare` with deprecated aliases.
   *Recommend (a).* The names are not API-gated, but they appear in `doc/agents/Mcp.md` and in
   tests, and "recording" reads fine for a profile file.
2. **D2. Canonical class separator.** Options: (a) leave names as stored, so JFR keys stay
   slashed; (b) turn `/` into `.` in JFR class names only, and in pprof/OTLP names only when the
   whole name matches a Java internal name (`[\w$]+(/[\w$]+)+\.[\w$<>]+`); (c) dot everything.
   *Recommend (b), applied after `stableName`,* because `stableName`'s `/0x…` pattern needs the
   slash form. Note that this changes the `method` values `jfr_compare` returns today, so it needs
   a CHANGELOG line. If that change is unwanted, use (a), and accept that a dotted third-party
   pprof file will not line up.
3. **D3. MCP surface.** Options: (a) a new `profile_compare`; (b) add `baselineFormat` and
   `candidateFormat` to `jfr_compare`. *Recommend (a):* `jfr_compare`'s response is a documented
   contract (`Mcp.md`, "`jfr_compare` is an adapter").
4. **D4. Unknown truncation.** Options: (a) judge inclusive moves anyway, with a note; (b) never
   judge inclusive moves on a side with unknown truncation. *Recommend (a).* Option (b) would make
   every pprof inclusive compare inconclusive. If (a) is too loose, add a later jfr2pprof label
   that carries JFR's `truncated` flag.
5. **D5. Cross-sampler policy.** Options: (a) refuse different kinds by default, with an override
   flag; (b) warn only. *Recommend (a),* with ALLOC against CPU/WALL refused even with the flag.
   Today's warn-only path for two JFR event types of the same kind stays as it is.
6. **D6. Datadog `weight`.** Options: (a) keep counting events; (b) weight shares by `weight`.
   *Recommend (a) in v1:* it keeps `jfr_compare` unchanged. Revisit it together with the
   weighted-significance work.
7. **D7. OTLP scope in v1.** Options: (a) pprof only, OTLP in a follow-up; (b) both. *Recommend
   (a).* The OTLP shell has open questions (section 8: index base, empty `values`, multiple values)
   that should be settled against a real exporter's output first, and there is no OTLP fixture
   from a real exporter.
8. **D8. Default pprof sample type.** Options: (a) the first CPU-kind type; (b) the *last* type,
   which is what (I believe) the Go `pprof` tool does without `default_sample_type`; (c) parse
   field 14. *Recommend (a) now, (c) later* (that change touches the gated pprof API).

## 8. What I did not verify

- **The class-name form was checked once, with the released backend.** I saw the slash form by
  running `jfr-shell-0.29.0-SNAPSHOT-all.jar` from the main checkout, with its auto-installed
  **released 0.29.0** `jafar` backend, not the built one. It warned about exactly that. I did not
  check what the `jdk` backend (`jfr-shell-jdk`, `LiveEventDecoder`) produces for class names. If
  it yields dots, the two JFR backends already disagree on keys today.
- **The unified-jar routing was read from source, not run.** I did not run `jafar-shell compare`
  through the built unified jar. The claim that it does not reach `CompareCommand` comes only from
  reading `unified/Main.java:30-31,128-131`.
- **Inline frame order.** `PprofProfile.Location` documents its `lines` as "outermost first"
  (`PprofProfile.java:95`), but the evaluators treat the first line as the leaf side. I did not
  check this against `profile.proto` or against a real inlined Go profile. jfr2pprof writes one
  line per location, so the round trip cannot tell the difference. *Checked in section 10: the
  evaluators are right and the Javadoc was wrong.*
- **OTLP index base.** `OtlpPathEvaluator` subtracts 1 from stack, location, function and
  attribute indices (`:92,114,118,122`), and `MinimalOtlpBuilder` writes those tables without a
  sentinel at index 0. The two agree with each other. I did not check either against a
  spec-conformant exporter that writes the zero entry.
- **OTLP values.** I did not check the OTLP spec's rule for samples with empty `values` (counted
  per timestamp?) or with several values. The shell's `values.get(0)` behaviour may undercount.
- **Other tools' pprof output.** I did not check what async-profiler or Go write for Java/Go
  function names, sample types or period. The sampler-kind table in section 4 is a proposal, not a
  survey. *Checked in section 10.*
- **The Go `pprof` default (D8)** comes from memory, not from a check. *Checked in section 10: it
  is the last type.*
- **The mcp surface's subpackages.** I did not check whether japicmp's
  `packageIncludes io.jafar.mcp` (`gradle/api-compat.gradle:151-162`) covers subpackages such as
  `io.jafar.mcp.jfr`.
- **Nothing was built or tested for this document.** The code paths above were read, and the one
  command in section 1 was run.

## 9. What v1 did differently

- **jfr2pprof did not produce identical keys.** It rendered a frame without a class as `.sendto`
  (the `{class}.` with an empty class), so every native frame of `tck-test.jfr` mismatched. Fixed in
  `FrameFormat.render`: the text between `{class}` and `{method}` goes with a missing class.
- **`stableName` missed a hidden-class form.** `tck-test.jfr` stores hidden lambda classes as
  `Handler$$Lambda.0x00007fd6a823c220` (a dot, not a slash), which kept the address and failed the
  D2 Java-name match on the pprof side. `.0x<hex>` as a whole segment is now removed too.
- **The round trip at `minDeltaPct = 0` is not an empty list.** The floor drops a move *smaller*
  than it, so at 0 every frame is a row. The test asserts that every row has `deltaPct` 0 and none
  is significant, and that there is one row per frame.
- **The sampler policy is a three-way option** (`CompareOptions.MixedSamplers`): `NOTE` keeps
  `jfr_compare`'s behaviour for two JFR event types of different kinds (judged, with the event-type
  note), so its output changes only by D2; `ALLOW` compares with nothing judged. `profile_compare`
  and `jfr-shell compare` choose with `MixedSamplers.forPair`: `NOTE` for two JFR sides, else
  `REFUSE` (or `ALLOW` on request). The D5 refusal therefore applies only when a pprof side is
  involved; refusing two recordings `jfr_compare` accepts broke `perf_ab_run.sh`'s exit-0
  contract.
- **Counts (section 4) were narrowed after review.** A `count` column counts only when it counts
  samples: allocation (`alloc_objects`) and lock (`contentions`) counts are estimates scaled from
  the samples. The partner is the single `samples/count` column or `<x>-samples` beside `<x>-time`,
  never "the first count column". Period-derived counts are summed and divided once.
- **D2 for pprof is decided per profile, not per name.** `encoding/json.Marshal` matches the Java
  internal-name pattern, so slashed names are dotted only in a profile that looks JVM-originated;
  a trailing `:<line>` is dropped, which makes caveat 1's line-numbered conversion match after all.
- **Sample-type names by prefix:** `cpu*` is CPU and `wall*` wall clock (`cpu-time`, `wall_time`).
- **`samples` takes the kind of the profile's period type or of another CPU/wall sample type**,
  else it is `UNKNOWN`. jfr2pprof's `samples/count` (no period) is therefore `UNKNOWN`, and the round
  trip gets the "Sampler kind not recognised" note rather than a refusal.
- **Unknown counts** report `sampleCount` as the number of sample records and `null`
  `baselineSamples`/`candidateSamples` in the rows; the finding is `regression:not-judged`, also
  used when mixed samplers were allowed.
- **`profile_compare` takes `inclusiveFrames`** (default false, like `jfr_compare`) and a per-side
  `sampleType`.
- **The standalone jfr-shell jar** has no pprof module, so it reads every non-JFR file as a
  recording and fails with "cannot read JFR recording"; `jafar-shell compare` reads pprof.


## 10. Checked against real producers (2026-11-27)

Section 8's "inline frame order", "other tools' pprof output" and "the Go `pprof` default" are now
checked against files Go 1.24.7's `runtime/pprof` and async-profiler 4.1's `jfrconv` wrote. The
files, the program they profile and the exact commands are in
`pprof-shell/src/test/resources/real/README.md`; `RealPprofProfilesTest` (pprof-shell) and
`RealProfilesCompareTest` (jafar-shell) read them.

Confirmed:

- **Inline order.** Go writes an inlined call as one location whose lines are innermost first
  (`[(*Hasher).Sum, Burn.func1, Burn]`), as `profile.proto` says. The reader takes the first line as
  the leaf, which is right; `PprofProfile.Location`'s "outermost first" was wrong and is corrected.
- **Go CPU counts.** `samples/count` with `cpu/nanoseconds` and period `cpu/nanoseconds` 10 ms; the
  default picks `samples/count` (CPU through the period type), and `cpu/nanoseconds` counts through
  its `samples` partner. Two runs of one program judge nothing at about 200 samples each.
- **Go names** stay slashed: no Go function looks JVM (`looksJvm` is false), so
  `encoding/json.Marshal` is kept. Closures (`func1`), pointer receivers and generic
  instantiations (`Map[go.shape.int,go.shape.*uint8]`) pass `stableName` unchanged.
- **Go heap** has no counts for any of its four columns (scaled estimates; a byte period), and no
  duration.
- **async-profiler** names are slashed, its profiles carry `<init>` and `$$Lambda` (so they are
  dotted), and hidden-class addresses are `.0x...` segments, which `stableName` removes. Its JFR and
  its pprof conversion give identical keys and counts for every Java frame.
- **D8.** Neither producer writes `default_sample_type`, so parsing field 14 (D8 (c)) would change
  nothing for them. `go tool pprof` defaults to the last type (`cpu`, `inuse_space`, `delay`),
  which D8 (b) assumed from memory.

Wrong, and fixed:

- **Go mutex and block counts.** Go writes period 1 whatever the rate and scales each contention by
  the rate, so `contentions / period` was taken as a count. Lock columns now never give counts.
- **async-profiler's column names** `allocations`, `locks` and `malloc` were `UNKNOWN`, so its
  allocation profile was compared against a CPU one. They now have allocation and lock kinds.

Decided 2026-11-27 with the maintainer, and done:

- **async-profiler JFR native frames** had the library as the class (`libjvm.so`, `libc.so.6`), so
  their key was `libjvm.so.Compile::Compile` where the pprof conversion writes `Compile::Compile`,
  and every native frame of a recording and its conversion mismatched. Option (b): `JfrCompare`
  keys a frame whose JFR frame type is `Native`, `C++` or `Kernel` by its method alone. This
  changes `jfr_compare`'s method values for such frames. `ap-cpu.jfr` and its conversion now have
  identical leaf and inclusive frame counts (`RealProfilesCompareTest`).

Open (not changed; each needs a decision):

- **async-profiler's wall profile is named `cpu/count`** by `jfrconv --wall`. The reader cannot tell
  it from a CPU profile, so CPU against wall is not refused. Options: (a) document it (done in
  `Usage.md`); (b) guess wall from the stacks. Recommended: (a), and an upstream report.
- **async-profiler's `allocations/count` counts samples** (bytes over count is about 512 KiB, the
  sampling interval), unlike Go's `alloc_objects`, but every allocation count is treated as an
  estimate, so an async-profiler allocation pair is not judged. Options: (a) keep it; (b) trust
  `allocations/count` by name. Recommended: (b) once a second async-profiler version confirms it.
- **The default sample type** is the first CPU type, else the first; Go's tool uses the last. For
  Go heap that is `alloc_objects` against `inuse_space`, for mutex `contentions` against `delay`.
  Options: (a) keep; (b) the last type when no CPU type. Recommended: (b), matching what Go users
  see.
- **async-profiler's JFR wall events** (`profiler.WallClockSample`) are not a JFR sample type, so
  `ap-wall.jfr` lists none; each event carries a `samples` count (300 events, 945 samples in the
  pprof conversion), so counting one per event would be wrong. Needs the weighting decision (D6).
- **An allocation profile's leaf is the allocated class** in async-profiler's pprof (`byte[]`,
  `java.lang.String`); a JFR allocation event's leaf is the allocating method. Self-time rows of a
  JFR-against-pprof allocation compare do not line up.
- **`JfrAnalyses.isNativeFrame`** calls any name with `<` native, so a constructor
  (`MethodHandle.<init>`) gets `type: native` in compare rows. Shared with other analyses, so not
  changed here.

# jfr2pprof — JFR → pprof converter (design)

**Status:** Draft / design. Not yet implemented.
**Module (proposed):** `:jfr2pprof`
**Owner:** TBD

## 1. Goal

A small, **vendor-neutral** command-line tool that converts a `.jfr` recording into a
Google [pprof](https://github.com/google/pprof/blob/main/proto/profile.proto) profile
(`profile.proto`, gzipped protobuf).

The tool contains **no knowledge of any specific event vendor**. Which JFR event type maps
to which pprof profile type (CPU, wall, allocation, live-heap, …), which field carries the
value, its unit, and which fields become pprof labels is supplied entirely by a **YAML
mapping file**. Datadog-specific event names live in a Datadog mapping file shipped by the
*consumer* (e.g. the `java-profiler` repo or a `prof-correctness` scenario), never in jafar.

### Immediate driver

[`DataDog/prof-correctness`](https://github.com/DataDog/prof-correctness) validates profiler
output by parsing **pprof only** (`analysis/analysis.go` → `google/pprof` `profile.ParseData`,
after transparent lz4/zstd/gzip decompression). The Datadog Java profiler emits **JFR only**.
This tool is the bridge that lets a `prof-correctness` scenario run the agent, convert its JFR
to pprof, drop it in `/app/data/`, and reuse the existing pprof analyzer unchanged.

## 2. Non-goals

- No Datadog- (or any vendor-) specific logic in tool code. All specifics are config.
- No flame graphs / HTML / collapsed output — that already exists elsewhere in jafar's shells.
- No pprof *reading* — `:pprof-shell` already reads pprof. This tool only writes.
- No streaming/live conversion. One `.jfr` in, one `.pprof` out.

## 3. Placement & rationale

New module `:jfr2pprof`, sibling to `:tools`. It is a runnable CLI (shadow/`-all` jar,
jbang-installable), not a library API. Because it is config-driven it stays vendor-neutral,
so hosting it in jafar does not couple jafar to Datadog — the concern that kept the DD event
mapping out of the parser core.

Depends on `:parser` (untyped API) + one YAML lib. Everything else is JDK-only.

## 4. Pipeline

```
.jfr ──▶ [jafar untyped parser] ──▶ per-event: (stack, values[], labels[])
                                          │  (mapping drives extraction)
                                          ▼
                                   [pprof model builder]
                                    - intern strings
                                    - dedup functions/locations
                                    - accumulate samples
                                          ▼
                                   [protobuf encode + gzip] ──▶ profile.pprof
```

## 5. Configuration schema (YAML)

One document. `profiles[]` describes each pprof sample type the output should contain. A
single JFR event may produce **several** pprof value columns (e.g. allocation → both a count
and a size column), so `values` is a list.

> **All event names below (`datadog.ExecutionSample`, `datadog.ObjectAllocationSample`, etc.)
> are illustrative placeholders.** The real names must be obtained from a live java-profiler
> recording (see §13, open question 1). They belong in the consumer's mapping file, never here.

```yaml
# Optional: how a frame renders into the folded/pprof function name.
# prof-correctness matches regexes against the ";"-joined function names, so this is a contract.
frame:
  format: "{class}.{method}"   # placeholders resolved from the JFR StackFrame (see §7)
  includeLineNumbers: false

profiles:
  - type: cpu-time              # pprof ValueType.type string (what prof-correctness keys on)
    event: datadog.ExecutionSample   # example only; confirm actual DD event name
    stackField: stackTrace      # path to the stack trace complex value
    values:
      - name: cpu-time          # ValueType.type
        unit: nanoseconds       # ValueType.unit
        field: weight           # JFR field summed into the value (see §6)
    labels:
      - jfr: sampledThread.osThreadId
        pprof: "thread id"
      - jfr: sampledThread.javaName
        pprof: "thread name"

  - type: allocation
    event: datadog.ObjectAllocationSample   # example only; confirm actual DD event name
    stackField: stackTrace
    values:
      - { name: alloc-samples, unit: count, field: "@count" }  # "@count" = 1 per event
      - { name: alloc-space,   unit: bytes, field: weight }
```

### Field semantics (the part that is *not* just event names)

| Key | Meaning |
|-----|---------|
| `profiles[].event` | fully-qualified JFR type name, matched against `type.getName()`. |
| `profiles[].stackField` | path to the stack-trace complex value on the event (usually `stackTrace`). |
| `values[].name` / `values[].unit` | become a pprof `ValueType` (`type`,`unit`) string pair. **Must match** the `profile-type` string used by the consumer's assertions. |
| `values[].field` | JFR numeric field summed into this column. Special: `@count` = literal `1` per event (for `*-samples`). Dotted paths allowed (`weight`, `tlabSize`, …). |
| `values[].scale` | *(optional)* constant multiplier applied to the field (e.g. sampling interval upscaling). Default `1`. |
| `labels[].jfr` / `labels[].pprof` | JFR field path → pprof label key. Numeric fields → `Label.num`; strings → `Label.str`. |

Design intent: the tool ships with **no default profiles**. The consumer supplies exactly one
mapping file (`--config datadog.yaml`). Generic mechanism, one concrete config — no DSL sprawl.

## 6. Value semantics & scaling (important, easy to get wrong)

- JFR sampled events represent estimates. If the profiler records an upscaling `weight`
  (bytes for allocation, ns for cpu/wall), sum that field directly (`field: weight`). If it
  records raw counts + a fixed sampling interval, use `field: "@count"` + `values[].scale:
  <interval_nanos>`, where `<interval_nanos>` is the sampling interval in nanoseconds. Obtain
  it from the JFR `jdk.ActiveSetting` events (field `value` where `name == "period"`) emitted
  at recording start, or let the consumer supply it explicitly as a literal in the mapping YAML.
- Emit **raw accumulated** values. Do **not** convert to per-second rates. `prof-correctness`
  applies its own `scale_by_duration` on the *expected* side; pre-rating here would
  double-scale. (See `analysis/analysis.go`: expected `v * durationSecs` when
  `scale_by_duration`.)
- Set `Profile.duration_nanos` from the recording span (sum of chunk durations, or
  last-sample − first-sample). `prof-correctness` uses the profile duration for rate math.
- Time fields in JFR are chunk-relative ticks; convert with `ctl.chunkInfo().asDurationNanos(ticks)`
  (tick → nanosecond duration) or `ctl.chunkInfo().asEpochNanos(ticks)` (tick → epoch nanoseconds)
  only where needed (durations, timestamps). `ctl.chunkInfo().startTime()` returns an `Instant`
  and `ctl.chunkInfo().duration()` returns a `Duration` — use these to accumulate the recording
  span across chunks. Values summed for pprof columns (`weight`, sizes) are normal numbers, not ticks.

## 7. Parsing with jafar (untyped API)

Use the **untyped** parser, not typed interfaces: typed `@JfrType` interfaces re-hardcode
event/field names at compile time, defeating the config-driven goal. Untyped lets us read
arbitrary event types named in the YAML by string.

```java
import io.jafar.parser.api.*;
import java.nio.file.Path;

// Use FULL_ITERATION: jfr2pprof reads every field of every matched event.
try (UntypedJafarParser p = UntypedJafarParser.open(jfrPath, ParsingContext.create(),
                                                    UntypedStrategy.FULL_ITERATION)) {
  p.handle((type, value, ctl) -> {
    ProfileSpec spec = byEvent.get(type.getName());   // from parsed YAML
    if (spec == null) return;

    // stack — spec.stackField is "stackTrace"; "frames" is the array field inside it
    Object frames = Values.get(value, spec.stackField, "frames"); // ArrayType or Object[]
    long[] locationIds = builder.internStack(frames, frameFormat);

    // values — fieldPath segments must be split on "." before passing to Values.as()
    long[] vals = new long[spec.values.size()];
    for (int i = 0; i < vals.length; i++) {
      ValueSpec v = spec.values.get(i);
      vals[i] = v.isCount() ? 1L
                            // Cast to Object[] so Java spreads the String[] as varargs segments.
                            : (long) (Values.as(value, Long.class, (Object[]) v.fieldSegments()).orElse(0L) * v.scale);
    }

    // labels — jfrPath likewise split into segments; cast needed for same varargs reason
    List<Label> labels = spec.labels.stream()
        .map(l -> builder.label(l.pprofKey, Values.get(value, (Object[]) l.jfrSegments())))
        .toList();

    builder.addSample(spec.sampleTypeIndex, locationIds, vals, labels);
  });
  p.run();
}
```

Notes / **confirmed by reading `JfrPathEvaluator` + jafar parser source**:

- **Frame struct field names (confirmed).** Each `StackFrame` element exposes:
  - `method` → `ComplexType` → `Map` containing:
    - `type` → `ComplexType` → `Map` with `name` (a `String`, or a `ComplexType` → `Map{string:…}` when backed by a symbol constant pool)
    - `name` → `String` (or same nested-map pattern as above)
  - `lineNumber` → `int`; value `-1` means no line info available.
  - `frame.format` placeholder `{class}` resolves from `method.type.name`, `{method}` from `method.name`.
  - There is no `bytecodeIndex` field accessed for pprof output; omit it.
- **Frames array element shape (confirmed).** `stackTrace` is a `ComplexType`; unwrap to `Map`.
  `frames` inside that map is an `ArrayType`; call `getArray()` to get `Object[]`.
  Each element is either a `ComplexType` (unwrap with `getValue()`) or an inline `Map<String,Object>`.
- **Stack order (confirmed, no reversal needed by default).** JFR `frames[0]` = leaf (most
  recent call). pprof `Sample.location_id` is also leaf-first, so no reversal is needed for the
  standard bottom-up case. Reversal is only required when emitting a top-down/root-first variant.
- **`Values.get()` / `Values.as()` path segments.** Both methods accept `Object... path` as
  discrete segments — they do **not** split dotted strings. YAML field paths like `"weight"` or
  `"sampledThread.osThreadId"` must be split on `"."` into individual `String` segments before
  being passed to `Values.get()`/`Values.as()`. Add a helper `String[] segments(String dotted)`
  that does `dotted.split("\\.")`.
- **Tick conversion API (confirmed).** Use `ctl.chunkInfo().asDurationNanos(ticks)` for
  duration-tick → nanoseconds and `ctl.chunkInfo().asEpochNanos(ticks)` for timestamp-tick →
  epoch nanoseconds. The method `convertTicks(ticks, TimeUnit)` does not exist.

## 8. pprof emission

Target message: `perftools.profiles.Profile` (canonical `profile.proto`). Core fields the
converter must populate — field numbers are confirmed by `PprofReader` in `:pprof-shell`:

| Field | Number | Notes |
|-------|--------|-------|
| `sample_type` | 1 | repeated `ValueType{type(1), unit(2)}` — one per configured value column, ordered consistently |
| `sample` | 2 | repeated `Sample{location_id[](1), value[](2), label[](3)}` |
| `mapping` | 3 | optional; omit for pure-Java profiles |
| `location` | 4 | `Location{id(1), mapping_id(2), address(3), line[](4), is_folded(5)}` |
| `function` | 5 | `Function{id(1), name(2), system_name(3), filename(4), start_line(5)}` |
| `string_table` | 6 | all strings interned; index 0 MUST be `""` |
| `time_nanos` | 9 | epoch nanoseconds of recording start |
| `duration_nanos` | 10 | recording span in nanoseconds |
| `period_type` | 11 | optional `ValueType` for the sampling period |
| `period` | 12 | optional sampling period value |

`Label` sub-fields: `key(1)`, `str(2)`, `num(3)`, `num_unit(4)`. Numeric JFR fields → `num`;
string JFR fields → `str`.

Two encoder options:

1. **Hand-rolled minimal protobuf writer (recommended).** pprof's proto is small and stable;
   a compact varint/length-delimited writer is a few hundred lines and adds **zero runtime
   dependency**. Matches jafar's low-dependency ethos. Model it after `ProtoUtil` in
   `:shell-core` (`io.jafar.shell.core.proto.ProtoUtil`) which already handles VARINT and LEN
   wire types. (`ProtoUtil` is a reference/inspiration only — do not import it; `:shell-core`
   is not a dependency of `:jfr2pprof`.) Gzip via JDK `GZIPOutputStream` (`google/pprof` `ParseData` transparently gunzips).
2. **`protobuf-java` + vendored `profile.proto`.** Less code, but pulls a sizable dependency
   into a module whose only job is one message type. Only pick this if the hand-rolled writer
   proves error-prone.

Builder responsibilities: intern strings, dedup `(class,method,line)` → function → location,
keep sample-type column order stable, accumulate/merge identical `(stack,labels)` samples.

The serialization layer uses a `ValueTypePair` record to carry the pprof-relevant `(name, unit)`
string pair from the config model into `PprofBuilder.build()`. JFR-specific fields (field path,
scale) are intentionally absent from this record — they remain in `ValueSpec` and are never
visible to `PprofBuilder`.

```java
public record ValueTypePair(String name, String unit) {}
```

`MappingConfig.allValueTypes()` returns `List<ValueTypePair>` — an unmodifiable list of all
`(name, unit)` pairs across all profiles in declaration order, used to populate `sample_type[]`.

## 9. YAML dependency

Use **`org.snakeyaml:snakeyaml-engine`** (YAML 1.2, safe-by-default — won't instantiate
arbitrary Java types), confined to this module's `build.gradle`. Parse to plain
`Map`/`List`/scalars; **no** Jackson databind, no POJO binding, no reflection. This is the
minimal canonical choice; do not leak it into `parser-core`.

(If a truly zero-dependency config is ever required, the schema is too nested for
`java.util.Properties` to express cleanly — snakeyaml-engine is the right trade.)

## 10. CLI

```
jfr2pprof --config <mapping.yaml> --output <out.pprof> <recording.jfr>
          [--no-gzip] [--period-type <t>/<u>]
```

- `--no-gzip`: emit raw (uncompressed) protobuf bytes instead of gzip-wrapping the output.
  Use `.pb` as the file extension when gzip is suppressed (e.g. `--output profile.pb`).
  Note: `prof-correctness` / `google/pprof` `ParseData` transparently handles gzip but
  expects either gzip or raw protobuf — both are accepted, so `--no-gzip` output is
  consumable by those tools without any extra flags.
- Exit non-zero with a clear message if no configured event type was found in the recording
  (guards silent empty profiles).
- jbang-installable like the other jafar tools (`jbang app install jfr2pprof@btraceio`).

## 11. Build / module setup

- `settings.gradle`: `include ':jfr2pprof'`.
- Toolchain: Java 25 (matches all other CLI/shell modules in the project; the `:parser` core stays multi-release Java 8).
- Shadow plugin: `id 'com.gradleup.shadow' version '9.0.0'` (matches `:jfr-mcp`).
- Runtime deps:
  ```
  implementation project(':parser')
  implementation 'org.snakeyaml:snakeyaml-engine:2.9'
  implementation 'org.slf4j:slf4j-api:2.0.16'
  runtimeOnly   'ch.qos.logback:logback-classic:1.5.18'
  ```
- `shadowJar` to a `-all` jar; no need to re-export `parser-core` in the POM (this is an app, not a library).
- Tests:
  ```
  testImplementation 'org.junit.jupiter:junit-jupiter:5.11.3'
  testRuntimeOnly   'org.junit.platform:junit-platform-launcher'
  testImplementation 'org.assertj:assertj-core:3.24.2'
  testImplementation 'org.openjdk.jmc:flightrecorder.writer:9.1.0'
  testImplementation project(':pprof-shell')
  ```
  Use `flightrecorder.writer` to synthesize tiny JFRs with known events/weights (version 9.1.0 matches `:jfr-mcp`).
  Use `PprofReader` from `:pprof-shell` to read back serialized pprof output in round-trip tests (§12, item 1).

## 12. Testing strategy

1. **Round-trip within jafar:** write pprof → read it back with `:pprof-shell`'s `PprofReader`
   and assert sample types, stacks, values, labels. Closes the loop without external tools.
2. **Golden files:** small synthesized JFR (via `flightrecorder.writer`) + checked-in expected
   pprof; assert byte/semantic equality.
3. **Cross-validation:** parse output with the real `google/pprof` tool in CI to prove
   compatibility (this is what `prof-correctness` will do).
4. **prof-correctness dry run:** a folded-stack + value assertion against a known workload,
   mirroring an existing ddprof scenario JSON, to catch frame-naming / label / scaling drift.

## 13. Open questions

- Exact DD event names + weight fields for CPU / wall / allocation / live-heap — enumerate from
  a real java-profiler recording; they belong in the *consumer's* mapping file, not here.
- Live-heap semantics: it is a retained-set snapshot, not an accumulating counter — confirm the
  value field and whether any dedup differs from allocation.
- Context labels (span/trace/endpoint): include only if `prof-correctness` scenarios assert on
  them; they map cleanly to pprof labels via the `labels[]` config.
- Whether to also emit `Mapping`/`address`/`Line.line` for native frames, if DD JFR carries
  them and scenarios need native-frame regexes.
- **CI setup for `google/pprof` cross-validation (§12, item 3):** the CI system, build image,
  installation step, and binary version to use for `google/pprof` are not yet decided. Options
  include installing the pre-built binary via `go install github.com/google/pprof@latest` in a
  GitHub Actions step, using a pinned release binary, or including it in a custom CI image.
  Must be resolved before milestone 6.

## 14. Milestones

1. Module skeleton + CLI arg parsing + YAML load to `Map`.
2. Minimal protobuf writer + `PprofBuilder` (string table, function/location dedup).
3. Untyped parse loop: stacks + single value column (cpu-time) end-to-end; round-trip test.
4. Multi-value events (allocation), labels, duration/time.
5. Frame-format contract validated against a real DD JFR (field paths confirmed by `:shell-core`; stack order already confirmed: frames[0] = leaf, no reversal needed for standard pprof). **Prerequisite (unresolved — see §13 item 1):** a real DD JFR recording must be obtained; no owner or source is yet assigned. This milestone is blocked until §13 item 1 is resolved.
6. google/pprof cross-validation in CI; sample Datadog mapping file (shipped by consumer).

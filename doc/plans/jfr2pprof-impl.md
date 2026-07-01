# jfr2pprof — Implementation Plan

**Design doc:** `doc/design/jfr2pprof.md`
**Module:** `:jfr2pprof`
**Root package:** `io.jafar.jfr2pprof`
**Java toolchain:** 25 (matches all other CLI/shell modules)

---

## 1. File layout

```
jfr2pprof/
├── build.gradle
└── src/
    ├── main/java/io/jafar/jfr2pprof/
    │   ├── Main.java                      # CLI entry point
    │   ├── config/
    │   │   ├── MappingConfig.java         # top-level YAML model
    │   │   ├── ProfileSpec.java           # one profiles[] entry
    │   │   ├── ValueSpec.java             # one values[] entry
    │   │   ├── LabelSpec.java             # one labels[] entry
    │   │   ├── FrameFormat.java           # frame: config
    │   │   └── MappingLoader.java         # snakeyaml-engine loader
    │   ├── proto/
    │   │   ├── PprofWriter.java           # hand-rolled protobuf writer
    │   │   └── PprofBuilder.java          # model accumulator → serializer
    │   └── convert/
    │       ├── FrameExtractor.java        # JFR frame Map → "Class.method[:line]"
    │       └── Jfr2PprofConverter.java    # main JFR parse loop
    └── test/java/io/jafar/jfr2pprof/
        ├── MappingLoaderTest.java
        ├── PprofBuilderRoundTripTest.java
        ├── SingleValueConversionTest.java
        ├── MultiValueLabelConversionTest.java
        └── FrameFormatTest.java
```

---

## 2. Build wiring

### 2.1 `settings.gradle` (root)

Add one line after the existing `include` list:

```groovy
include ':jfr2pprof'
```

### 2.2 `jfr2pprof/build.gradle`

```groovy
plugins {
    id 'java'
    id 'com.gradleup.shadow' version '9.0.0'
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation project(':parser')
    implementation 'org.snakeyaml:snakeyaml-engine:2.9'
    implementation 'org.slf4j:slf4j-api:2.0.16'
    runtimeOnly   'ch.qos.logback:logback-classic:1.5.18'

    testImplementation 'org.junit.jupiter:junit-jupiter:5.11.3'
    testRuntimeOnly   'org.junit.platform:junit-platform-launcher'
    testImplementation 'org.assertj:assertj-core:3.24.2'
    testImplementation 'org.openjdk.jmc:flightrecorder.writer:9.1.0'
    // PprofReader is in pprof-shell; depend on it for round-trip tests only
    testImplementation project(':pprof-shell')
}

test {
    useJUnitPlatform()
}

shadowJar {
    archiveClassifier.set('all')
    manifest {
        attributes 'Main-Class': 'io.jafar.jfr2pprof.Main'
    }
}

group = 'io.btrace'
version = rootProject.version
description = 'JFR → pprof converter'
```

---

## 3. Tasks

Tasks are ordered by dependency. Each task names exactly the file(s) to create or modify,
the public API to implement, and the acceptance criterion.

---

### Milestone 1 — Module scaffold, YAML loading, CLI skeleton

#### T1 · `build.gradle` + `settings.gradle`

- Create `jfr2pprof/build.gradle` as above.
- Add `include ':jfr2pprof'` to `settings.gradle`.
- **AC:** `./gradlew :jfr2pprof:compileJava` succeeds on an empty `Main.java`.

---

#### T2 · Config model POJOs

Files: `config/FrameFormat.java`, `config/LabelSpec.java`, `config/ValueSpec.java`,
`config/ProfileSpec.java`, `config/MappingConfig.java`.

**`FrameFormat`**

```java
// Immutable. Defaults: format="{class}.{method}", includeLineNumbers=false.
public final class FrameFormat {
    private final String format;           // "{class}.{method}" template
    private final boolean includeLineNumbers;

    public FrameFormat(String format, boolean includeLineNumbers) { … }

    public static FrameFormat defaultFormat() {
        return new FrameFormat("{class}.{method}", false);
    }

    // Renders one frame given className and methodName and lineNumber (-1 = absent).
    public String render(String className, String methodName, int lineNumber) { … }
}
```

`render()` replaces `{class}` and `{method}` in `format`, then appends `":lineNumber"` only
when `includeLineNumbers && lineNumber != -1`.

**`LabelSpec`**

```java
public final class LabelSpec {
    private final String jfrPath;     // dotted path, e.g. "sampledThread.osThreadId"
    private final String pprofKey;    // pprof label key string

    // Splits jfrPath on "." for use with Values.get().
    public String[] jfrSegments() { return jfrPath.split("\\."); }
}
```

**`ValueSpec`**

```java
public final class ValueSpec {
    private final String name;        // pprof ValueType.type
    private final String unit;        // pprof ValueType.unit
    private final String field;       // JFR field path, or "@count"
    private final double scale;       // default 1.0

    public boolean isCount() { return "@count".equals(field); }

    // Splits field on "." for use with Values.as().
    // Throws IllegalStateException when isCount() is true (caller must check first).
    public String[] fieldSegments() { … }
}
```

**`ProfileSpec`**

```java
public final class ProfileSpec {
    private final String type;           // profile-level identifier (e.g. "cpu-time", "allocation"); matches design §5 YAML key
    private final String event;          // fully-qualified JFR type name
    private final String stackField;     // usually "stackTrace"
    private final List<ValueSpec> values;
    private final List<LabelSpec> labels;
    // Assigned during config loading; index into MappingConfig.allValueTypes().
    private int sampleTypeIndex;
}
```

**`ValueTypePair`**

```java
// Carries only the pprof-relevant (name, unit) strings.
// JFR-specific fields (field path, scale) are intentionally absent.
public record ValueTypePair(String name, String unit) {}
```

**`MappingConfig`**

```java
public final class MappingConfig {
    private final FrameFormat frame;
    private final List<ProfileSpec> profiles;

    // Returns an unmodifiable list of (name, unit) pairs for all value columns across
    // all profiles, in declaration order. Used to populate pprof sample_type[].
    // Only name and unit cross into the serialization layer; JFR field paths and
    // scale factors remain in ValueSpec, which PprofBuilder never sees.
    public List<ValueTypePair> allValueTypes() { … }
}
```

**AC:** All POJOs compile; `FrameFormat.render()` unit-tested inline.

---

#### T3 · `MappingLoader`

File: `config/MappingLoader.java`

Uses `org.snakeyaml.engine.v2.api.Load` with `LoadSettings.builder().build()` (safe by default —
no arbitrary Java class instantiation).

```java
public final class MappingLoader {

    // Parses the YAML mapping file at path.
    // Throws IllegalArgumentException with a descriptive message on schema violations.
    public static MappingConfig load(Path path) throws IOException { … }

    // Package-private; parses from a reader (used in tests).
    static MappingConfig load(Reader reader) { … }
}
```

Parsing strategy — parse to raw `Map`/`List`/scalars, then map manually:

1. Top-level key `frame` (optional) → `FrameFormat`. Missing → `FrameFormat.defaultFormat()`.
2. Top-level key `profiles` (required, list) → `List<ProfileSpec>`.
3. For each profile: `type` (optional String, profile-level identifier), `event` (required String),
   `stackField` (optional, default `"stackTrace"`), `values` (required list), `labels` (optional list).
4. For each value: `name` (required), `unit` (required), `field` (required), `scale` (optional, default `1.0`).
5. For each label: `jfr` (required), `pprof` (required).
6. After loading, set `sampleTypeIndex` on each `ProfileSpec` by walking `allValueTypes()`.

Validation:
- At least one `profiles` entry.
- Each `ProfileSpec` has at least one `values` entry.
- `field` must be non-empty.
- Dotted field paths must have no empty segments (e.g. `"a..b"` is invalid).

**AC:** `MappingLoaderTest` — load the sample YAML from the design doc §5 and assert each
field value; assert validation errors for missing required keys.

---

#### T4 · `Main` skeleton

File: `Main.java`

```java
public final class Main {
    public static void main(String[] args) { System.exit(run(args)); }

    // Returns exit code: 0 = success, 1 = user error, 2 = I/O or parse error.
    static int run(String[] args) { … }
}
```

Argument parsing with plain `String[]` iteration (no external library):

| Flag | Required | Description |
|------|----------|-------------|
| `--config <path>` | yes | YAML mapping file |
| `--output <path>` | yes | output file path |
| `--no-gzip` | no | emit raw protobuf (not gzip-wrapped) |
| `--period-type <t>/<u>` | no | override pprof period_type string pair |
| positional | yes | `.jfr` recording path |

Error handling:
- Unknown flag or missing required argument → print usage to stderr, return 1.
- File not found → print message to stderr, return 2.
- No configured event type found in recording → print message to stderr, return 1
  (guards silent empty profiles — design §10).

At this stage `run()` just loads config and prints it (stub); conversion is wired in T9.

**AC:** `Main.run(new String[]{})` returns 1; `Main.run(new String[]{"--config","x","--output","y","missing.jfr"})` returns 2.

---

### Milestone 2 — Hand-rolled protobuf writer + PprofBuilder

#### T5 · `PprofWriter`

File: `proto/PprofWriter.java`

Writes the pprof wire format directly to a `ByteArrayOutputStream`. Uses only JDK classes.
Field numbers and wire types are taken from the confirmed table in design §8.

```java
final class PprofWriter {

    // Writes a varint-encoded long (LEB128, little-endian).
    void writeVarint(long value) { … }

    // Writes a tag byte: (fieldNumber << 3) | wireType.
    // wireType: 0 = VARINT, 2 = LEN.
    void writeTag(int fieldNumber, int wireType) { … }

    // Writes a field number + varint value (wire type 0).
    void writeInt64(int fieldNumber, long value) { … }

    // Writes a field number + length-delimited bytes (wire type 2).
    void writeBytes(int fieldNumber, byte[] data) { … }

    // Starts a nested message: pushes a new ByteArrayOutputStream.
    void beginMessage() { … }

    // Ends a nested message: pops, writes its bytes as a LEN field into the parent.
    void endMessage(int fieldNumber) { … }

    // Returns the accumulated bytes. Must be called only when no nested message is open.
    byte[] toByteArray() { … }
}
```

Implementation note: use a `Deque<ByteArrayOutputStream>` as the message stack.

**AC:** Unit test: write a trivial two-field message (field 1 = varint 42, field 2 = string "hi"),
then assert the raw bytes inline without any external parser:
- field 1 tag byte = `0x08` (field 1, wire type 0 VARINT), followed by varint `42` = `0x2A`
- field 2 tag byte = `0x12` (field 2, wire type 2 LEN), followed by length byte `0x02` and UTF-8 bytes `'h'`, `'i'`

---

#### T6 · `PprofBuilder`

File: `proto/PprofBuilder.java`

Accumulates all pprof model entities, then serializes to an `OutputStream` on `build()`.

```java
public final class PprofBuilder {

    // Interns a string into the string table. Index 0 is always "".
    // Returns the string table index.
    long internString(String s) { … }

    // Interns a (className, methodName, startLine) triple as a Function.
    // Returns function id (1-based, stable across calls with the same triple).
    long internFunction(String className, String methodName, long startLine) { … }

    // Interns a (functionId, lineNumber) pair as a Location.
    // Returns location id (1-based).
    long internLocation(long functionId, long lineNumber) { … }

    // Converts a JFR frames Object (ArrayType or Object[]) into an array of location ids.
    // If frames is an ArrayType, call getArray() to unwrap it to Object[] before iterating
    // (design §7: "`frames` inside that map is an ArrayType; call getArray() to get Object[]").
    // Uses FrameExtractor to render each frame to a "Class.method" string,
    // then interns function + location.
    // JFR frames[0] = leaf; pprof location_id[] is leaf-first — no reversal needed.
    long[] internStack(Object frames, FrameFormat fmt) { … }

    // Appends one pprof Sample.
    // locationIds: from internStack().
    // firstValueIndex: the offset of this profile's first value column in the global
    //   sample_type[] list (i.e. ProfileSpec.sampleTypeIndex). Required because pprof
    //   mandates that every Sample.value[] has the same length as sample_type[]; columns
    //   belonging to other profiles must be zero. PprofBuilder fills in zeros for all
    //   positions outside [firstValueIndex, firstValueIndex + vals.length).
    //   The total sample_type count is known at build() time from valueTypes.size().
    // vals: values for THIS profile's columns only (length == ProfileSpec.values.size()).
    // labels: typed Label instances built via label(key, value); numeric → Label.num, string → Label.str.
    void addSample(long[] locationIds, int firstValueIndex, long[] vals, List<Label> labels) { … }

    // Builds a Label from a pprof key and a raw JFR value.
    // If value is a Number (or its String form parses as long), writes Label.num; otherwise Label.str.
    Label label(String key, Object value) { … }

    // Returns the number of distinct samples accumulated so far (after merging).
    int sampleCount() { … }

    // Serializes the accumulated profile to out.
    // valueTypes: list of (name, unit) pairs — emitted as sample_type[].
    //   Use MappingConfig.allValueTypes() to obtain this list.
    //   PprofBuilder knows nothing about JFR field paths or scaling.
    //   valueTypes.size() determines the width of every Sample.value[] array.
    // timeNanos: epoch nanos of recording start (Profile.time_nanos field 9).
    // durationNanos: recording span (Profile.duration_nanos field 10).
    // gzip: when true, wrap output in GZIPOutputStream.
    void build(List<ValueTypePair> valueTypes, long timeNanos, long durationNanos,
               boolean gzip, OutputStream out) throws IOException { … }
}
```

**String table** invariant: index 0 is always `""` (pre-populated in constructor).

**Function dedup key:** `className + "\0" + methodName + "\0" + startLine` → `Map<String, Long>`.

**Location dedup key:** `functionId * 0x1_0000_0000L + lineNumber` → `Map<Long, Long>`.
(Assumes functionId < 2^32 and lineNumber < 2^32, which is safe for Java.)

**Sample merging:** As required by the design (§8), `addSample()` accumulates samples with
identical `(locationIds, labels)` keys by summing their value arrays rather than appending a
new `Sample` entry. Internally store `LinkedHashMap<SampleKey, long[]>` (insertion-order
preserved for determinism) where:
- `SampleKey` wraps `locationIds` (`Arrays.equals`/`Arrays.hashCode`) and `List<Label>` (value-based equality).
- The mapped `long[]` has length `totalValueTypes` (known at `build()` time from `valueTypes.size()`).
  For a new key, allocate a zero-filled array; on merge, add `vals[j]` into `stored[firstValueIndex + j]`.
- `sampleCount()` returns `map.size()`.
The `build()` method iterates the map to emit one `Sample` per unique key, using the full accumulated `long[]` as `Sample.value[]`.

**Label type detection:** if the JFR value is a `Number` (or its `String` form parses as `long`),
write `Label.num`; otherwise write `Label.str`.

**AC:** `PprofBuilderRoundTripTest` — 1 value type total (`valueTypes.size() == 1`):
- Call `addSample(locIds1, 0, new long[]{42L}, labels)` and `addSample(locIds2, 0, new long[]{7L}, labels)` with distinct stacks.
- Build → read back with `PprofReader` from `:pprof-shell`, assert:
  - `sampleTypes.size() == 1`
  - `samples.size() == 2` (distinct stacks → no merge)
  - each sample's `values.get(0)` is correct (42 and 7)
  - `stringTable.get(0).equals("")`
- Add a third call `addSample(locIds1, 0, new long[]{10L}, labels)` (same stack as first).
  - Assert `sampleCount() == 2` and first sample's value is `52` (42 + 10, merged).
- Multi-profile scenario: 2 value types total, two calls with `firstValueIndex=0` (1 val) and
  `firstValueIndex=1` (1 val); assert each built `Sample.value[]` has length 2.

---

### Milestone 3 — Single-value parse loop

#### T7 · `FrameExtractor`

File: `convert/FrameExtractor.java`

Extracts a rendered frame string from one JFR frame `Object` (which may be a `ComplexType`,
an inline `Map<String,Object>`, or `null`).

```java
final class FrameExtractor {

    // Returns null if the frame cannot be rendered (null input, missing method, etc.).
    static String extract(Object frame, FrameFormat fmt) { … }

    // Resolves a "name" value that may be:
    //   - a plain String
    //   - a ComplexType → Map{string: String}  (constant-pool symbol)
    //   - null → ""
    private static String resolveSymbol(Object nameValue) { … }
}
```

Implementation follows `JfrPathEvaluator.extractMethodNameForProfile()` (confirmed in design §7):

1. If `frame` is `ComplexType`, call `getValue()`.
2. Cast to `Map<String,Object>` as `frameMap`.
3. Get `frameMap.get("method")` → unwrap `ComplexType` → `methodMap`.
4. `className` = `resolveSymbol(methodMap.get("type") → unwrap ComplexType → typeMap.get("name"))`.
5. `methodName` = `resolveSymbol(methodMap.get("name"))`.
6. `lineNumber` = `(Integer) frameMap.get("lineNumber")` or `-1` if absent/null.
7. Return `fmt.render(className, methodName, lineNumber)`.

`resolveSymbol()` unwraps a `ComplexType` if present, then:
- Returns the value directly if it is a `String`.
- If it is a `Map`, returns `String.valueOf(map.get("string"))`.
- Otherwise returns `""`.

**AC:** `FrameFormatTest`:
- Construct a synthetic `Map` matching the confirmed JFR frame shape, call `FrameExtractor.extract()`, assert rendered string.
- Verify `null` frame returns `null` without throwing.
- Verify `lineNumber = -1` with `includeLineNumbers=true` omits the line suffix.

---

#### T8 · `Jfr2PprofConverter`

File: `convert/Jfr2PprofConverter.java`

Main conversion orchestrator.

```java
public final class Jfr2PprofConverter {

    // Converts jfrPath according to config, writes pprof to out.
    // gzip: whether to gzip-wrap the output (design §10 --no-gzip flag).
    // Returns the number of samples written.
    // Throws IllegalStateException with a descriptive message if no configured event type
    // is found in the recording (design §10 "exit non-zero" requirement).
    public int convert(Path jfrPath, MappingConfig config,
                       boolean gzip, OutputStream out) throws IOException { … }
}
```

Internal implementation:

```java
// Build event-name → ProfileSpec lookup (O(1) per event during parsing).
Map<String, ProfileSpec> byEvent = new HashMap<>();
for (ProfileSpec spec : config.profiles()) {
    byEvent.put(spec.event(), spec);
}

PprofBuilder builder = new PprofBuilder();
long[] timeNanos    = {0L};   // epoch nanos of first chunk start
long[] durationNanos = {0L};  // sum of chunk durations
long[] lastChunkId  = {-1L};  // tracks last seen chunk to avoid double-counting duration

try (UntypedJafarParser p = UntypedJafarParser.open(
        jfrPath, ParsingContext.create(), UntypedStrategy.FULL_ITERATION)) {

    p.handle((type, value, ctl) -> {
        // --- Accumulate timing once per chunk boundary (design §6) ---
        Control.ChunkInfo ci = ctl.chunkInfo();
        if (ci.chunkId() != lastChunkId[0]) {
            lastChunkId[0] = ci.chunkId();
            if (timeNanos[0] == 0L) {
                timeNanos[0] = ci.startTime().toEpochMilli() * 1_000_000L;
            }
            durationNanos[0] += ci.duration().toNanos();
        }

        // --- Per-event processing ---
        ProfileSpec spec = byEvent.get(type.getName());
        if (spec == null) return;

        Object frames = Values.get(value, spec.stackField(), "frames");
        long[] locationIds = builder.internStack(frames, config.frame());

        long[] vals = new long[spec.values().size()];
        for (int i = 0; i < vals.length; i++) {
            ValueSpec v = spec.values().get(i);
            if (v.isCount()) {
                vals[i] = 1L;
            } else {
                vals[i] = (long) (Values.as(value, Long.class,
                                            (Object[]) v.fieldSegments())
                                       .orElse(0L) * v.scale());
            }
        }

        List<Label> labels = spec.labels().stream()
            .map(l -> builder.label(l.pprofKey(), Values.get(value, (Object[]) l.jfrSegments())))
            .toList();

        // spec.sampleTypeIndex() is the offset of this profile's first column in the
        // global sample_type[] list. PprofBuilder pads the remaining columns with zeros.
        builder.addSample(locationIds, spec.sampleTypeIndex(), vals, labels);
    });
    p.run();
}

if (builder.sampleCount() == 0) {
    throw new IllegalStateException(
        "No events matched any configured profile type in: " + jfrPath);
}

builder.build(config.allValueTypes(), timeNanos[0], durationNanos[0], gzip, out);
return builder.sampleCount();
```

**AC:** `SingleValueConversionTest`:
- Synthesize a JFR with a single event type (`MyEvent`) with a `stackTrace` and a `weight` field.
- Write a YAML config mapping `MyEvent` → `cpu-time / nanoseconds / weight`.
- Call `Jfr2PprofConverter.convert()`.
- Read back with `PprofReader`, assert `sampleTypes = [{type:"cpu-time", unit:"nanoseconds"}]`
  and at least one sample with a non-zero value.

---

#### T9 · Wire `Jfr2PprofConverter` into `Main`

Extend `Main.run()` to:
1. Load `MappingConfig` from `--config`.
2. Open output `FileOutputStream` to `--output`.
3. Call `Jfr2PprofConverter.convert()`.
4. On `IllegalStateException` ("no events matched") → print to stderr, return 1.
5. On `IOException` → print to stderr, return 2.
6. On success → return 0.

**AC:** End-to-end smoke: `Main.run(["--config","…","--output","out.pprof","recording.jfr"])` produces a non-empty `out.pprof`.

---

### Milestone 4 — Multi-value events, labels, duration

#### T10 · Multi-value + `@count` sentinel (already in T8 loop above)

The T8 loop already handles multiple `ValueSpec` entries per profile and the `@count` sentinel
(`v.isCount()` → emit `1L`). This task validates it with a test.

**AC:** `MultiValueLabelConversionTest`:
- Synthesize a JFR with an allocation event carrying a `weight` field and a `sampledThread`
  complex value with `osThreadId` (long) and `javaName` (String).
- Config: two value columns (`alloc-samples/@count` and `alloc-space/bytes/weight`) and two
  labels.
- Assert output has 2 `sampleTypes`, each sample has 2 values, and labels include both a
  numeric and a string label.

---

#### T11 · Duration/time fields

Verified in T8 via the chunk-id guard logic. Explicit test:

**AC:** `SingleValueConversionTest` extended — assert `profile.durationNanos > 0` and
`profile.timeNanos > 0` in the read-back result.

---

### Milestone 5 — Frame-format contract

#### T12 · `FrameFormat.render()` + `FrameExtractor` edge cases

**AC:** `FrameFormatTest` (extends T7):
- Custom `format: "{method}.{class}"` (reversed) → verify output order.
- `format: "{class}#{method}"` → verify separator.
- `includeLineNumbers: true` + `lineNumber = 42` → `"Foo.bar:42"`.
- `includeLineNumbers: true` + `lineNumber = -1` → `"Foo.bar"` (no suffix).
- Nested `{string:…}` constant-pool name shape → resolved correctly.

Note: validation against a real DD JFR is blocked on §13 open question 1 (obtaining a
live java-profiler recording). This task covers the confirmed field shapes from the design doc.

---

### Milestone 6 — CI cross-validation (deferred)

Blocked on §13 open question 5 (CI setup for `google/pprof`). Tracked separately; no
implementation tasks defined here.

---

## 4. Invariants and constraints

Drawn directly from the design doc — implementation must respect all of these:

| # | Invariant | Source |
|---|-----------|--------|
| I1 | No vendor-specific logic in tool code. Event names only in consumer YAML. | design §1, §2 |
| I2 | String table index 0 is always `""`. | design §8 |
| I3 | `Values.get()`/`Values.as()` receive `(Object[])` cast when passing a `String[]`. | design §7 |
| I4 | JFR `frames[0]` is leaf. pprof `location_id[]` is leaf-first. No reversal for bottom-up. | design §7 |
| I5 | Emit raw accumulated values; never convert to per-second rates. | design §6 |
| I6 | Chunk duration counted once per chunk (use `chunkId()` guard). | design §6 |
| I7 | Exit non-zero when no configured event type found. | design §10 |
| I8 | `--no-gzip` emits raw protobuf bytes (still valid input for `google/pprof`). | design §10 |
| I9 | No external deps beyond `parser`, `snakeyaml-engine`, `slf4j`/logback. | design §11 |
| I10 | `snakeyaml-engine` used only in `MappingLoader`; must not leak into `:parser`. | design §9 |

---

## 5. Task dependency graph

```
T1 (build)
  └─ T2 (POJOs)
       └─ T3 (MappingLoader)
            └─ T4 (Main skeleton)
                 └─ T9 (Main wiring) ──────────────────────────────┐
T5 (PprofWriter)                                                    │
  └─ T6 (PprofBuilder)                                             │
       └─ T7 (FrameExtractor)                                      │
            └─ T8 (Jfr2PprofConverter) ─── T10, T11 ──────────────┘
                                                                    │
                                                        T12 (FrameFormat edge cases)
```

---

## 6. Out of scope

- No pprof reading (`:pprof-shell` already does this; used only in tests via `testImplementation`).
- No flame graphs or HTML output.
- No streaming/live conversion.
- No Datadog-specific event names in any source file.
- No `Mapping` message (native frames — deferred per §13 open question 4).
- google/pprof CI integration (milestone 6 — deferred per §13 open question 5).
- jbang installability (`jbang app install jfr2pprof@btraceio`) — requires `//DEPS` / `//JAVA` headers in `Main.java`; deferred until the shadow-jar build is stable (design §10).

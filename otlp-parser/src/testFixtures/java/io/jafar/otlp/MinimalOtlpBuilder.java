package io.jafar.otlp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds minimal in-memory OTLP profiles files (binary protobuf, no gzip) for unit tests.
 *
 * <p>The string table is built automatically — add strings with {@link #addString(String)} and use
 * the returned index wherever a string-table reference is expected.
 *
 * <p>Every dictionary table starts with its zero value, as opentelemetry-proto v1.10.0 requires
 * ("MUST be the zero value ... and present") and async-profiler 4.6 writes: an empty mapping,
 * location, function, attribute and stack at index 0, and {@code ""} in the string table. The
 * {@code add*} methods return the absolute index of what they add, so the first function is 1.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * MinimalOtlpBuilder b = new MinimalOtlpBuilder();
 * int typeIdx = b.addString("cpu");
 * int unitIdx = b.addString("nanoseconds");
 * b.setSampleType(typeIdx, unitIdx);
 * b.setDurationNanos(30_000_000_000L);
 *
 * int fnIdx = b.addFunction(b.addString("com.example.Foo.bar"), b.addString("Foo.java"));
 * int locIdx = b.addLocation(fnIdx, 10);
 * int stackIdx = b.addStack(List.of(locIdx));
 *
 * int attrIdx = b.addAttribute(b.addString("thread"), "main");
 * b.addSample(stackIdx, List.of(attrIdx), List.of(1_000_000L));
 * Path file = b.write(tempDir);
 * }</pre>
 */
public final class MinimalOtlpBuilder {

  // Protobuf wire types
  private static final int WIRE_VARINT = 0;
  private static final int WIRE_I64 = 1;
  private static final int WIRE_LEN = 2;

  // ---- state ----
  // String table: index 0 = "" (null sentinel)
  private final List<String> strings = new ArrayList<>();

  /** The profiles written so far; the last is the one the profile-level setters change. */
  private final List<ProfileDef> profiles = new ArrayList<>(List.of(new ProfileDef()));

  private final List<FunctionDef> functions = new ArrayList<>();
  private final List<LocationDef> locations = new ArrayList<>();
  private final List<StackDef> stacks = new ArrayList<>();
  private final List<AttributeDef> attributes = new ArrayList<>();

  private record FunctionDef(int nameIdx, int filenameIdx) {}

  private record LocationDef(int functionIndex, long line) {}

  private record StackDef(List<Integer> locationIndices) {}

  private record AttributeDef(int keyIdx, String value) {}

  private record SampleDef(
      int stackIndex, List<Integer> attrIndices, List<Long> values, List<Long> timestamps) {}

  /** One profile: a sample type (singular in OTLP), its samples, period and time. */
  private static final class ProfileDef {
    int sampleTypeTypeIdx;
    int sampleTypeUnitIdx;
    final List<SampleDef> samples = new ArrayList<>();
    long durationNanos;
    long timeUnixNano;
    boolean hasPeriodType;
    int periodTypeTypeIdx;
    int periodTypeUnitIdx;
    long period;
  }

  private ProfileDef current() {
    return profiles.get(profiles.size() - 1);
  }

  public MinimalOtlpBuilder() {
    // index 0 must be the empty string (null sentinel)
    strings.add("");
  }

  /** Adds a string to the string table and returns its index (the first is 1). */
  public int addString(String s) {
    strings.add(s);
    return strings.size() - 1;
  }

  /** Sets the sample type (e.g. cpu / nanoseconds). */
  public MinimalOtlpBuilder setSampleType(int typeIdx, int unitIdx) {
    current().sampleTypeTypeIdx = typeIdx;
    current().sampleTypeUnitIdx = unitIdx;
    return this;
  }

  /**
   * Starts another profile in the same file, sharing the dictionary: the profile-level setters and
   * {@code addSample} then apply to it.
   */
  public MinimalOtlpBuilder addProfile(int typeIdx, int unitIdx) {
    profiles.add(new ProfileDef());
    return setSampleType(typeIdx, unitIdx);
  }

  /** Sets the period type and period (e.g. cpu / nanoseconds, 10 ms). */
  public MinimalOtlpBuilder setPeriod(int typeIdx, int unitIdx, long period) {
    current().hasPeriodType = true;
    current().periodTypeTypeIdx = typeIdx;
    current().periodTypeUnitIdx = unitIdx;
    current().period = period;
    return this;
  }

  /**
   * Adds a function and returns its index into the function table (the first is 1, after the zero
   * value). {@code nameIdx} and {@code filenameIdx} are string table indices.
   */
  public int addFunction(int nameIdx, int filenameIdx) {
    functions.add(new FunctionDef(nameIdx, filenameIdx));
    return functions.size(); // index 0 is the zero value
  }

  /**
   * Adds a location referencing a single function. Returns its index into the location table (the
   * first is 1). {@code functionIndex} is an index into the function table.
   */
  public int addLocation(int functionIndex, long lineNumber) {
    locations.add(new LocationDef(functionIndex, lineNumber));
    return locations.size(); // index 0 is the zero value
  }

  /**
   * Adds a stack with the given location indices, leaf first. Returns its index into the stack
   * table (the first is 1).
   */
  public int addStack(List<Integer> locationIndices) {
    stacks.add(new StackDef(new ArrayList<>(locationIndices)));
    return stacks.size(); // index 0 is the zero value
  }

  /**
   * Adds an attribute (key-value pair). Returns its index into the attribute table (the first is
   * 1). {@code keyIdx} is a string table index. Value is stored as a string.
   */
  public int addAttribute(int keyIdx, String value) {
    attributes.add(new AttributeDef(keyIdx, value));
    return attributes.size(); // index 0 is the zero value
  }

  /**
   * Adds a sample.
   *
   * @param stackIndex index into the stack table
   * @param attrIndices indices into the attribute table
   * @param values the sample's values, in the profile's sample type
   */
  public MinimalOtlpBuilder addSample(
      int stackIndex, List<Integer> attrIndices, List<Long> values) {
    return addSample(stackIndex, attrIndices, values, List.of());
  }

  /**
   * Adds a sample with per-event timestamps. Without values, each timestamp counts one; with
   * values, there must be one value per timestamp.
   */
  public MinimalOtlpBuilder addSample(
      int stackIndex, List<Integer> attrIndices, List<Long> values, List<Long> timestamps) {
    current()
        .samples
        .add(
            new SampleDef(
                stackIndex,
                new ArrayList<>(attrIndices),
                new ArrayList<>(values),
                new ArrayList<>(timestamps)));
    return this;
  }

  /** Sets the recording duration in nanoseconds. */
  public MinimalOtlpBuilder setDurationNanos(long nanos) {
    current().durationNanos = nanos;
    return this;
  }

  /** Sets the collection timestamp in nanoseconds since epoch. */
  public MinimalOtlpBuilder setTimeUnixNano(long nanos) {
    current().timeUnixNano = nanos;
    return this;
  }

  /** Writes the profile to a temp file inside {@code dir} and returns its path. */
  public Path write(Path dir) throws IOException {
    Path file = dir.resolve("test-" + System.nanoTime() + ".otlp");
    byte[] proto = encodeProfilesData();
    Files.write(file, proto);
    return file;
  }

  // ---- encoding ----

  private byte[] encodeProfilesData() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    // field 1: resource_profiles (LEN)
    writeLen(out, 1, encodeResourceProfiles());
    // field 2: dictionary (LEN)
    writeLen(out, 2, encodeDictionary());
    return out.toByteArray();
  }

  private byte[] encodeResourceProfiles() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    // field 2: scope_profiles (LEN) — field 2 per RESOURCE_PROFILES_SCOPE_PROFILES = 2
    writeLen(out, 2, encodeScopeProfiles());
    return out.toByteArray();
  }

  private byte[] encodeScopeProfiles() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    // field 2: profiles (LEN) — field 2 per SCOPE_PROFILES_PROFILES = 2
    for (ProfileDef profile : profiles) {
      writeLen(out, 2, encodeProfile(profile));
    }
    return out.toByteArray();
  }

  private static byte[] encodeProfile(ProfileDef p) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    // field 1: sample_type (LEN)
    writeLen(out, 1, encodeValueType(p.sampleTypeTypeIdx, p.sampleTypeUnitIdx));
    // field 2: samples (repeated LEN)
    for (SampleDef s : p.samples) {
      writeLen(out, 2, encodeSample(s));
    }
    // field 3: time_unix_nano (I64 / wire type 1)
    if (p.timeUnixNano != 0) {
      writeFixed64(out, 3, p.timeUnixNano);
    }
    // field 4: duration_nanos (varint)
    if (p.durationNanos != 0) {
      writeVarint(out, 4, p.durationNanos);
    }
    if (p.hasPeriodType) {
      // field 5: period_type (LEN), field 6: period (varint)
      writeLen(out, 5, encodeValueType(p.periodTypeTypeIdx, p.periodTypeUnitIdx));
      writeVarint(out, 6, p.period);
    }
    return out.toByteArray();
  }

  private static byte[] encodeValueType(int typeIdx, int unitIdx) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    writeVarint(out, 1, typeIdx);
    writeVarint(out, 2, unitIdx);
    return out.toByteArray();
  }

  private static byte[] encodeSample(SampleDef s) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    // field 1: stack_index (varint)
    writeVarint(out, 1, s.stackIndex());
    // field 2: attribute_indices (packed int32)
    if (!s.attrIndices().isEmpty()) {
      ByteArrayOutputStream packed = new ByteArrayOutputStream();
      for (int idx : s.attrIndices()) {
        writeRawVarint(packed, idx);
      }
      writeLen(out, 2, packed.toByteArray());
    }
    // field 4: values (packed int64)
    if (!s.values().isEmpty()) {
      ByteArrayOutputStream packed = new ByteArrayOutputStream();
      for (long v : s.values()) {
        writeRawVarint(packed, v);
      }
      writeLen(out, 4, packed.toByteArray());
    }
    // field 5: timestamps_unix_nano (packed fixed64)
    if (!s.timestamps().isEmpty()) {
      ByteArrayOutputStream packed = new ByteArrayOutputStream();
      for (long t : s.timestamps()) {
        for (int i = 0; i < 8; i++) {
          packed.write((int) ((t >>> (8 * i)) & 0xFF));
        }
      }
      writeLen(out, 5, packed.toByteArray());
    }
    return out.toByteArray();
  }

  private byte[] encodeDictionary() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    // Each table's zero value comes first, at index 0 (an empty message).
    // field 1: mapping_table (repeated LEN): the zero value only
    writeLen(out, 1, new byte[0]);
    // field 2: location_table (repeated LEN)
    writeLen(out, 2, new byte[0]);
    for (LocationDef loc : locations) {
      writeLen(out, 2, encodeLocation(loc));
    }
    // field 3: function_table (repeated LEN)
    writeLen(out, 3, new byte[0]);
    for (FunctionDef fn : functions) {
      writeLen(out, 3, encodeFunction(fn));
    }
    // field 5: string_table (repeated bytes)
    for (String s : strings) {
      writeLen(out, 5, s.getBytes(StandardCharsets.UTF_8));
    }
    // field 6: attribute_table (repeated LEN)
    writeLen(out, 6, new byte[0]);
    for (AttributeDef attr : attributes) {
      writeLen(out, 6, encodeAttribute(attr));
    }
    // field 7: stack_table (repeated LEN)
    writeLen(out, 7, new byte[0]);
    for (StackDef stack : stacks) {
      writeLen(out, 7, encodeStack(stack));
    }
    return out.toByteArray();
  }

  private static byte[] encodeLocation(LocationDef loc) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    // field 3: lines (repeated LEN)
    ByteArrayOutputStream lineOut = new ByteArrayOutputStream();
    writeVarint(lineOut, 1, loc.functionIndex()); // function_index
    writeVarint(lineOut, 2, loc.line()); // line number
    writeLen(out, 3, lineOut.toByteArray());
    return out.toByteArray();
  }

  private static byte[] encodeFunction(FunctionDef fn) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    writeVarint(out, 1, fn.nameIdx()); // name_strindex
    writeVarint(out, 3, fn.filenameIdx()); // filename_strindex
    return out.toByteArray();
  }

  private static byte[] encodeAttribute(AttributeDef attr) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    // field 1: key_strindex (varint)
    writeVarint(out, 1, attr.keyIdx());
    // field 2: value (AnyValue LEN) — encode as string_value (field 1 of AnyValue)
    ByteArrayOutputStream anyValue = new ByteArrayOutputStream();
    writeLen(anyValue, 1, attr.value().getBytes(StandardCharsets.UTF_8));
    writeLen(out, 2, anyValue.toByteArray());
    return out.toByteArray();
  }

  private static byte[] encodeStack(StackDef stack) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    if (!stack.locationIndices().isEmpty()) {
      // field 1: location_indices (packed int32)
      ByteArrayOutputStream packed = new ByteArrayOutputStream();
      for (int idx : stack.locationIndices()) {
        writeRawVarint(packed, idx);
      }
      writeLen(out, 1, packed.toByteArray());
    }
    return out.toByteArray();
  }

  // ---- low-level helpers ----

  private static void writeVarint(OutputStream out, int fieldNumber, long value)
      throws IOException {
    writeTag(out, fieldNumber, WIRE_VARINT);
    writeRawVarint(out, value);
  }

  private static void writeFixed64(OutputStream out, int fieldNumber, long value)
      throws IOException {
    writeTag(out, fieldNumber, WIRE_I64);
    for (int i = 0; i < 8; i++) {
      out.write((int) (value & 0xFF));
      value >>>= 8;
    }
  }

  private static void writeLen(OutputStream out, int fieldNumber, byte[] data) throws IOException {
    writeTag(out, fieldNumber, WIRE_LEN);
    writeRawVarint(out, data.length);
    out.write(data);
  }

  private static void writeTag(OutputStream out, int fieldNumber, int wireType) throws IOException {
    writeRawVarint(out, ((long) fieldNumber << 3) | wireType);
  }

  private static void writeRawVarint(OutputStream out, long value) throws IOException {
    while ((value & ~0x7FL) != 0) {
      out.write((int) ((value & 0x7F) | 0x80));
      value >>>= 7;
    }
    out.write((int) value);
  }
}

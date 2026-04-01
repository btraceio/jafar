package io.jafar.otelp.shell;

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
 * <p>Usage:
 *
 * <pre>{@code
 * MinimalOtelpBuilder b = new MinimalOtelpBuilder();
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
public final class MinimalOtelpBuilder {

  // Protobuf wire types
  private static final int WIRE_VARINT = 0;
  private static final int WIRE_I64 = 1;
  private static final int WIRE_LEN = 2;

  // ---- state ----
  // String table: index 0 = "" (null sentinel)
  private final List<String> strings = new ArrayList<>();

  // Sample type (singular in OTLP)
  private int sampleTypeTypeIdx = 0;
  private int sampleTypeUnitIdx = 0;

  private final List<FunctionDef> functions = new ArrayList<>();
  private final List<LocationDef> locations = new ArrayList<>();
  private final List<StackDef> stacks = new ArrayList<>();
  private final List<AttributeDef> attributes = new ArrayList<>();
  private final List<SampleDef> samples = new ArrayList<>();
  private long durationNanos = 0;
  private long timeUnixNano = 0;

  private record FunctionDef(int nameIdx, int filenameIdx) {}

  private record LocationDef(int functionIndex, long line) {}

  private record StackDef(List<Integer> locationIndices) {}

  private record AttributeDef(int keyIdx, String value) {}

  private record SampleDef(int stackIndex, List<Integer> attrIndices, List<Long> values) {}

  public MinimalOtelpBuilder() {
    // index 0 must be the empty string (null sentinel)
    strings.add("");
  }

  /** Adds a string to the string table and returns its 1-based index. */
  public int addString(String s) {
    strings.add(s);
    return strings.size() - 1;
  }

  /** Sets the sample type (e.g. cpu / nanoseconds). */
  public MinimalOtelpBuilder setSampleType(int typeIdx, int unitIdx) {
    this.sampleTypeTypeIdx = typeIdx;
    this.sampleTypeUnitIdx = unitIdx;
    return this;
  }

  /**
   * Adds a function and returns its 1-based index into the function table. {@code nameIdx} and
   * {@code filenameIdx} are string table indices.
   */
  public int addFunction(int nameIdx, int filenameIdx) {
    functions.add(new FunctionDef(nameIdx, filenameIdx));
    return functions.size(); // 1-based index
  }

  /**
   * Adds a location referencing a single function. Returns its 1-based index into the location
   * table. {@code functionIndex} is a 1-based index into the function table.
   */
  public int addLocation(int functionIndex, long lineNumber) {
    locations.add(new LocationDef(functionIndex, lineNumber));
    return locations.size(); // 1-based index
  }

  /**
   * Adds a stack with the given location indices. Returns its 1-based index into the stack table.
   * Location indices are 1-based.
   */
  public int addStack(List<Integer> locationIndices) {
    stacks.add(new StackDef(new ArrayList<>(locationIndices)));
    return stacks.size(); // 1-based index
  }

  /**
   * Adds an attribute (key-value pair). Returns its 1-based index into the attribute table. {@code
   * keyIdx} is a string table index. Value is stored as a string.
   */
  public int addAttribute(int keyIdx, String value) {
    attributes.add(new AttributeDef(keyIdx, value));
    return attributes.size(); // 1-based index
  }

  /**
   * Adds a sample.
   *
   * @param stackIndex 1-based index into the stack table
   * @param attrIndices 1-based indices into the attribute table
   * @param values one value per sample type
   */
  public MinimalOtelpBuilder addSample(
      int stackIndex, List<Integer> attrIndices, List<Long> values) {
    samples.add(new SampleDef(stackIndex, new ArrayList<>(attrIndices), new ArrayList<>(values)));
    return this;
  }

  /** Sets the recording duration in nanoseconds. */
  public MinimalOtelpBuilder setDurationNanos(long nanos) {
    this.durationNanos = nanos;
    return this;
  }

  /** Sets the collection timestamp in nanoseconds since epoch. */
  public MinimalOtelpBuilder setTimeUnixNano(long nanos) {
    this.timeUnixNano = nanos;
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
    writeLen(out, 2, encodeProfile());
    return out.toByteArray();
  }

  private byte[] encodeProfile() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    // field 1: sample_type (LEN)
    writeLen(out, 1, encodeValueType(sampleTypeTypeIdx, sampleTypeUnitIdx));
    // field 2: samples (repeated LEN)
    for (SampleDef s : samples) {
      writeLen(out, 2, encodeSample(s));
    }
    // field 3: time_unix_nano (I64 / wire type 1)
    if (timeUnixNano != 0) {
      writeFixed64(out, 3, timeUnixNano);
    }
    // field 4: duration_nanos (varint)
    if (durationNanos != 0) {
      writeVarint(out, 4, durationNanos);
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
    return out.toByteArray();
  }

  private byte[] encodeDictionary() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    // field 2: location_table (repeated LEN)
    for (LocationDef loc : locations) {
      writeLen(out, 2, encodeLocation(loc));
    }
    // field 3: function_table (repeated LEN)
    for (FunctionDef fn : functions) {
      writeLen(out, 3, encodeFunction(fn));
    }
    // field 5: string_table (repeated bytes)
    for (String s : strings) {
      writeLen(out, 5, s.getBytes(StandardCharsets.UTF_8));
    }
    // field 6: attribute_table (repeated LEN)
    for (AttributeDef attr : attributes) {
      writeLen(out, 6, encodeAttribute(attr));
    }
    // field 7: stack_table (repeated LEN)
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

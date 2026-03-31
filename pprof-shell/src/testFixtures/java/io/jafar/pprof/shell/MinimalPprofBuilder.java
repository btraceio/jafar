package io.jafar.pprof.shell;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Builds minimal in-memory pprof files (gzip-compressed protobuf) for unit tests. Produces
 * parseable profiles without requiring an external file or a running profiler.
 *
 * <p>The string table is built automatically — add strings with {@link #addString(String)} and use
 * the returned index wherever a string-table reference is expected.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * MinimalPprofBuilder b = new MinimalPprofBuilder();
 * int cpu  = b.addString("cpu");
 * int ns   = b.addString("nanoseconds");
 * b.addSampleType(cpu, ns);
 * b.setDurationNanos(30_000_000_000L);
 *
 * int fnId = b.addFunction(b.addString("myMethod"), b.addString("MyClass.java"));
 * b.addLocation(1, fnId, 10);
 *
 * b.addSample(List.of(1L), List.of(1_000_000L), List.of());
 * Path file = b.write(tempDir);
 * }</pre>
 */
public final class MinimalPprofBuilder {

  // ---- protobuf wire types ----
  private static final int WIRE_VARINT = 0;
  private static final int WIRE_LEN = 2;

  // ---- state ----
  private final List<String> strings = new ArrayList<>();
  private final List<long[]> sampleTypes = new ArrayList<>(); // [typeIdx, unitIdx]
  private final List<SampleDef> samples = new ArrayList<>();
  private final List<LocationDef> locations = new ArrayList<>();
  private final List<FunctionDef> functions = new ArrayList<>();
  private long durationNanos = 0;
  private long timeNanos = 0;

  private record SampleDef(List<Long> locationIds, List<Long> values, List<long[]> labels) {}

  private record LocationDef(long id, long functionId, long lineNumber) {}

  private record FunctionDef(long id, int nameIdx, int filenameIdx, long startLine) {}

  public MinimalPprofBuilder() {
    // index 0 must be the empty string
    strings.add("");
  }

  /** Adds a string to the string table and returns its index. */
  public int addString(String s) {
    strings.add(s);
    return strings.size() - 1;
  }

  /** Registers a sample type (e.g. cpu / nanoseconds). */
  public MinimalPprofBuilder addSampleType(int typeIdx, int unitIdx) {
    sampleTypes.add(new long[] {typeIdx, unitIdx});
    return this;
  }

  /**
   * Adds a function and returns its ID (1-based). {@code nameIdx} and {@code filenameIdx} are
   * indices into the string table.
   */
  public long addFunction(int nameIdx, int filenameIdx) {
    long id = functions.size() + 1;
    functions.add(new FunctionDef(id, nameIdx, filenameIdx, 0));
    return id;
  }

  /**
   * Adds a location referencing a single function line. Returns the location ID. Multiple locations
   * can reference the same function.
   */
  public long addLocation(long functionId, long lineNumber) {
    long id = locations.size() + 1;
    locations.add(new LocationDef(id, functionId, lineNumber));
    return id;
  }

  /**
   * Adds a sample.
   *
   * @param locationIds ordered list of location IDs (leaf first)
   * @param values one value per sample type (must match sample type count)
   * @param labels list of {@code [keyIdx, strIdx, num, numUnitIdx]} arrays
   */
  public MinimalPprofBuilder addSample(
      List<Long> locationIds, List<Long> values, List<long[]> labels) {
    samples.add(new SampleDef(locationIds, values, labels));
    return this;
  }

  /** Sets the recording duration in nanoseconds. */
  public MinimalPprofBuilder setDurationNanos(long nanos) {
    this.durationNanos = nanos;
    return this;
  }

  /** Sets the collection timestamp in nanoseconds since epoch. */
  public MinimalPprofBuilder setTimeNanos(long nanos) {
    this.timeNanos = nanos;
    return this;
  }

  /** Writes the profile to a temp file inside {@code dir} and returns its path. */
  public Path write(Path dir) throws IOException {
    Path file = dir.resolve("test-" + System.nanoTime() + ".pb.gz");
    byte[] proto = encodeProfile();
    try (OutputStream fos = Files.newOutputStream(file);
        GZIPOutputStream gz = new GZIPOutputStream(fos)) {
      gz.write(proto);
    }
    return file;
  }

  // ---- protobuf encoding ----

  private byte[] encodeProfile() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    // field 1: sampleType (repeated)
    for (long[] st : sampleTypes) {
      writeLen(out, 1, encodeSampleType(st[0], st[1]));
    }

    // field 2: sample (repeated)
    for (SampleDef s : samples) {
      writeLen(out, 2, encodeSample(s));
    }

    // field 4: location (repeated)
    for (LocationDef loc : locations) {
      writeLen(out, 4, encodeLocation(loc));
    }

    // field 5: function (repeated)
    for (FunctionDef fn : functions) {
      writeLen(out, 5, encodeFunction(fn));
    }

    // field 6: stringTable (repeated)
    for (String s : strings) {
      writeLen(out, 6, s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // field 10: durationNanos
    if (durationNanos != 0) {
      writeVarint(out, 10, durationNanos);
    }

    // field 9: timeNanos
    if (timeNanos != 0) {
      writeVarint(out, 9, timeNanos);
    }

    return out.toByteArray();
  }

  private static byte[] encodeSampleType(long typeIdx, long unitIdx) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    writeVarint(out, 1, typeIdx);
    writeVarint(out, 2, unitIdx);
    return out.toByteArray();
  }

  private static byte[] encodeSample(SampleDef s) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (long locId : s.locationIds()) {
      writeVarint(out, 1, locId);
    }
    for (long val : s.values()) {
      writeVarint(out, 2, val);
    }
    for (long[] label : s.labels()) {
      writeLen(out, 3, encodeLabel(label));
    }
    return out.toByteArray();
  }

  private static byte[] encodeLabel(long[] label) throws IOException {
    // label: [keyIdx, strIdx, num, numUnitIdx]
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    if (label[0] != 0) writeVarint(out, 1, label[0]);
    if (label[1] != 0) writeVarint(out, 2, label[1]);
    if (label[2] != 0) writeVarint(out, 3, label[2]);
    if (label[3] != 0) writeVarint(out, 4, label[3]);
    return out.toByteArray();
  }

  private static byte[] encodeLocation(LocationDef loc) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    writeVarint(out, 1, loc.id());
    // line sub-message: field 4
    ByteArrayOutputStream lineOut = new ByteArrayOutputStream();
    writeVarint(lineOut, 1, loc.functionId());
    writeVarint(lineOut, 2, loc.lineNumber());
    writeLen(out, 4, lineOut.toByteArray());
    return out.toByteArray();
  }

  private static byte[] encodeFunction(FunctionDef fn) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    writeVarint(out, 1, fn.id());
    writeVarint(out, 2, fn.nameIdx());
    writeVarint(out, 4, fn.filenameIdx());
    if (fn.startLine() != 0) writeVarint(out, 5, fn.startLine());
    return out.toByteArray();
  }

  // ---- low-level varint / length-delimited helpers ----

  private static void writeVarint(OutputStream out, int fieldNumber, long value)
      throws IOException {
    writeTag(out, fieldNumber, WIRE_VARINT);
    writeRawVarint(out, value);
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

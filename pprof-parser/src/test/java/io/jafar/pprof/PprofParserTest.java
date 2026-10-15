package io.jafar.pprof;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.pprof.api.PprofParser;
import io.jafar.pprof.api.PprofProfile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link PprofParser}: round-trip encoding and parsing of synthetic profiles. */
class PprofParserTest {

  @TempDir Path tempDir;

  /**
   * Builds a profile with known values and verifies that the reader reconstructs them correctly.
   */
  @Test
  void roundTripBasicProfile() throws Exception {
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    int cpu = b.addString("cpu");
    int ns = b.addString("nanoseconds");
    int fnName = b.addString("com.example.Foo.bar");
    int filename = b.addString("Foo.java");
    int threadKey = b.addString("thread");
    int mainStr = b.addString("main");

    b.addSampleType(cpu, ns);
    b.setDurationNanos(5_000_000_000L);

    long fn1 = b.addFunction(fnName, filename);
    long loc1 = b.addLocation(fn1, 42);

    b.addSample(List.of(loc1), List.of(1_234_567L), List.of(new long[] {threadKey, mainStr, 0, 0}));

    Path file = b.write(tempDir);
    PprofProfile.Profile profile = PprofParser.parse(file);

    // sample types
    assertEquals(1, profile.sampleTypes().size());
    assertEquals("cpu", profile.sampleTypes().get(0).type());
    assertEquals("nanoseconds", profile.sampleTypes().get(0).unit());

    // duration
    assertEquals(5_000_000_000L, profile.durationNanos());

    // samples
    assertEquals(1, profile.samples().size());
    PprofProfile.Sample sample = profile.samples().get(0);
    assertArrayEquals(new long[] {1_234_567L}, sample.values());
    assertArrayEquals(new long[] {loc1}, sample.locationIds());

    // labels
    assertEquals(1, sample.labels().size());
    PprofProfile.Label label = sample.labels().get(0);
    assertEquals("thread", label.key());
    assertEquals("main", label.str());

    // functions
    assertEquals(1, profile.functions().size());
    PprofProfile.Function fn = profile.functions().get(0);
    assertEquals("com.example.Foo.bar", fn.name());
    assertEquals("Foo.java", fn.filename());

    // locations
    assertEquals(1, profile.locations().size());
    PprofProfile.Location loc = profile.locations().get(0);
    assertEquals(1, loc.lines().size());
    assertEquals(42L, loc.lines().get(0).lineNumber());
    assertEquals(fn.id(), loc.lines().get(0).functionId());
  }

  @Test
  void multipleSampleTypes() throws Exception {
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    int cpu = b.addString("cpu");
    int ns = b.addString("nanoseconds");
    int alloc = b.addString("alloc_objects");
    int count = b.addString("count");

    b.addSampleType(cpu, ns);
    b.addSampleType(alloc, count);

    long fn1 = b.addFunction(b.addString("com.Foo.bar"), b.addString("Foo.java"));
    long loc1 = b.addLocation(fn1, 1);

    b.addSample(List.of(loc1), List.of(1_000_000L, 3L), List.of());

    Path file = b.write(tempDir);
    PprofProfile.Profile profile = PprofParser.parse(file);

    assertEquals(2, profile.sampleTypes().size());
    assertEquals("cpu", profile.sampleTypes().get(0).type());
    assertEquals("alloc_objects", profile.sampleTypes().get(1).type());

    PprofProfile.Sample sample = profile.samples().get(0);
    assertArrayEquals(new long[] {1_000_000L, 3L}, sample.values());
  }

  @Test
  void multipleSamplesAndLocations() throws Exception {
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    int cpu = b.addString("cpu");
    int ns = b.addString("nanoseconds");
    b.addSampleType(cpu, ns);

    long fn1 = b.addFunction(b.addString("a.A.foo"), b.addString("A.java"));
    long fn2 = b.addFunction(b.addString("b.B.bar"), b.addString("B.java"));
    long loc1 = b.addLocation(fn1, 10);
    long loc2 = b.addLocation(fn2, 20);
    long loc3 = b.addLocation(fn1, 11);

    b.addSample(List.of(loc1, loc2), List.of(100L), List.of());
    b.addSample(List.of(loc3, loc2), List.of(200L), List.of());

    Path file = b.write(tempDir);
    PprofProfile.Profile profile = PprofParser.parse(file);

    assertEquals(2, profile.samples().size());
    assertEquals(2, profile.functions().size());
    assertEquals(3, profile.locations().size());

    // First sample references loc1 and loc2
    assertArrayEquals(new long[] {loc1, loc2}, profile.samples().get(0).locationIds());
  }

  @Test
  void emptyStringAtIndexZero() throws Exception {
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    int cpu = b.addString("cpu");
    int ns = b.addString("nanoseconds");
    b.addSampleType(cpu, ns);

    Path file = b.write(tempDir);
    PprofProfile.Profile profile = PprofParser.parse(file);

    // The string table always starts with "" at index 0
    assertFalse(profile.stringTable().isEmpty());
    assertEquals("", profile.stringTable().get(0));
  }

  @Test
  void numericLabel() throws Exception {
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    int cpu = b.addString("cpu");
    int ns = b.addString("nanoseconds");
    b.addSampleType(cpu, ns);

    int goroutineKey = b.addString("goroutine");
    long fn1 = b.addFunction(b.addString("main.run"), b.addString("main.go"));
    long loc1 = b.addLocation(fn1, 1);

    // numeric label: goroutine=42
    b.addSample(List.of(loc1), List.of(500L), List.of(new long[] {goroutineKey, 0, 42L, 0}));

    Path file = b.write(tempDir);
    PprofProfile.Profile profile = PprofParser.parse(file);

    PprofProfile.Sample sample = profile.samples().get(0);
    assertEquals(1, sample.labels().size());
    PprofProfile.Label label = sample.labels().get(0);
    assertEquals("goroutine", label.key());
    assertEquals(42L, label.num());
    assertNull(label.str()); // str should be empty/null for numeric label
  }

  /**
   * Concatenated gzip members must decompress to the concatenation of both payloads, which protobuf
   * merge semantics then parse as one profile (fields appended, scalars of the last member win).
   * The ISIZE capacity hint covers only the last member, so this also exercises the growth fallback
   * of the decompression buffer.
   */
  @Test
  void concatenatedGzipMembers() throws Exception {
    MinimalPprofBuilder b1 = new MinimalPprofBuilder();
    int cpu = b1.addString("cpu");
    int ns = b1.addString("nanoseconds");
    b1.addSampleType(cpu, ns);
    long fn = b1.addFunction(b1.addString("main.run"), b1.addString("main.go"));
    long loc = b1.addLocation(fn, 1);
    b1.addSample(List.of(loc), List.of(100L), List.of());
    b1.setDurationNanos(1000L);

    MinimalPprofBuilder b2 = new MinimalPprofBuilder();
    b2.addSampleType(cpu, ns);
    b2.addSample(List.of(loc), List.of(200L), List.of());
    b2.setDurationNanos(2000L);

    Path f1 = b1.write(tempDir);
    Path f2 = b2.write(tempDir);
    Path merged = tempDir.resolve("merged.pb.gz");
    Files.write(merged, concat(f1, f2));

    PprofProfile.Profile profile = PprofParser.parse(merged);
    assertEquals(2, profile.samples().size());
    assertArrayEquals(new long[] {200L}, profile.samples().get(1).values());
    assertEquals(2000L, profile.durationNanos()); // last member's scalar wins
  }

  /** A truncated protobuf stream must be rejected, not silently parsed. */
  @Test
  void truncatedSubmessageIsRejected() throws Exception {
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    int cpu = b.addString("cpu");
    int ns = b.addString("nanoseconds");
    b.addSampleType(cpu, ns);
    long fn = b.addFunction(b.addString("main.run"), b.addString("main.go"));
    long loc = b.addLocation(fn, 1);
    b.addSample(List.of(loc), List.of(500L), List.of());

    Path file = b.write(tempDir);
    byte[] raw = gunzip(file);
    // The builder ends with the durationNanos varint (6 bytes: tag + 5 payload bytes);
    // cutting 5 leaves a bare tag whose varint extends past the end.
    byte[] truncated = Arrays.copyOf(raw, raw.length - 5);
    Path truncatedFile = tempDir.resolve("truncated.pb.gz");
    try (OutputStream out = Files.newOutputStream(truncatedFile);
        GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(truncated);
    }

    assertThrows(IOException.class, () -> PprofParser.parse(truncatedFile));
  }

  /**
   * Fields that come AFTER a label submessage inside the same sample message must still be parsed.
   * The minimal builder always emits labels last, which would mask a cursor that skips too far
   * after a submessage; this test hand-encodes location id, value and isFolded fields after their
   * nested submessages.
   */
  @Test
  void fieldsAfterSubmessageAreParsed() throws Exception {
    int key = 1; // string table index 1
    int valueStr = 2;
    int typeName = 3;
    int unitName = 4;
    int fnName = 5;
    int fileName = 6;

    ByteArrayOutputStream proto = new ByteArrayOutputStream();
    // sampleType: { type: 3, unit: 4 }
    ByteArrayOutputStream vt = new ByteArrayOutputStream();
    writeRawVarint(vt, 8 | 0); // field 1, wire 0
    writeRawVarint(vt, typeName);
    writeRawVarint(vt, 16 | 0); // field 2, wire 0
    writeRawVarint(vt, unitName);
    writeLenField(proto, 1, vt.toByteArray());

    // sample: locationId=7, label{key=1, str=2}, locationId=8, value=99
    ByteArrayOutputStream sample = new ByteArrayOutputStream();
    writeVarintField(sample, 1, 7);
    ByteArrayOutputStream label = new ByteArrayOutputStream();
    writeVarintField(label, 1, key);
    writeVarintField(label, 2, valueStr);
    writeLenField(sample, 3, label.toByteArray());
    writeVarintField(sample, 1, 8); // after the label
    writeVarintField(sample, 2, 99); // after the label
    writeLenField(proto, 2, sample.toByteArray());

    // location: line{functionId=1, line=10}, address=0x1234 (after the line), isFolded=1 (after)
    ByteArrayOutputStream loc = new ByteArrayOutputStream();
    writeVarintField(loc, 1, 42); // id
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    writeVarintField(line, 1, 1); // functionId
    writeVarintField(line, 2, 10); // line number
    writeLenField(loc, 4, line.toByteArray());
    writeVarintField(loc, 3, 0x1234); // address, after the line submessage
    writeVarintField(loc, 5, 1); // isFolded, after the line submessage
    writeLenField(proto, 4, loc.toByteArray());

    // function: id=1, name=5, filename=6
    ByteArrayOutputStream fn = new ByteArrayOutputStream();
    writeVarintField(fn, 1, 1);
    writeVarintField(fn, 2, fnName);
    writeVarintField(fn, 4, fileName);
    writeLenField(proto, 5, fn.toByteArray());

    // string table
    for (String s : new String[] {"", "thread", "main", "cpu", "count", "main.run", "main.go"}) {
      writeLenField(proto, 6, s.getBytes(StandardCharsets.UTF_8));
    }

    Path file = tempDir.resolve("after-submessage.pb.gz");
    try (OutputStream out = Files.newOutputStream(file);
        GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(proto.toByteArray());
    }

    PprofProfile.Profile profile = PprofParser.parse(file);
    PprofProfile.Sample s = profile.samples().get(0);
    assertArrayEquals(new long[] {7, 8}, s.locationIds());
    assertArrayEquals(new long[] {99L}, s.values());
    assertEquals(1, s.labels().size());
    assertEquals("thread", s.labels().get(0).key());
    assertEquals("main", s.labels().get(0).str());

    PprofProfile.Location l = profile.locations().get(0);
    assertEquals(42L, l.id());
    assertEquals(0x1234L, l.address()); // parsed, not skipped
    assertTrue(l.isFolded()); // parsed, not skipped
    assertEquals(1, l.lines().size());
    assertEquals(1L, l.lines().get(0).functionId());
    assertEquals(10L, l.lines().get(0).lineNumber());
  }

  /**
   * A repeated singular field (period_type) must follow protobuf last-wins semantics: the last
   * occurrence replaces the first, not accumulates beside it.
   */
  @Test
  void duplicatePeriodTypeLastWins() throws Exception {
    int cpuIdx = 1;
    int nsIdx = 2;
    int spaceIdx = 3;
    int bytesIdx = 4;

    ByteArrayOutputStream proto = new ByteArrayOutputStream();
    writeLenField(proto, 11, valueType(cpuIdx, nsIdx)); // first period_type: cpu/nanoseconds
    writeLenField(proto, 11, valueType(spaceIdx, bytesIdx)); // second: space/bytes — must win
    for (String s : new String[] {"", "cpu", "nanoseconds", "space", "bytes"}) {
      writeLenField(proto, 6, s.getBytes(StandardCharsets.UTF_8));
    }

    Path file = tempDir.resolve("dup-period-type.pb.gz");
    try (OutputStream out = Files.newOutputStream(file);
        GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(proto.toByteArray());
    }

    PprofProfile.Profile profile = PprofParser.parse(file);
    assertEquals("space", profile.periodType().type());
    assertEquals("bytes", profile.periodType().unit());
  }

  /**
   * A 64-bit string-table index that truncates to an in-range int must resolve to the empty string
   * (out of range), never to the string at the truncated index.
   */
  @Test
  void outOfRangeStringIndexResolvesEmpty() throws Exception {
    long forgedIndex = 0x1_0000_0002L; // truncates to 2 — must NOT resolve to string table[2]

    ByteArrayOutputStream proto = new ByteArrayOutputStream();
    ByteArrayOutputStream fn = new ByteArrayOutputStream();
    writeVarintField(fn, 1, 1); // id
    writeVarintField(fn, 2, forgedIndex); // name: forged 64-bit index
    writeVarintField(fn, 4, forgedIndex); // filename: same
    writeLenField(proto, 5, fn.toByteArray());
    for (String s : new String[] {"", "one", "DECOY-ENTRY-TWO"}) {
      writeLenField(proto, 6, s.getBytes(StandardCharsets.UTF_8));
    }

    Path file = tempDir.resolve("forged-index.pb.gz");
    try (OutputStream out = Files.newOutputStream(file);
        GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(proto.toByteArray());
    }

    PprofProfile.Profile profile = PprofParser.parse(file);
    assertEquals("", profile.functions().get(0).name());
    assertEquals("", profile.functions().get(0).filename());
  }

  private static byte[] valueType(int typeIdx, int unitIdx) throws IOException {
    ByteArrayOutputStream m = new ByteArrayOutputStream(8);
    writeVarintField(m, 1, typeIdx);
    writeVarintField(m, 2, unitIdx);
    return m.toByteArray();
  }

  private static byte[] concat(Path a, Path b) throws IOException {
    byte[] x = Files.readAllBytes(a);
    byte[] y = Files.readAllBytes(b);
    byte[] out = new byte[x.length + y.length];
    System.arraycopy(x, 0, out, 0, x.length);
    System.arraycopy(y, 0, out, x.length, y.length);
    return out;
  }

  private static void writeVarintField(OutputStream out, int fieldNumber, long value)
      throws IOException {
    writeRawVarint(out, ((long) fieldNumber << 3) | 0);
    writeRawVarint(out, value);
  }

  private static void writeLenField(OutputStream out, int fieldNumber, byte[] data)
      throws IOException {
    writeRawVarint(out, ((long) fieldNumber << 3) | 2);
    writeRawVarint(out, data.length);
    out.write(data);
  }

  private static void writeRawVarint(OutputStream out, long value) throws IOException {
    while ((value & ~0x7FL) != 0) {
      out.write((int) ((value & 0x7F) | 0x80));
      value >>>= 7;
    }
    out.write((int) value);
  }

  private static byte[] gunzip(Path file) throws IOException {
    try (InputStream is = Files.newInputStream(file);
        GZIPInputStream gz = new GZIPInputStream(is)) {
      return gz.readAllBytes();
    }
  }
}

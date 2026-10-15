package io.jafar.pprof.benchmark;

import io.jafar.pprof.api.PprofParser;
import io.jafar.pprof.api.PprofProfile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures full-file parse cost of the pprof parser: gzip decompression + protobuf wire decoding +
 * string resolution.
 *
 * <p>By default a deterministic synthetic profile is generated in setup (30k samples, 8k locations,
 * 4k functions, stack depth 2-29, string and numeric labels). To benchmark a real profile instead,
 * set the environment variable {@code PPROF_FILE} (it survives the gradle → jmh → forked-JVM chain;
 * a {@code -D} system property does not) — the synthetic file is then not used.
 *
 * <p>{@link #gzipOnly} is a control: JDK {@code readAllBytes()} decompression with a single file
 * open, no protobuf decoding. {@code parseFile - gzipOnly} is the parser's own cost plus the
 * benchmark path's gzip-trailer read and ISIZE-buffer strategy — see the method javadoc.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(
    value = 1,
    jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
public class PprofParserBenchmark {

  private static final int FUNCTIONS = 4_000;
  private static final int LOCATIONS = 8_000;
  private static final int SAMPLES = 30_000;

  private Path file;
  private boolean ownsFile;

  @Setup(Level.Trial)
  public void setUp() throws Exception {
    String real = System.getProperty("pprof.file");
    if (real == null) {
      real = System.getenv("PPROF_FILE"); // env survives the gradle->jmh->fork chain
    }
    if (real != null) {
      file = Path.of(real);
      ownsFile = false;
    } else {
      file = Files.createTempFile("pprof-bench", ".pb.gz");
      ownsFile = true;
      try (OutputStream out = Files.newOutputStream(file);
          GZIPOutputStream gz = new GZIPOutputStream(out)) {
        gz.write(encodeProfile());
      }
      // self-check: the generator must round-trip through the real parser
      PprofProfile.Profile check = PprofParser.parse(file);
      if (check.samples().size() != SAMPLES
          || check.locations().size() != LOCATIONS
          || check.functions().size() != FUNCTIONS) {
        throw new IllegalStateException(
            "synthetic profile check failed: samples="
                + check.samples().size()
                + " locations="
                + check.locations().size()
                + " functions="
                + check.functions().size());
      }
      if (check.samples().stream().anyMatch(s -> s.locationIds().length < 2)) {
        throw new IllegalStateException("synthetic stacks must have depth >= 2");
      }
      // Resolution checks: counts alone would pass a parser that loses every string
      // and value (the benchmark would then compare a broken parser against nothing).
      if (!"cpu".equals(check.sampleTypes().get(0).type())) {
        throw new IllegalStateException(
            "sample type 0 must resolve to 'cpu', got: " + check.sampleTypes().get(0).type());
      }
      boolean labelsResolve =
          check.samples().stream()
              .flatMap(s -> s.labels().stream())
              .anyMatch(
                  l -> "thread_name".equals(l.key()) && l.str() != null && !l.str().isEmpty());
      if (!labelsResolve) {
        throw new IllegalStateException("no string label resolved; label staging is broken");
      }
      boolean valuesResolve = check.samples().stream().anyMatch(s -> s.values()[0] != 0);
      if (!valuesResolve) {
        throw new IllegalStateException("all sample values are zero; value staging is broken");
      }
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() throws Exception {
    if (ownsFile) {
      Files.deleteIfExists(file);
    }
  }

  @Benchmark
  public PprofProfile.Profile parseFile() throws IOException {
    return PprofParser.parse(file);
  }

  /**
   * Control: the JDK {@code InputStream.readAllBytes()} decompression (single file open), no
   * protobuf decoding. It approximates — not isolates — the I/O + inflation share of {@link
   * #parseFile()}: the benchmark path additionally reads the gzip trailer and reopens the file, and
   * decompresses into an ISIZE-sized buffer, so {@code parseFile - gzipOnly} includes those costs
   * and the buffer-strategy difference, not only protobuf decoding.
   */
  @Benchmark
  public byte[] gzipOnly() throws IOException {
    try (var is = Files.newInputStream(file);
        var gzis = new GZIPInputStream(is, 65536)) {
      return gzis.readAllBytes();
    }
  }

  // ---- synthetic profile generation (protobuf wire format, mirrors profile.proto) ----

  private byte[] encodeProfile() throws IOException {
    Random rnd = new Random(42);
    ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);

    // string table (index 0 must be the empty string, per profile.proto)
    addString(out, "");
    int cpuIdx = addString(out, "cpu");
    int nanosIdx = addString(out, "nanoseconds");
    int samplesIdx = addString(out, "samples");
    int countIdx = addString(out, "count");
    int pidIdx = addString(out, "pid");
    int threadNameIdx = addString(out, "thread_name");
    int procIdx = addString(out, "process_id");
    int[] threadStrIdx = new int[64];
    for (int i = 0; i < threadStrIdx.length; i++) {
      threadStrIdx[i] = addString(out, "thread-" + i);
    }
    int[] fnNameIdx = new int[FUNCTIONS];
    int[] fnFileIdx = new int[FUNCTIONS];
    for (int i = 0; i < FUNCTIONS; i++) {
      fnNameIdx[i] =
          addString(
              out,
              "com.example.pkg"
                  + (i % 37)
                  + ".Class"
                  + i
                  + ".method"
                  + (i % 11)
                  + "(java.lang.String,int)");
      fnFileIdx[i] =
          addString(out, "/src/main/java/com/example/pkg" + (i % 37) + "/Class" + i + ".java");
    }

    // sampleType (field 1)
    writeLen(out, 1, encodeValueType(cpuIdx, nanosIdx));
    writeLen(out, 1, encodeValueType(samplesIdx, countIdx));

    // sample (field 2)
    for (int s = 0; s < SAMPLES; s++) {
      ByteArrayOutputStream m = new ByteArrayOutputStream(128);
      int depth = 2 + rnd.nextInt(28);
      for (int d = 0; d < depth; d++) {
        writeVarintField(m, 1, 1 + rnd.nextInt(LOCATIONS));
      }
      writeVarintField(m, 2, 1_000_000L + rnd.nextInt(100_000_000)); // cpu nanoseconds
      writeVarintField(m, 2, 1 + rnd.nextInt(50)); // count
      if (rnd.nextInt(5) < 4) { // 80%: thread_name string label
        writeLen(
            m, 3, encodeLabel(threadNameIdx, threadStrIdx[rnd.nextInt(threadStrIdx.length)], 0, 0));
      }
      if (rnd.nextInt(10) < 5) { // 50%: process_id numeric label
        writeLen(m, 3, encodeLabel(procIdx, 0, 1000 + rnd.nextInt(5000), pidIdx));
      }
      writeLen(out, 2, m.toByteArray());
    }

    // mapping (field 3)
    writeLen(
        out,
        3,
        encodeMapping(1, 0x100000000L, 0x200000000L, 0, addString(out, "/bin/synthetic-app"), 1));

    // location (field 4)
    for (int i = 0; i < LOCATIONS; i++) {
      ByteArrayOutputStream m = new ByteArrayOutputStream(48);
      writeVarintField(m, 1, i + 1); // id
      writeVarintField(m, 2, 1); // mappingId
      writeVarintField(m, 3, 0x100000000L + i * 64L); // address
      int fn = rnd.nextInt(FUNCTIONS);
      ByteArrayOutputStream line = new ByteArrayOutputStream(24);
      writeVarintField(line, 1, fn + 1); // functionId
      writeVarintField(line, 2, 10 + rnd.nextInt(2000)); // line number
      writeLen(m, 4, line.toByteArray());
      if (rnd.nextInt(10) == 0) { // 10%: inlined caller frame
        ByteArrayOutputStream inlined = new ByteArrayOutputStream(24);
        writeVarintField(inlined, 1, 1 + rnd.nextInt(FUNCTIONS));
        writeVarintField(inlined, 2, 10 + rnd.nextInt(2000));
        writeLen(m, 4, inlined.toByteArray());
      }
      writeLen(out, 4, m.toByteArray());
    }

    // function (field 5)
    for (int i = 0; i < FUNCTIONS; i++) {
      ByteArrayOutputStream m = new ByteArrayOutputStream(48);
      writeVarintField(m, 1, i + 1); // id
      writeVarintField(m, 2, fnNameIdx[i]); // name
      writeVarintField(m, 3, fnNameIdx[i]); // systemName
      writeVarintField(m, 4, fnFileIdx[i]); // filename
      writeLen(out, 5, m.toByteArray());
    }

    // stringTable (field 6) entries were appended above in index order; nothing more to add
    // periodType (field 11), period (field 12), durationNanos (field 10), timeNanos (field 9)
    writeLen(out, 11, encodeValueType(cpuIdx, nanosIdx));
    writeVarintField(out, 12, 10_000_000); // 10ms
    writeVarintField(out, 10, 60_000_000_000L);
    writeVarintField(out, 9, 1_700_000_000_000_000_000L);

    return out.toByteArray();
  }

  // ---- wire helpers ----

  /** Appends a string-table entry (profile field 6) and returns its 0-based index. */
  private int addString(ByteArrayOutputStream out, String s) throws IOException {
    writeLen(out, 6, s.getBytes(StandardCharsets.UTF_8));
    return stringIndex++;
  }

  private int stringIndex = 0;

  private static byte[] encodeValueType(int typeIdx, int unitIdx) throws IOException {
    ByteArrayOutputStream m = new ByteArrayOutputStream(8);
    writeVarintField(m, 1, typeIdx);
    writeVarintField(m, 2, unitIdx);
    return m.toByteArray();
  }

  private static byte[] encodeLabel(int keyIdx, int strIdx, long num, int numUnitIdx)
      throws IOException {
    ByteArrayOutputStream m = new ByteArrayOutputStream(16);
    if (keyIdx != 0) writeVarintField(m, 1, keyIdx);
    if (strIdx != 0) writeVarintField(m, 2, strIdx);
    if (num != 0) writeVarintField(m, 3, num);
    if (numUnitIdx != 0) writeVarintField(m, 4, numUnitIdx);
    return m.toByteArray();
  }

  private static byte[] encodeMapping(
      long id, long memStart, long memLimit, long fileOffset, int filenameIdx, int hasFunctions)
      throws IOException {
    ByteArrayOutputStream m = new ByteArrayOutputStream(48);
    writeVarintField(m, 1, id);
    writeVarintField(m, 2, memStart);
    writeVarintField(m, 3, memLimit);
    writeVarintField(m, 4, fileOffset);
    writeVarintField(m, 5, filenameIdx);
    writeVarintField(m, 7, hasFunctions);
    return m.toByteArray();
  }

  private static void writeVarintField(OutputStream out, int fieldNumber, long value)
      throws IOException {
    writeRawVarint(out, ((long) fieldNumber << 3) | 0); // wire type 0
    writeRawVarint(out, value);
  }

  private static void writeLen(OutputStream out, int fieldNumber, byte[] data) throws IOException {
    writeRawVarint(out, ((long) fieldNumber << 3) | 2); // wire type 2
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
}

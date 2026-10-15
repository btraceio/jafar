package io.jafar.pprof.internal;

import io.jafar.pprof.api.PprofProfile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Hand-rolled gzip + protobuf wire format decoder for pprof profiles.
 *
 * <p>Supports VARINT (wire type 0) and LEN (wire type 2) fields, which cover all fields in
 * google/pprof/proto/profile.proto. Field numbers are hardcoded; no proto descriptor is needed.
 *
 * <p>Both packed and unpacked repeated fields are handled correctly.
 *
 * <p>Allocation discipline: the decoder is single-phase. Stack locations, sample values, labels and
 * location lines are decoded into a handful of scratch arrays reused across all messages of one
 * profile, and copied out to exact-size arrays only once, when the owning record is built. String
 * references cannot be resolved until the string table has been seen, so string-bearing data (value
 * types, functions, mappings, sample labels) is staged in flat {@code long[]} stores and resolved
 * in one pass at the end. The only per-sample allocations are the final {@link PprofProfile.Sample}
 * components themselves.
 */
public final class PprofReader {

  // Profile field numbers (profile.proto)
  private static final int PROFILE_SAMPLE_TYPE = 1;
  private static final int PROFILE_SAMPLE = 2;
  private static final int PROFILE_MAPPING = 3;
  private static final int PROFILE_LOCATION = 4;
  private static final int PROFILE_FUNCTION = 5;
  private static final int PROFILE_STRING_TABLE = 6;
  private static final int PROFILE_TIME_NANOS = 9;
  private static final int PROFILE_DURATION_NANOS = 10;
  private static final int PROFILE_PERIOD_TYPE = 11;
  private static final int PROFILE_PERIOD = 12;

  // ValueType field numbers
  private static final int VALUE_TYPE_TYPE = 1;
  private static final int VALUE_TYPE_UNIT = 2;

  // Sample field numbers
  private static final int SAMPLE_LOCATION_ID = 1;
  private static final int SAMPLE_VALUE = 2;
  private static final int SAMPLE_LABEL = 3;

  // Label field numbers
  private static final int LABEL_KEY = 1;
  private static final int LABEL_STR = 2;
  private static final int LABEL_NUM = 3;
  private static final int LABEL_NUM_UNIT = 4;

  // Location field numbers
  private static final int LOCATION_ID = 1;
  private static final int LOCATION_MAPPING_ID = 2;
  private static final int LOCATION_ADDRESS = 3;
  private static final int LOCATION_LINE = 4;
  private static final int LOCATION_IS_FOLDED = 5;

  // Line field numbers
  private static final int LINE_FUNCTION_ID = 1;
  private static final int LINE_LINE = 2;

  // Function field numbers
  private static final int FUNCTION_ID = 1;
  private static final int FUNCTION_NAME = 2;
  private static final int FUNCTION_SYSTEM_NAME = 3;
  private static final int FUNCTION_FILENAME = 4;
  private static final int FUNCTION_START_LINE = 5;

  // Mapping field numbers
  private static final int MAPPING_ID = 1;
  private static final int MAPPING_MEMORY_START = 2;
  private static final int MAPPING_MEMORY_LIMIT = 3;
  private static final int MAPPING_FILE_OFFSET = 4;
  private static final int MAPPING_FILENAME = 5;
  private static final int MAPPING_HAS_FUNCTIONS = 7;

  // Wire types
  private static final int WIRE_VARINT = 0;
  private static final int WIRE_I64 = 1;
  private static final int WIRE_LEN = 2;
  private static final int WIRE_I32 = 5;

  private PprofReader() {}

  /**
   * Reads a gzip-compressed pprof profile from the given path.
   *
   * @param path path to the .pb.gz or .pprof file
   * @return parsed profile
   * @throws IOException if reading or parsing fails
   */
  public static PprofProfile.Profile read(Path path) throws IOException {
    Buffer raw = gunzip(path);
    return new Parser(raw.data, 0, raw.length).parseProfile();
  }

  // ---- gzip decompression ----

  /** Decompressed bytes; {@code length} may be shorter than {@code data.length}. */
  private record Buffer(byte[] data, int length) {}

  /**
   * Decompresses the file into a single buffer, sized from the gzip trailer when possible.
   *
   * <p>The trailer ISIZE is only a capacity hint: it is the size of the last member modulo 2^32, so
   * concatenated members and streams larger than 4 GB make it too small. Correctness never depends
   * on it — the read loop grows the buffer whenever it fills — it only avoids the repeated doubling
   * (and the transient copies that come with it) for the common case.
   *
   * <p>Because ISIZE is attacker-controlled, the hint is additionally capped by a compression-ratio
   * bound on the compressed file: a few-byte file with a forged trailer cannot force a huge
   * allocation, and genuinely larger profiles simply take the growth path.
   */
  private static Buffer gunzip(Path path) throws IOException {
    int hint = uncompressedSizeHint(path);
    byte[] buf = hint > 0 ? new byte[hint] : new byte[1 << 16];
    int len = 0;
    try (InputStream is = Files.newInputStream(path);
        GZIPInputStream gzis = new GZIPInputStream(is, 65536)) {
      while (true) {
        if (len == buf.length) {
          // Doubling overflows int at 2^30 (<< 1 turns negative); refuse rather than throw
          // NegativeArraySizeException, so every failure surfaces as the declared IOException.
          if (buf.length >= 1 << 30) {
            throw new IOException("Decompressed profile exceeds 1 GiB");
          }
          buf = Arrays.copyOf(buf, buf.length << 1);
        }
        int r = gzis.read(buf, len, buf.length - len);
        if (r < 0) {
          break;
        }
        len += r;
      }
    }
    // The slack beyond len is never parsed; trimming it would cost one full copy.
    return new Buffer(buf, len);
  }

  /**
   * Reads the gzip ISIZE trailer, or -1 when it cannot be trusted.
   *
   * <p>The returned hint is capped: never above 1 GiB, and never above a 64:1 compression ratio of
   * the compressed file size (ISIZE is untrusted input; see {@link #gunzip}).
   */
  private static int uncompressedSizeHint(Path path) throws IOException {
    try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
      long size = ch.size();
      if (size < 18) {
        return -1; // smaller than a minimal gzip member
      }
      ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
      if (ch.read(b, size - 4) != 4) {
        return -1;
      }
      long isize = b.getInt(0) & 0xFFFFFFFFL;
      if (isize == 0) {
        return 0; // exactly 4 GB, or wrapped: unknown, use default chunk
      }
      long cap = Math.min(1L << 30, Math.max(1L << 16, 64 * size));
      return (int) Math.min(isize, cap);
    }
  }

  // ---- growable long scratch / staging store ----

  /** Growable primitive long array with reset; the backing array is never shared. */
  private static final class LongVec {
    long[] a;
    int n;

    LongVec(int cap) {
      a = new long[Math.max(1, cap)];
    }

    void add(long v) {
      if (n == a.length) {
        a = Arrays.copyOf(a, a.length << 1);
      }
      a[n++] = v;
    }

    void clear() {
      n = 0;
    }

    /** Always a fresh array: results become record components (e.g. Sample.locationIds). */
    long[] exact() {
      return Arrays.copyOf(a, n);
    }
  }

  // ---- protobuf parser ----

  /**
   * Cursor-based wire decoder. Every read advances {@link #pos} and is bounds-checked against the
   * enclosing message end, so each varint is decoded exactly once and a submessage can never read
   * past its own LEN boundary.
   */
  private static final class Parser {
    private final byte[] buf;
    private final int end;
    private int pos;

    // Scratch buffers, reused across all messages of one profile.
    private final LongVec ids = new LongVec(32); // sample stack location ids
    private final LongVec vals = new LongVec(4); // sample values
    private final LongVec labels = new LongVec(4); // flat [key, str, num, numUnit] quadruples
    private final LongVec lines = new LongVec(2); // flat [functionId, lineNumber] pairs

    // Sample staging: exact-size arrays, one entry per sample. Label strings are resolved after
    // the whole profile message has been consumed, so label payloads wait in flat form.
    private final List<long[]> sampleLocIds = new ArrayList<>();
    private final List<long[]> sampleValues = new ArrayList<>();
    private final List<long[]> sampleLabels = new ArrayList<>();

    // String-bearing staging, flat strides: value type 2, function 5, mapping 6.
    private final LongVec valueTypes = new LongVec(4);
    private final LongVec functions = new LongVec(64);
    private final LongVec mappings = new LongVec(4);
    private final LongVec periodType = new LongVec(2);
    private boolean hasPeriodType;

    private final List<PprofProfile.Location> locations = new ArrayList<>();
    private final List<String> stringTable = new ArrayList<>();

    private long timeNanos;
    private long durationNanos;
    private long period;

    Parser(byte[] buf, int start, int end) {
      this.buf = buf;
      this.end = end;
      this.pos = start;
    }

    // ---- varint decoding (single pass: value + position advance) ----

    private long readVarint(int end) throws IOException {
      long result = 0;
      int shift = 0;
      int p = pos;
      while (true) {
        if (p >= end) {
          throw new IOException("Truncated protobuf: varint extends past message end");
        }
        byte b = buf[p++];
        result |= (long) (b & 0x7F) << shift;
        if ((b & 0x80) == 0) {
          break;
        }
        shift += 7;
        if (shift > 63) {
          throw new IOException("Malformed protobuf: varint exceeds 64 bits");
        }
      }
      pos = p;
      return result;
    }

    private int readLen(int end) throws IOException {
      long raw = readVarint(end);
      // Overflow-safe bound: raw bytes must fit in the enclosing message (end >= pos).
      if (raw > end - pos) {
        throw new IOException("Truncated protobuf: field length " + raw + " past message end");
      }
      if (raw < 0 || raw > Integer.MAX_VALUE - 8) {
        throw new IOException("Protobuf field length out of range: " + raw);
      }
      return (int) raw;
    }

    /** Skips a field of the given wire type, bounds-checked against {@code end}. */
    private void skipField(int wireType, int end) throws IOException {
      switch (wireType) {
        case WIRE_VARINT -> readVarint(end);
        case WIRE_I64 -> {
          if (pos + 8 > end) {
            throw new IOException("Truncated protobuf: fixed64 extends past message end");
          }
          pos += 8;
        }
        case WIRE_LEN -> pos += readLen(end);
        case WIRE_I32 -> {
          if (pos + 4 > end) {
            throw new IOException("Truncated protobuf: fixed32 extends past message end");
          }
          pos += 4;
        }
        default -> throw new IOException("Cannot skip unknown wire type " + wireType);
      }
    }

    // ---- profile message ----

    PprofProfile.Profile parseProfile() throws IOException {
      while (pos < end) {
        long tag = readVarint(end);
        int fieldNumber = (int) (tag >>> 3);
        int wireType = (int) (tag & 7);
        switch (wireType) {
          case WIRE_LEN -> {
            int len = readLen(end);
            int msgEnd = pos + len;
            switch (fieldNumber) {
              case PROFILE_SAMPLE_TYPE -> parseValueType(valueTypes, msgEnd);
              case PROFILE_SAMPLE -> parseSample(msgEnd);
              case PROFILE_MAPPING -> parseMapping(msgEnd);
              case PROFILE_LOCATION -> parseLocation(msgEnd);
              case PROFILE_FUNCTION -> parseFunction(msgEnd);
              case PROFILE_STRING_TABLE ->
                  stringTable.add(new String(buf, pos, len, StandardCharsets.UTF_8));
              case PROFILE_PERIOD_TYPE -> {
                // Protobuf semantics: last occurrence of a singular field wins — clear
                // the staging so a repeated period_type overwrites, not accumulates.
                periodType.clear();
                parseValueType(periodType, msgEnd);
                hasPeriodType = true;
              }
              default -> {
                /* skip unknown */
              }
            }
            pos = msgEnd;
          }
          case WIRE_VARINT -> {
            long value = readVarint(end);
            switch (fieldNumber) {
              case PROFILE_TIME_NANOS -> timeNanos = value;
              case PROFILE_DURATION_NANOS -> durationNanos = value;
              case PROFILE_PERIOD -> period = value;
              default -> {
                /* skip */
              }
            }
          }
          case WIRE_I64 -> {
            if (pos + 8 > end) {
              throw new IOException("Truncated protobuf: fixed64 extends past buffer end");
            }
            pos += 8;
          }
          case WIRE_I32 -> {
            if (pos + 4 > end) {
              throw new IOException("Truncated protobuf: fixed32 extends past buffer end");
            }
            pos += 4;
          }
          default ->
              throw new IOException("Unknown wire type " + wireType + " at offset " + (pos - 1));
        }
      }
      return buildProfile();
    }

    private PprofProfile.Profile buildProfile() {
      List<PprofProfile.ValueType> sampleTypes = new ArrayList<>(valueTypes.n / 2);
      for (int i = 0; i < valueTypes.n; i += 2) {
        sampleTypes.add(
            new PprofProfile.ValueType(resolve(valueTypes.a[i]), resolve(valueTypes.a[i + 1])));
      }
      PprofProfile.ValueType periodTypeResolved =
          hasPeriodType
              ? new PprofProfile.ValueType(resolve(periodType.a[0]), resolve(periodType.a[1]))
              : null;

      List<PprofProfile.Function> functionsOut = new ArrayList<>(functions.n / 5);
      for (int i = 0; i < functions.n; i += 5) {
        functionsOut.add(
            new PprofProfile.Function(
                functions.a[i],
                resolve(functions.a[i + 1]),
                resolve(functions.a[i + 2]),
                resolve(functions.a[i + 3]),
                functions.a[i + 4]));
      }

      List<PprofProfile.Mapping> mappingsOut = new ArrayList<>(mappings.n / 6);
      for (int i = 0; i < mappings.n; i += 6) {
        mappingsOut.add(
            new PprofProfile.Mapping(
                mappings.a[i],
                mappings.a[i + 1],
                mappings.a[i + 2],
                mappings.a[i + 3],
                resolve(mappings.a[i + 4]),
                mappings.a[i + 5] != 0));
      }

      List<PprofProfile.Sample> samplesOut = new ArrayList<>(sampleLocIds.size());
      for (int i = 0; i < sampleLocIds.size(); i++) {
        samplesOut.add(
            new PprofProfile.Sample(
                sampleLocIds.get(i), sampleValues.get(i), resolveLabels(sampleLabels.get(i))));
      }

      return new PprofProfile.Profile(
          List.copyOf(sampleTypes),
          List.copyOf(samplesOut),
          List.copyOf(mappingsOut),
          List.copyOf(locations),
          List.copyOf(functionsOut),
          List.copyOf(stringTable),
          period,
          periodTypeResolved,
          durationNanos,
          timeNanos);
    }

    /** Resolves staged label quadruples {@code [key, str, num, numUnit]} into {@link List}s. */
    private List<PprofProfile.Label> resolveLabels(long[] ld) {
      if (ld.length == 0) {
        return List.of();
      }
      PprofProfile.Label[] out = new PprofProfile.Label[ld.length / 4];
      for (int j = 0, i = 0; j < out.length; j++, i += 4) {
        String str = ld[i + 1] != 0 ? resolve(ld[i + 1]) : null;
        String numUnit = ld[i + 3] != 0 ? resolve(ld[i + 3]) : null;
        out[j] = new PprofProfile.Label(resolve(ld[i]), str, ld[i + 2], numUnit);
      }
      return List.of(out);
    }

    // ---- sub-message parsers ----

    private void parseValueType(LongVec out, int end) throws IOException {
      long type = 0;
      long unit = 0;
      while (pos < end) {
        long tag = readVarint(end);
        int fieldNumber = (int) (tag >>> 3);
        int wireType = (int) (tag & 7);
        if (wireType == WIRE_VARINT) {
          long value = readVarint(end);
          if (fieldNumber == VALUE_TYPE_TYPE) type = value;
          else if (fieldNumber == VALUE_TYPE_UNIT) unit = value;
        } else {
          skipField(wireType, end);
        }
      }
      out.add(type);
      out.add(unit);
    }

    private void parseSample(int end) throws IOException {
      ids.clear();
      vals.clear();
      labels.clear();
      while (pos < end) {
        long tag = readVarint(end);
        int fieldNumber = (int) (tag >>> 3);
        int wireType = (int) (tag & 7);
        switch (fieldNumber) {
          case SAMPLE_LOCATION_ID -> {
            if (wireType == WIRE_VARINT) {
              ids.add(readVarint(end));
            } else if (wireType == WIRE_LEN) {
              readPacked(ids, end);
            } else {
              skipField(wireType, end);
            }
          }
          case SAMPLE_VALUE -> {
            if (wireType == WIRE_VARINT) {
              vals.add(readVarint(end));
            } else if (wireType == WIRE_LEN) {
              readPacked(vals, end);
            } else {
              skipField(wireType, end);
            }
          }
          case SAMPLE_LABEL -> {
            if (wireType == WIRE_LEN) {
              // len first, then msgEnd: in `pos + readLen(end)` Java captures pos before
              // readLen advances it (JLS 15.7.1), shorting msgEnd by the len varint.
              int len = readLen(end);
              int msgEnd = pos + len;
              parseLabel(msgEnd);
              pos = msgEnd;
            } else {
              skipField(wireType, end);
            }
          }
          default -> skipField(wireType, end);
        }
      }
      sampleLocIds.add(ids.exact());
      sampleValues.add(vals.exact());
      sampleLabels.add(labels.exact());
    }

    /** Reads a packed repeated varint field body into {@code out}. */
    private void readPacked(LongVec out, int end) throws IOException {
      int len = readLen(end);
      int packEnd = pos + len;
      while (pos < packEnd) {
        out.add(readVarint(packEnd));
      }
    }

    private void parseLabel(int end) throws IOException {
      long key = 0;
      long str = 0;
      long num = 0;
      long numUnit = 0;
      while (pos < end) {
        long tag = readVarint(end);
        int fieldNumber = (int) (tag >>> 3);
        int wireType = (int) (tag & 7);
        if (wireType == WIRE_VARINT) {
          long value = readVarint(end);
          switch (fieldNumber) {
            case LABEL_KEY -> key = value;
            case LABEL_STR -> str = value;
            case LABEL_NUM -> num = value;
            case LABEL_NUM_UNIT -> numUnit = value;
            default -> {}
          }
        } else {
          skipField(wireType, end);
        }
      }
      labels.add(key);
      labels.add(str);
      labels.add(num);
      labels.add(numUnit);
    }

    private void parseLocation(int end) throws IOException {
      long id = 0;
      long mappingId = 0;
      long address = 0;
      boolean isFolded = false;
      lines.clear();
      while (pos < end) {
        long tag = readVarint(end);
        int fieldNumber = (int) (tag >>> 3);
        int wireType = (int) (tag & 7);
        switch (fieldNumber) {
          case LOCATION_ID -> {
            if (wireType == WIRE_VARINT) {
              id = readVarint(end);
            } else {
              skipField(wireType, end);
            }
          }
          case LOCATION_MAPPING_ID -> {
            if (wireType == WIRE_VARINT) {
              mappingId = readVarint(end);
            } else {
              skipField(wireType, end);
            }
          }
          case LOCATION_ADDRESS -> {
            if (wireType == WIRE_VARINT) {
              address = readVarint(end);
            } else {
              skipField(wireType, end);
            }
          }
          case LOCATION_LINE -> {
            if (wireType == WIRE_LEN) {
              // len first, then msgEnd — see SAMPLE_LABEL for the operand-order trap.
              int len = readLen(end);
              int msgEnd = pos + len;
              parseLine(msgEnd);
              pos = msgEnd;
            } else {
              skipField(wireType, end);
            }
          }
          case LOCATION_IS_FOLDED -> {
            if (wireType == WIRE_VARINT) {
              isFolded = readVarint(end) != 0;
            } else {
              skipField(wireType, end);
            }
          }
          default -> skipField(wireType, end);
        }
      }
      int lineCount = lines.n / 2;
      List<PprofProfile.Line> lineList;
      if (lineCount == 0) {
        lineList = List.of();
      } else {
        PprofProfile.Line[] lineArr = new PprofProfile.Line[lineCount];
        for (int i = 0, j = 0; i < lineCount; i++, j += 2) {
          lineArr[i] = new PprofProfile.Line(lines.a[j], lines.a[j + 1]);
        }
        lineList = List.of(lineArr);
      }
      locations.add(new PprofProfile.Location(id, mappingId, address, lineList, isFolded));
    }

    private void parseLine(int end) throws IOException {
      long functionId = 0;
      long lineNumber = 0;
      while (pos < end) {
        long tag = readVarint(end);
        int fieldNumber = (int) (tag >>> 3);
        int wireType = (int) (tag & 7);
        if (wireType == WIRE_VARINT) {
          long value = readVarint(end);
          if (fieldNumber == LINE_FUNCTION_ID) functionId = value;
          else if (fieldNumber == LINE_LINE) lineNumber = value;
        } else {
          skipField(wireType, end);
        }
      }
      lines.add(functionId);
      lines.add(lineNumber);
    }

    private void parseFunction(int end) throws IOException {
      long id = 0;
      long name = 0;
      long systemName = 0;
      long filename = 0;
      long startLine = 0;
      while (pos < end) {
        long tag = readVarint(end);
        int fieldNumber = (int) (tag >>> 3);
        int wireType = (int) (tag & 7);
        if (wireType == WIRE_VARINT) {
          long value = readVarint(end);
          switch (fieldNumber) {
            case FUNCTION_ID -> id = value;
            case FUNCTION_NAME -> name = value;
            case FUNCTION_SYSTEM_NAME -> systemName = value;
            case FUNCTION_FILENAME -> filename = value;
            case FUNCTION_START_LINE -> startLine = value;
            default -> {}
          }
        } else {
          skipField(wireType, end);
        }
      }
      functions.add(id);
      functions.add(name);
      functions.add(systemName);
      functions.add(filename);
      functions.add(startLine);
    }

    private void parseMapping(int end) throws IOException {
      long id = 0;
      long memoryStart = 0;
      long memoryLimit = 0;
      long fileOffset = 0;
      long filename = 0;
      boolean hasFunctions = false;
      while (pos < end) {
        long tag = readVarint(end);
        int fieldNumber = (int) (tag >>> 3);
        int wireType = (int) (tag & 7);
        if (wireType == WIRE_VARINT) {
          long value = readVarint(end);
          switch (fieldNumber) {
            case MAPPING_ID -> id = value;
            case MAPPING_MEMORY_START -> memoryStart = value;
            case MAPPING_MEMORY_LIMIT -> memoryLimit = value;
            case MAPPING_FILE_OFFSET -> fileOffset = value;
            case MAPPING_FILENAME -> filename = value;
            case MAPPING_HAS_FUNCTIONS -> hasFunctions = value != 0;
            default -> {}
          }
        } else {
          skipField(wireType, end);
        }
      }
      mappings.add(id);
      mappings.add(memoryStart);
      mappings.add(memoryLimit);
      mappings.add(fileOffset);
      mappings.add(filename);
      mappings.add(hasFunctions ? 1 : 0);
    }

    /** Resolves a string table index (0 = empty string, out-of-range = empty string). */
    private String resolve(long index) {
      // Bounds-check BEFORE narrowing: a wire-decoded 64-bit index that truncates to an
      // in-range int would silently resolve to the wrong string.
      if (index <= 0 || index >= stringTable.size()) {
        return "";
      }
      return stringTable.get((int) index);
    }
  }
}

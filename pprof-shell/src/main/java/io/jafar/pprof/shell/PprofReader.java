package io.jafar.pprof.shell;

import io.jafar.shell.core.proto.ProtoUtil;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Hand-rolled gzip + protobuf wire format decoder for pprof profiles.
 *
 * <p>Supports VARINT (wire type 0) and LEN (wire type 2) fields, which cover all fields in
 * google/pprof/proto/profile.proto. Field numbers are hardcoded; no proto descriptor is needed.
 *
 * <p>Both packed and unpacked repeated fields are handled correctly.
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

  private PprofReader() {}

  /**
   * Reads a gzip-compressed pprof profile from the given path.
   *
   * @param path path to the .pb.gz or .pprof file
   * @return parsed profile
   * @throws IOException if reading or parsing fails
   */
  public static PprofProfile.Profile read(Path path) throws IOException {
    byte[] raw;
    try (InputStream is = Files.newInputStream(path);
        GZIPInputStream gzis = new GZIPInputStream(is, 65536)) {
      raw = gzis.readAllBytes();
    }
    return parseProfile(raw, 0, raw.length);
  }

  // ---- Intermediate raw types (pre-string-resolution) ----

  private record RawValueType(long type, long unit) {}

  private static final class RawSample {
    final List<Long> locationIds = new ArrayList<>();
    final List<Long> values = new ArrayList<>();
    final List<long[]> labels = new ArrayList<>(); // [key, str, num, numUnit]
  }

  private static final class RawLocation {
    long id;
    long mappingId;
    long address;
    final List<long[]> lines = new ArrayList<>(); // [functionId, lineNumber]
    boolean isFolded;
  }

  private static final class RawFunction {
    long id;
    long name;
    long systemName;
    long filename;
    long startLine;
  }

  private static final class RawMapping {
    long id;
    long memoryStart;
    long memoryLimit;
    long fileOffset;
    long filename;
    boolean hasFunctions;
  }

  // ---- Profile parser ----

  private static PprofProfile.Profile parseProfile(byte[] buf, int start, int end)
      throws IOException {
    List<RawValueType> rawSampleTypes = new ArrayList<>();
    List<RawSample> rawSamples = new ArrayList<>();
    List<RawMapping> rawMappings = new ArrayList<>();
    List<RawLocation> rawLocations = new ArrayList<>();
    List<RawFunction> rawFunctions = new ArrayList<>();
    List<String> stringTable = new ArrayList<>();
    RawValueType rawPeriodType = null;
    long period = 0;
    long durationNanos = 0;
    long timeNanos = 0;

    int pos = start;
    while (pos < end) {
      long tag = ProtoUtil.readVarint(buf, pos);
      pos += ProtoUtil.varintLen(buf, pos);
      int fieldNumber = (int) (tag >>> 3);
      int wireType = (int) (tag & 7);

      switch (wireType) {
        case ProtoUtil.WIRE_LEN -> {
          int len = ProtoUtil.readSafeLen(buf, pos);
          pos += ProtoUtil.varintLen(buf, pos);
          int msgEnd = pos + len;
          switch (fieldNumber) {
            case PROFILE_SAMPLE_TYPE -> rawSampleTypes.add(parseValueType(buf, pos, msgEnd));
            case PROFILE_SAMPLE -> rawSamples.add(parseSample(buf, pos, msgEnd));
            case PROFILE_MAPPING -> rawMappings.add(parseMapping(buf, pos, msgEnd));
            case PROFILE_LOCATION -> rawLocations.add(parseLocation(buf, pos, msgEnd));
            case PROFILE_FUNCTION -> rawFunctions.add(parseFunction(buf, pos, msgEnd));
            case PROFILE_STRING_TABLE ->
                stringTable.add(new String(buf, pos, len, StandardCharsets.UTF_8));
            case PROFILE_PERIOD_TYPE -> rawPeriodType = parseValueType(buf, pos, msgEnd);
            default -> {
              /* skip unknown */
            }
          }
          pos = msgEnd;
        }
        case ProtoUtil.WIRE_VARINT -> {
          long value = ProtoUtil.readVarint(buf, pos);
          pos += ProtoUtil.varintLen(buf, pos);
          switch (fieldNumber) {
            case PROFILE_TIME_NANOS -> timeNanos = value;
            case PROFILE_DURATION_NANOS -> durationNanos = value;
            case PROFILE_PERIOD -> period = value;
            default -> {
              /* skip */
            }
          }
        }
        case ProtoUtil.WIRE_I64 -> pos += 8;
        case ProtoUtil.WIRE_I32 -> pos += 4;
        default ->
            throw new IOException("Unknown wire type " + wireType + " at offset " + (pos - 1));
      }
    }

    return buildProfile(
        rawSampleTypes,
        rawSamples,
        rawMappings,
        rawLocations,
        rawFunctions,
        stringTable,
        rawPeriodType,
        period,
        durationNanos,
        timeNanos);
  }

  private static PprofProfile.Profile buildProfile(
      List<RawValueType> rawSampleTypes,
      List<RawSample> rawSamples,
      List<RawMapping> rawMappings,
      List<RawLocation> rawLocations,
      List<RawFunction> rawFunctions,
      List<String> stringTable,
      RawValueType rawPeriodType,
      long period,
      long durationNanos,
      long timeNanos) {

    List<PprofProfile.ValueType> sampleTypes = new ArrayList<>(rawSampleTypes.size());
    for (RawValueType rv : rawSampleTypes) {
      sampleTypes.add(
          new PprofProfile.ValueType(
              resolve(stringTable, rv.type()), resolve(stringTable, rv.unit())));
    }

    PprofProfile.ValueType periodType =
        rawPeriodType != null
            ? new PprofProfile.ValueType(
                resolve(stringTable, rawPeriodType.type()),
                resolve(stringTable, rawPeriodType.unit()))
            : null;

    List<PprofProfile.Function> functions = new ArrayList<>(rawFunctions.size());
    for (RawFunction rf : rawFunctions) {
      functions.add(
          new PprofProfile.Function(
              rf.id,
              resolve(stringTable, rf.name),
              resolve(stringTable, rf.systemName),
              resolve(stringTable, rf.filename),
              rf.startLine));
    }

    List<PprofProfile.Location> locations = new ArrayList<>(rawLocations.size());
    for (RawLocation rl : rawLocations) {
      List<PprofProfile.Line> lines = new ArrayList<>(rl.lines.size());
      for (long[] rawLine : rl.lines) {
        lines.add(new PprofProfile.Line(rawLine[0], rawLine[1]));
      }
      locations.add(
          new PprofProfile.Location(
              rl.id, rl.mappingId, rl.address, List.copyOf(lines), rl.isFolded));
    }

    List<PprofProfile.Mapping> mappings = new ArrayList<>(rawMappings.size());
    for (RawMapping rm : rawMappings) {
      mappings.add(
          new PprofProfile.Mapping(
              rm.id,
              rm.memoryStart,
              rm.memoryLimit,
              rm.fileOffset,
              resolve(stringTable, rm.filename),
              rm.hasFunctions));
    }

    List<PprofProfile.Sample> samples = new ArrayList<>(rawSamples.size());
    for (RawSample rs : rawSamples) {
      List<PprofProfile.Label> labels = new ArrayList<>(rs.labels.size());
      for (long[] rl : rs.labels) {
        // rl: [key, str, num, numUnit]
        String key = resolve(stringTable, rl[0]);
        String str = rl[1] != 0 ? resolve(stringTable, rl[1]) : null;
        long num = rl[2];
        String numUnit = rl[3] != 0 ? resolve(stringTable, rl[3]) : null;
        labels.add(new PprofProfile.Label(key, str, num, numUnit));
      }
      samples.add(
          new PprofProfile.Sample(
              List.copyOf(rs.locationIds), List.copyOf(rs.values), List.copyOf(labels)));
    }

    return new PprofProfile.Profile(
        List.copyOf(sampleTypes),
        List.copyOf(samples),
        List.copyOf(mappings),
        List.copyOf(locations),
        List.copyOf(functions),
        List.copyOf(stringTable),
        period,
        periodType,
        durationNanos,
        timeNanos);
  }

  // ---- Sub-message parsers ----

  private static RawValueType parseValueType(byte[] buf, int start, int end) throws IOException {
    long type = 0;
    long unit = 0;
    int pos = start;
    while (pos < end) {
      long tag = ProtoUtil.readVarint(buf, pos);
      pos += ProtoUtil.varintLen(buf, pos);
      int fieldNumber = (int) (tag >>> 3);
      int wireType = (int) (tag & 7);
      if (wireType == ProtoUtil.WIRE_VARINT) {
        long value = ProtoUtil.readVarint(buf, pos);
        pos += ProtoUtil.varintLen(buf, pos);
        if (fieldNumber == VALUE_TYPE_TYPE) type = value;
        else if (fieldNumber == VALUE_TYPE_UNIT) unit = value;
      } else {
        pos = ProtoUtil.skipField(buf, pos, wireType);
      }
    }
    return new RawValueType(type, unit);
  }

  private static RawSample parseSample(byte[] buf, int start, int end) throws IOException {
    RawSample sample = new RawSample();
    int pos = start;
    while (pos < end) {
      long tag = ProtoUtil.readVarint(buf, pos);
      pos += ProtoUtil.varintLen(buf, pos);
      int fieldNumber = (int) (tag >>> 3);
      int wireType = (int) (tag & 7);
      switch (fieldNumber) {
        case SAMPLE_LOCATION_ID -> {
          if (wireType == ProtoUtil.WIRE_VARINT) {
            sample.locationIds.add(ProtoUtil.readVarint(buf, pos));
            pos += ProtoUtil.varintLen(buf, pos);
          } else if (wireType == ProtoUtil.WIRE_LEN) {
            int len = ProtoUtil.readSafeLen(buf, pos);
            pos += ProtoUtil.varintLen(buf, pos);
            int packEnd = pos + len;
            while (pos < packEnd) {
              sample.locationIds.add(ProtoUtil.readVarint(buf, pos));
              pos += ProtoUtil.varintLen(buf, pos);
            }
          } else {
            pos = ProtoUtil.skipField(buf, pos, wireType);
          }
        }
        case SAMPLE_VALUE -> {
          if (wireType == ProtoUtil.WIRE_VARINT) {
            sample.values.add(ProtoUtil.readVarint(buf, pos));
            pos += ProtoUtil.varintLen(buf, pos);
          } else if (wireType == ProtoUtil.WIRE_LEN) {
            int len = ProtoUtil.readSafeLen(buf, pos);
            pos += ProtoUtil.varintLen(buf, pos);
            int packEnd = pos + len;
            while (pos < packEnd) {
              sample.values.add(ProtoUtil.readVarint(buf, pos));
              pos += ProtoUtil.varintLen(buf, pos);
            }
          } else {
            pos = ProtoUtil.skipField(buf, pos, wireType);
          }
        }
        case SAMPLE_LABEL -> {
          if (wireType == ProtoUtil.WIRE_LEN) {
            int len = ProtoUtil.readSafeLen(buf, pos);
            pos += ProtoUtil.varintLen(buf, pos);
            sample.labels.add(parseLabel(buf, pos, pos + len));
            pos += len;
          } else {
            pos = ProtoUtil.skipField(buf, pos, wireType);
          }
        }
        default -> pos = ProtoUtil.skipField(buf, pos, wireType);
      }
    }
    return sample;
  }

  private static long[] parseLabel(byte[] buf, int start, int end) throws IOException {
    long[] label = new long[4]; // [key, str, num, numUnit]
    int pos = start;
    while (pos < end) {
      long tag = ProtoUtil.readVarint(buf, pos);
      pos += ProtoUtil.varintLen(buf, pos);
      int fieldNumber = (int) (tag >>> 3);
      int wireType = (int) (tag & 7);
      if (wireType == ProtoUtil.WIRE_VARINT) {
        long value = ProtoUtil.readVarint(buf, pos);
        pos += ProtoUtil.varintLen(buf, pos);
        switch (fieldNumber) {
          case LABEL_KEY -> label[0] = value;
          case LABEL_STR -> label[1] = value;
          case LABEL_NUM -> label[2] = value;
          case LABEL_NUM_UNIT -> label[3] = value;
        }
      } else {
        pos = ProtoUtil.skipField(buf, pos, wireType);
      }
    }
    return label;
  }

  private static RawLocation parseLocation(byte[] buf, int start, int end) throws IOException {
    RawLocation loc = new RawLocation();
    int pos = start;
    while (pos < end) {
      long tag = ProtoUtil.readVarint(buf, pos);
      pos += ProtoUtil.varintLen(buf, pos);
      int fieldNumber = (int) (tag >>> 3);
      int wireType = (int) (tag & 7);
      switch (fieldNumber) {
        case LOCATION_ID -> {
          if (wireType == ProtoUtil.WIRE_VARINT) {
            loc.id = ProtoUtil.readVarint(buf, pos);
            pos += ProtoUtil.varintLen(buf, pos);
          } else {
            pos = ProtoUtil.skipField(buf, pos, wireType);
          }
        }
        case LOCATION_MAPPING_ID -> {
          if (wireType == ProtoUtil.WIRE_VARINT) {
            loc.mappingId = ProtoUtil.readVarint(buf, pos);
            pos += ProtoUtil.varintLen(buf, pos);
          } else {
            pos = ProtoUtil.skipField(buf, pos, wireType);
          }
        }
        case LOCATION_ADDRESS -> {
          if (wireType == ProtoUtil.WIRE_VARINT) {
            loc.address = ProtoUtil.readVarint(buf, pos);
            pos += ProtoUtil.varintLen(buf, pos);
          } else {
            pos = ProtoUtil.skipField(buf, pos, wireType);
          }
        }
        case LOCATION_LINE -> {
          if (wireType == ProtoUtil.WIRE_LEN) {
            int len = ProtoUtil.readSafeLen(buf, pos);
            pos += ProtoUtil.varintLen(buf, pos);
            loc.lines.add(parseLine(buf, pos, pos + len));
            pos += len;
          } else {
            pos = ProtoUtil.skipField(buf, pos, wireType);
          }
        }
        case LOCATION_IS_FOLDED -> {
          if (wireType == ProtoUtil.WIRE_VARINT) {
            loc.isFolded = ProtoUtil.readVarint(buf, pos) != 0;
            pos += ProtoUtil.varintLen(buf, pos);
          } else {
            pos = ProtoUtil.skipField(buf, pos, wireType);
          }
        }
        default -> pos = ProtoUtil.skipField(buf, pos, wireType);
      }
    }
    return loc;
  }

  private static long[] parseLine(byte[] buf, int start, int end) throws IOException {
    long[] line = new long[2]; // [functionId, lineNumber]
    int pos = start;
    while (pos < end) {
      long tag = ProtoUtil.readVarint(buf, pos);
      pos += ProtoUtil.varintLen(buf, pos);
      int fieldNumber = (int) (tag >>> 3);
      int wireType = (int) (tag & 7);
      if (wireType == ProtoUtil.WIRE_VARINT) {
        long value = ProtoUtil.readVarint(buf, pos);
        pos += ProtoUtil.varintLen(buf, pos);
        if (fieldNumber == LINE_FUNCTION_ID) line[0] = value;
        else if (fieldNumber == LINE_LINE) line[1] = value;
      } else {
        pos = ProtoUtil.skipField(buf, pos, wireType);
      }
    }
    return line;
  }

  private static RawFunction parseFunction(byte[] buf, int start, int end) throws IOException {
    RawFunction fn = new RawFunction();
    int pos = start;
    while (pos < end) {
      long tag = ProtoUtil.readVarint(buf, pos);
      pos += ProtoUtil.varintLen(buf, pos);
      int fieldNumber = (int) (tag >>> 3);
      int wireType = (int) (tag & 7);
      if (wireType == ProtoUtil.WIRE_VARINT) {
        long value = ProtoUtil.readVarint(buf, pos);
        pos += ProtoUtil.varintLen(buf, pos);
        switch (fieldNumber) {
          case FUNCTION_ID -> fn.id = value;
          case FUNCTION_NAME -> fn.name = value;
          case FUNCTION_SYSTEM_NAME -> fn.systemName = value;
          case FUNCTION_FILENAME -> fn.filename = value;
          case FUNCTION_START_LINE -> fn.startLine = value;
        }
      } else {
        pos = ProtoUtil.skipField(buf, pos, wireType);
      }
    }
    return fn;
  }

  private static RawMapping parseMapping(byte[] buf, int start, int end) throws IOException {
    RawMapping m = new RawMapping();
    int pos = start;
    while (pos < end) {
      long tag = ProtoUtil.readVarint(buf, pos);
      pos += ProtoUtil.varintLen(buf, pos);
      int fieldNumber = (int) (tag >>> 3);
      int wireType = (int) (tag & 7);
      if (wireType == ProtoUtil.WIRE_VARINT) {
        long value = ProtoUtil.readVarint(buf, pos);
        pos += ProtoUtil.varintLen(buf, pos);
        switch (fieldNumber) {
          case MAPPING_ID -> m.id = value;
          case MAPPING_MEMORY_START -> m.memoryStart = value;
          case MAPPING_MEMORY_LIMIT -> m.memoryLimit = value;
          case MAPPING_FILE_OFFSET -> m.fileOffset = value;
          case MAPPING_FILENAME -> m.filename = value;
          case MAPPING_HAS_FUNCTIONS -> m.hasFunctions = value != 0;
        }
      } else {
        pos = ProtoUtil.skipField(buf, pos, wireType);
      }
    }
    return m;
  }

  /** Resolves a string table index (0 = empty string). */
  private static String resolve(List<String> stringTable, long index) {
    int i = (int) index;
    if (i <= 0 || i >= stringTable.size()) return "";
    return stringTable.get(i);
  }
}

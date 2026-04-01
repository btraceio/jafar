package io.jafar.otelp.shell;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Hand-rolled protobuf wire format decoder for OTLP profiling signal files.
 *
 * <p>Supports VARINT (wire type 0), I64 (wire type 1), LEN (wire type 2), and I32 (wire type 5)
 * fields. Field numbers are hardcoded from
 * opentelemetry/proto/profiles/v1development/profiles.proto; no proto descriptor is needed.
 *
 * <p>The file is expected to contain a binary-encoded {@code ProfilesData} message (no gzip
 * wrapper).
 */
public final class OtelpReader {

  // Protobuf wire types
  private static final int WIRE_VARINT = 0;
  private static final int WIRE_I64 = 1;
  private static final int WIRE_LEN = 2;
  private static final int WIRE_I32 = 5;

  // ProfilesData field numbers
  private static final int PROFILES_DATA_RESOURCE_PROFILES = 1;
  private static final int PROFILES_DATA_DICTIONARY = 2;

  // ProfilesDictionary field numbers
  private static final int DICT_MAPPING_TABLE = 1;
  private static final int DICT_LOCATION_TABLE = 2;
  private static final int DICT_FUNCTION_TABLE = 3;
  private static final int DICT_STRING_TABLE = 5;
  private static final int DICT_ATTRIBUTE_TABLE = 6;
  private static final int DICT_STACK_TABLE = 7;

  // ResourceProfiles field numbers
  private static final int RESOURCE_PROFILES_SCOPE_PROFILES = 2;

  // ScopeProfiles field numbers
  private static final int SCOPE_PROFILES_PROFILES = 2;

  // Profile field numbers
  private static final int PROFILE_SAMPLE_TYPE = 1;
  private static final int PROFILE_SAMPLES = 2;
  private static final int PROFILE_TIME_UNIX_NANO = 3; // fixed64 (wire type 1)
  private static final int PROFILE_DURATION_NANO = 4;
  private static final int PROFILE_PERIOD_TYPE = 5;
  private static final int PROFILE_PERIOD = 6;

  // ValueType field numbers
  private static final int VALUE_TYPE_TYPE_STRINDEX = 1;
  private static final int VALUE_TYPE_UNIT_STRINDEX = 2;

  // Sample field numbers
  private static final int SAMPLE_STACK_INDEX = 1;
  private static final int SAMPLE_ATTRIBUTE_INDICES = 2;
  private static final int SAMPLE_VALUES = 4;
  private static final int SAMPLE_TIMESTAMPS_UNIX_NANO = 5; // packed fixed64

  // Stack field numbers
  private static final int STACK_LOCATION_INDICES = 1;

  // Location field numbers
  private static final int LOCATION_MAPPING_INDEX = 1;
  private static final int LOCATION_ADDRESS = 2;
  private static final int LOCATION_LINES = 3;
  private static final int LOCATION_ATTRIBUTE_INDICES = 4;

  // Line field numbers
  private static final int LINE_FUNCTION_INDEX = 1;
  private static final int LINE_LINE = 2;
  private static final int LINE_COLUMN = 3;

  // Function field numbers
  private static final int FUNCTION_NAME_STRINDEX = 1;
  private static final int FUNCTION_SYSTEM_NAME_STRINDEX = 2;
  private static final int FUNCTION_FILENAME_STRINDEX = 3;
  private static final int FUNCTION_START_LINE = 4;

  // Mapping field numbers
  private static final int MAPPING_MEMORY_START = 1;
  private static final int MAPPING_MEMORY_LIMIT = 2;
  private static final int MAPPING_FILE_OFFSET = 3;
  private static final int MAPPING_FILENAME_STRINDEX = 4;

  // KeyValueAndUnit field numbers
  private static final int KVU_KEY_STRINDEX = 1;
  private static final int KVU_VALUE = 2;
  private static final int KVU_UNIT_STRINDEX = 3;

  // AnyValue field numbers (oneof — we only read string_value for MVP)
  private static final int ANY_VALUE_STRING_VALUE = 1;

  private OtelpReader() {}

  /**
   * Reads a binary-encoded OTLP profiles file from the given path.
   *
   * @param path path to the .otlp file
   * @return parsed profiles data
   * @throws IOException if reading or parsing fails
   */
  public static OtelpProfile.ProfilesData read(Path path) throws IOException {
    byte[] raw = Files.readAllBytes(path);
    return parseProfilesData(raw, 0, raw.length);
  }

  // ---- Intermediate raw types (pre-string-resolution) ----

  private static final class RawDictionary {
    final List<RawMapping> mappingTable = new ArrayList<>();
    final List<RawLocation> locationTable = new ArrayList<>();
    final List<RawFunction> functionTable = new ArrayList<>();
    final List<String> stringTable = new ArrayList<>();
    final List<RawAttribute> attributeTable = new ArrayList<>();
    final List<RawStack> stackTable = new ArrayList<>();
  }

  private record RawValueType(int typeStrindex, int unitStrindex) {}

  private static final class RawProfile {
    RawValueType sampleType;
    final List<RawSample> samples = new ArrayList<>();
    long timeUnixNano;
    long durationNano;
    RawValueType periodType;
    long period;
  }

  private static final class RawSample {
    int stackIndex;
    final List<Integer> attributeIndices = new ArrayList<>();
    final List<Long> values = new ArrayList<>();
    final List<Long> timestampsUnixNano = new ArrayList<>();
  }

  private record RawStack(List<Integer> locationIndices) {}

  private static final class RawLocation {
    int mappingIndex;
    long address;
    final List<RawLine> lines = new ArrayList<>();
    final List<Integer> attributeIndices = new ArrayList<>();
  }

  private record RawLine(int functionIndex, long line, long column) {}

  private static final class RawFunction {
    int nameStrindex;
    int systemNameStrindex;
    int filenameStrindex;
    long startLine;
  }

  private static final class RawMapping {
    long memoryStart;
    long memoryLimit;
    long fileOffset;
    int filenameStrindex;
  }

  private static final class RawAttribute {
    int keyStrindex;
    String value = "";
    int unitStrindex;
  }

  // ---- ProfilesData parser ----

  private static OtelpProfile.ProfilesData parseProfilesData(byte[] buf, int start, int end)
      throws IOException {
    RawDictionary rawDict = new RawDictionary();
    List<RawProfile> rawProfiles = new ArrayList<>();

    int pos = start;
    while (pos < end) {
      long tag = readVarint(buf, pos);
      pos += varintLen(buf, pos);
      int fieldNumber = (int) (tag >>> 3);
      int wireType = (int) (tag & 7);

      if (wireType == WIRE_LEN) {
        int len = (int) readVarint(buf, pos);
        pos += varintLen(buf, pos);
        int msgEnd = pos + len;
        switch (fieldNumber) {
          case PROFILES_DATA_RESOURCE_PROFILES ->
              parseResourceProfiles(buf, pos, msgEnd, rawProfiles);
          case PROFILES_DATA_DICTIONARY -> parseDictionary(buf, pos, msgEnd, rawDict);
          default -> {} // skip unknown
        }
        pos = msgEnd;
      } else {
        pos = skipField(buf, pos, wireType);
      }
    }

    return buildProfilesData(rawDict, rawProfiles);
  }

  private static void parseResourceProfiles(
      byte[] buf, int start, int end, List<RawProfile> profiles) throws IOException {
    int pos = start;
    while (pos < end) {
      long tag = readVarint(buf, pos);
      pos += varintLen(buf, pos);
      int fieldNumber = (int) (tag >>> 3);
      int wireType = (int) (tag & 7);

      if (wireType == WIRE_LEN) {
        int len = (int) readVarint(buf, pos);
        pos += varintLen(buf, pos);
        int msgEnd = pos + len;
        if (fieldNumber == RESOURCE_PROFILES_SCOPE_PROFILES) {
          parseScopeProfiles(buf, pos, msgEnd, profiles);
        }
        pos = msgEnd;
      } else {
        pos = skipField(buf, pos, wireType);
      }
    }
  }

  private static void parseScopeProfiles(byte[] buf, int start, int end, List<RawProfile> profiles)
      throws IOException {
    int pos = start;
    while (pos < end) {
      long tag = readVarint(buf, pos);
      pos += varintLen(buf, pos);
      int fieldNumber = (int) (tag >>> 3);
      int wireType = (int) (tag & 7);

      if (wireType == WIRE_LEN) {
        int len = (int) readVarint(buf, pos);
        pos += varintLen(buf, pos);
        int msgEnd = pos + len;
        if (fieldNumber == SCOPE_PROFILES_PROFILES) {
          profiles.add(parseProfile(buf, pos, msgEnd));
        }
        pos = msgEnd;
      } else {
        pos = skipField(buf, pos, wireType);
      }
    }
  }

  private static RawProfile parseProfile(byte[] buf, int start, int end) throws IOException {
    RawProfile profile = new RawProfile();
    int pos = start;
    while (pos < end) {
      long tag = readVarint(buf, pos);
      pos += varintLen(buf, pos);
      int fieldNumber = (int) (tag >>> 3);
      int wireType = (int) (tag & 7);

      switch (wireType) {
        case WIRE_LEN -> {
          int len = (int) readVarint(buf, pos);
          pos += varintLen(buf, pos);
          int msgEnd = pos + len;
          switch (fieldNumber) {
            case PROFILE_SAMPLE_TYPE -> profile.sampleType = parseValueType(buf, pos, msgEnd);
            case PROFILE_SAMPLES -> profile.samples.add(parseSample(buf, pos, msgEnd));
            case PROFILE_PERIOD_TYPE -> profile.periodType = parseValueType(buf, pos, msgEnd);
            default -> {} // skip unknown
          }
          pos = msgEnd;
        }
        case WIRE_VARINT -> {
          long value = readVarint(buf, pos);
          pos += varintLen(buf, pos);
          switch (fieldNumber) {
            case PROFILE_DURATION_NANO -> profile.durationNano = value;
            case PROFILE_PERIOD -> profile.period = value;
            default -> {} // skip
          }
        }
        case WIRE_I64 -> {
          long value = readFixed64(buf, pos);
          pos += 8;
          if (fieldNumber == PROFILE_TIME_UNIX_NANO) {
            profile.timeUnixNano = value;
          }
        }
        case WIRE_I32 -> pos += 4;
        default ->
            throw new IOException("Unknown wire type " + wireType + " at offset " + (pos - 1));
      }
    }
    return profile;
  }

  private static RawValueType parseValueType(byte[] buf, int start, int end) throws IOException {
    int typeStrindex = 0;
    int unitStrindex = 0;
    int pos = start;
    while (pos < end) {
      long tag = readVarint(buf, pos);
      pos += varintLen(buf, pos);
      int fieldNumber = (int) (tag >>> 3);
      int wireType = (int) (tag & 7);
      if (wireType == WIRE_VARINT) {
        long value = readVarint(buf, pos);
        pos += varintLen(buf, pos);
        if (fieldNumber == VALUE_TYPE_TYPE_STRINDEX) typeStrindex = (int) value;
        else if (fieldNumber == VALUE_TYPE_UNIT_STRINDEX) unitStrindex = (int) value;
      } else {
        pos = skipField(buf, pos, wireType);
      }
    }
    return new RawValueType(typeStrindex, unitStrindex);
  }

  private static RawSample parseSample(byte[] buf, int start, int end) throws IOException {
    RawSample sample = new RawSample();
    int pos = start;
    while (pos < end) {
      long tag = readVarint(buf, pos);
      pos += varintLen(buf, pos);
      int fieldNumber = (int) (tag >>> 3);
      int wireType = (int) (tag & 7);

      switch (fieldNumber) {
        case SAMPLE_STACK_INDEX -> {
          if (wireType == WIRE_VARINT) {
            sample.stackIndex = (int) readVarint(buf, pos);
            pos += varintLen(buf, pos);
          } else {
            pos = skipField(buf, pos, wireType);
          }
        }
        case SAMPLE_ATTRIBUTE_INDICES -> {
          if (wireType == WIRE_VARINT) {
            sample.attributeIndices.add((int) readVarint(buf, pos));
            pos += varintLen(buf, pos);
          } else if (wireType == WIRE_LEN) {
            // packed int32
            int len = (int) readVarint(buf, pos);
            pos += varintLen(buf, pos);
            int packEnd = pos + len;
            while (pos < packEnd) {
              sample.attributeIndices.add((int) readVarint(buf, pos));
              pos += varintLen(buf, pos);
            }
          } else {
            pos = skipField(buf, pos, wireType);
          }
        }
        case SAMPLE_VALUES -> {
          if (wireType == WIRE_VARINT) {
            sample.values.add(readVarint(buf, pos));
            pos += varintLen(buf, pos);
          } else if (wireType == WIRE_LEN) {
            // packed int64
            int len = (int) readVarint(buf, pos);
            pos += varintLen(buf, pos);
            int packEnd = pos + len;
            while (pos < packEnd) {
              sample.values.add(readVarint(buf, pos));
              pos += varintLen(buf, pos);
            }
          } else {
            pos = skipField(buf, pos, wireType);
          }
        }
        case SAMPLE_TIMESTAMPS_UNIX_NANO -> {
          if (wireType == WIRE_I64) {
            sample.timestampsUnixNano.add(readFixed64(buf, pos));
            pos += 8;
          } else if (wireType == WIRE_LEN) {
            // packed fixed64
            int len = (int) readVarint(buf, pos);
            pos += varintLen(buf, pos);
            int packEnd = pos + len;
            while (pos < packEnd) {
              sample.timestampsUnixNano.add(readFixed64(buf, pos));
              pos += 8;
            }
          } else {
            pos = skipField(buf, pos, wireType);
          }
        }
        default -> pos = skipField(buf, pos, wireType);
      }
    }
    return sample;
  }

  private static void parseDictionary(byte[] buf, int start, int end, RawDictionary dict)
      throws IOException {
    int pos = start;
    while (pos < end) {
      long tag = readVarint(buf, pos);
      pos += varintLen(buf, pos);
      int fieldNumber = (int) (tag >>> 3);
      int wireType = (int) (tag & 7);

      if (wireType == WIRE_LEN) {
        int len = (int) readVarint(buf, pos);
        pos += varintLen(buf, pos);
        int msgEnd = pos + len;
        switch (fieldNumber) {
          case DICT_MAPPING_TABLE -> dict.mappingTable.add(parseMapping(buf, pos, msgEnd));
          case DICT_LOCATION_TABLE -> dict.locationTable.add(parseLocation(buf, pos, msgEnd));
          case DICT_FUNCTION_TABLE -> dict.functionTable.add(parseFunction(buf, pos, msgEnd));
          case DICT_STRING_TABLE ->
              dict.stringTable.add(new String(buf, pos, len, StandardCharsets.UTF_8));
          case DICT_ATTRIBUTE_TABLE ->
              dict.attributeTable.add(parseKeyValueAndUnit(buf, pos, msgEnd));
          case DICT_STACK_TABLE -> dict.stackTable.add(parseStack(buf, pos, msgEnd));
          default -> {} // skip unknown
        }
        pos = msgEnd;
      } else {
        pos = skipField(buf, pos, wireType);
      }
    }
  }

  private static RawStack parseStack(byte[] buf, int start, int end) throws IOException {
    List<Integer> locationIndices = new ArrayList<>();
    int pos = start;
    while (pos < end) {
      long tag = readVarint(buf, pos);
      pos += varintLen(buf, pos);
      int fieldNumber = (int) (tag >>> 3);
      int wireType = (int) (tag & 7);

      if (fieldNumber == STACK_LOCATION_INDICES) {
        if (wireType == WIRE_VARINT) {
          locationIndices.add((int) readVarint(buf, pos));
          pos += varintLen(buf, pos);
        } else if (wireType == WIRE_LEN) {
          // packed int32
          int len = (int) readVarint(buf, pos);
          pos += varintLen(buf, pos);
          int packEnd = pos + len;
          while (pos < packEnd) {
            locationIndices.add((int) readVarint(buf, pos));
            pos += varintLen(buf, pos);
          }
        } else {
          pos = skipField(buf, pos, wireType);
        }
      } else {
        pos = skipField(buf, pos, wireType);
      }
    }
    return new RawStack(locationIndices);
  }

  private static RawLocation parseLocation(byte[] buf, int start, int end) throws IOException {
    RawLocation loc = new RawLocation();
    int pos = start;
    while (pos < end) {
      long tag = readVarint(buf, pos);
      pos += varintLen(buf, pos);
      int fieldNumber = (int) (tag >>> 3);
      int wireType = (int) (tag & 7);

      switch (fieldNumber) {
        case LOCATION_MAPPING_INDEX -> {
          if (wireType == WIRE_VARINT) {
            loc.mappingIndex = (int) readVarint(buf, pos);
            pos += varintLen(buf, pos);
          } else {
            pos = skipField(buf, pos, wireType);
          }
        }
        case LOCATION_ADDRESS -> {
          if (wireType == WIRE_VARINT) {
            loc.address = readVarint(buf, pos);
            pos += varintLen(buf, pos);
          } else {
            pos = skipField(buf, pos, wireType);
          }
        }
        case LOCATION_LINES -> {
          if (wireType == WIRE_LEN) {
            int len = (int) readVarint(buf, pos);
            pos += varintLen(buf, pos);
            loc.lines.add(parseLine(buf, pos, pos + len));
            pos += len;
          } else {
            pos = skipField(buf, pos, wireType);
          }
        }
        case LOCATION_ATTRIBUTE_INDICES -> {
          if (wireType == WIRE_VARINT) {
            loc.attributeIndices.add((int) readVarint(buf, pos));
            pos += varintLen(buf, pos);
          } else if (wireType == WIRE_LEN) {
            int len = (int) readVarint(buf, pos);
            pos += varintLen(buf, pos);
            int packEnd = pos + len;
            while (pos < packEnd) {
              loc.attributeIndices.add((int) readVarint(buf, pos));
              pos += varintLen(buf, pos);
            }
          } else {
            pos = skipField(buf, pos, wireType);
          }
        }
        default -> pos = skipField(buf, pos, wireType);
      }
    }
    return loc;
  }

  private static RawLine parseLine(byte[] buf, int start, int end) throws IOException {
    int functionIndex = 0;
    long line = 0;
    long column = 0;
    int pos = start;
    while (pos < end) {
      long tag = readVarint(buf, pos);
      pos += varintLen(buf, pos);
      int fieldNumber = (int) (tag >>> 3);
      int wireType = (int) (tag & 7);
      if (wireType == WIRE_VARINT) {
        long value = readVarint(buf, pos);
        pos += varintLen(buf, pos);
        switch (fieldNumber) {
          case LINE_FUNCTION_INDEX -> functionIndex = (int) value;
          case LINE_LINE -> line = value;
          case LINE_COLUMN -> column = value;
          default -> {} // skip
        }
      } else {
        pos = skipField(buf, pos, wireType);
      }
    }
    return new RawLine(functionIndex, line, column);
  }

  private static RawFunction parseFunction(byte[] buf, int start, int end) throws IOException {
    RawFunction fn = new RawFunction();
    int pos = start;
    while (pos < end) {
      long tag = readVarint(buf, pos);
      pos += varintLen(buf, pos);
      int fieldNumber = (int) (tag >>> 3);
      int wireType = (int) (tag & 7);
      if (wireType == WIRE_VARINT) {
        long value = readVarint(buf, pos);
        pos += varintLen(buf, pos);
        switch (fieldNumber) {
          case FUNCTION_NAME_STRINDEX -> fn.nameStrindex = (int) value;
          case FUNCTION_SYSTEM_NAME_STRINDEX -> fn.systemNameStrindex = (int) value;
          case FUNCTION_FILENAME_STRINDEX -> fn.filenameStrindex = (int) value;
          case FUNCTION_START_LINE -> fn.startLine = value;
          default -> {} // skip
        }
      } else {
        pos = skipField(buf, pos, wireType);
      }
    }
    return fn;
  }

  private static RawMapping parseMapping(byte[] buf, int start, int end) throws IOException {
    RawMapping m = new RawMapping();
    int pos = start;
    while (pos < end) {
      long tag = readVarint(buf, pos);
      pos += varintLen(buf, pos);
      int fieldNumber = (int) (tag >>> 3);
      int wireType = (int) (tag & 7);
      if (wireType == WIRE_VARINT) {
        long value = readVarint(buf, pos);
        pos += varintLen(buf, pos);
        switch (fieldNumber) {
          case MAPPING_MEMORY_START -> m.memoryStart = value;
          case MAPPING_MEMORY_LIMIT -> m.memoryLimit = value;
          case MAPPING_FILE_OFFSET -> m.fileOffset = value;
          case MAPPING_FILENAME_STRINDEX -> m.filenameStrindex = (int) value;
          default -> {} // skip
        }
      } else {
        pos = skipField(buf, pos, wireType);
      }
    }
    return m;
  }

  private static RawAttribute parseKeyValueAndUnit(byte[] buf, int start, int end)
      throws IOException {
    RawAttribute attr = new RawAttribute();
    int pos = start;
    while (pos < end) {
      long tag = readVarint(buf, pos);
      pos += varintLen(buf, pos);
      int fieldNumber = (int) (tag >>> 3);
      int wireType = (int) (tag & 7);

      switch (fieldNumber) {
        case KVU_KEY_STRINDEX -> {
          if (wireType == WIRE_VARINT) {
            attr.keyStrindex = (int) readVarint(buf, pos);
            pos += varintLen(buf, pos);
          } else {
            pos = skipField(buf, pos, wireType);
          }
        }
        case KVU_VALUE -> {
          if (wireType == WIRE_LEN) {
            int len = (int) readVarint(buf, pos);
            pos += varintLen(buf, pos);
            // AnyValue — extract string_value if present (field 1 of AnyValue)
            attr.value = parseAnyValueAsString(buf, pos, pos + len);
            pos += len;
          } else {
            pos = skipField(buf, pos, wireType);
          }
        }
        case KVU_UNIT_STRINDEX -> {
          if (wireType == WIRE_VARINT) {
            attr.unitStrindex = (int) readVarint(buf, pos);
            pos += varintLen(buf, pos);
          } else {
            pos = skipField(buf, pos, wireType);
          }
        }
        default -> pos = skipField(buf, pos, wireType);
      }
    }
    return attr;
  }

  private static String parseAnyValueAsString(byte[] buf, int start, int end) throws IOException {
    int pos = start;
    while (pos < end) {
      long tag = readVarint(buf, pos);
      pos += varintLen(buf, pos);
      int fieldNumber = (int) (tag >>> 3);
      int wireType = (int) (tag & 7);

      if (wireType == WIRE_LEN) {
        int len = (int) readVarint(buf, pos);
        pos += varintLen(buf, pos);
        if (fieldNumber == ANY_VALUE_STRING_VALUE) {
          return new String(buf, pos, len, StandardCharsets.UTF_8);
        }
        pos += len;
      } else if (wireType == WIRE_VARINT) {
        long value = readVarint(buf, pos);
        pos += varintLen(buf, pos);
        // bool_value (field 4), int_value (field 2), etc. — convert to string
        return String.valueOf(value);
      } else {
        pos = skipField(buf, pos, wireType);
      }
    }
    return "";
  }

  // ---- Build phase: resolve string indices ----

  private static OtelpProfile.ProfilesData buildProfilesData(
      RawDictionary rawDict, List<RawProfile> rawProfiles) {
    List<String> st = rawDict.stringTable;

    // Build resolved dictionary
    List<OtelpProfile.Mapping> mappings = new ArrayList<>(rawDict.mappingTable.size());
    for (RawMapping rm : rawDict.mappingTable) {
      mappings.add(
          new OtelpProfile.Mapping(
              rm.memoryStart, rm.memoryLimit, rm.fileOffset, resolve(st, rm.filenameStrindex)));
    }

    List<OtelpProfile.Function> functions = new ArrayList<>(rawDict.functionTable.size());
    for (RawFunction rf : rawDict.functionTable) {
      String name = resolve(st, rf.nameStrindex);
      String sysName = resolve(st, rf.systemNameStrindex);
      functions.add(
          new OtelpProfile.Function(
              name.isEmpty() ? sysName : name,
              sysName,
              resolve(st, rf.filenameStrindex),
              rf.startLine));
    }

    List<OtelpProfile.Location> locations = new ArrayList<>(rawDict.locationTable.size());
    for (RawLocation rl : rawDict.locationTable) {
      List<OtelpProfile.Line> lines = new ArrayList<>(rl.lines.size());
      for (RawLine rawLine : rl.lines) {
        lines.add(new OtelpProfile.Line(rawLine.functionIndex(), rawLine.line(), rawLine.column()));
      }
      locations.add(
          new OtelpProfile.Location(
              rl.mappingIndex, rl.address, List.copyOf(lines), List.copyOf(rl.attributeIndices)));
    }

    List<OtelpProfile.Stack> stacks = new ArrayList<>(rawDict.stackTable.size());
    for (RawStack rs : rawDict.stackTable) {
      stacks.add(new OtelpProfile.Stack(List.copyOf(rs.locationIndices())));
    }

    List<OtelpProfile.Attribute> attributes = new ArrayList<>(rawDict.attributeTable.size());
    for (RawAttribute ra : rawDict.attributeTable) {
      attributes.add(
          new OtelpProfile.Attribute(
              resolve(st, ra.keyStrindex), ra.value, resolve(st, ra.unitStrindex)));
    }

    OtelpProfile.Dictionary dictionary =
        new OtelpProfile.Dictionary(
            List.copyOf(mappings),
            List.copyOf(locations),
            List.copyOf(functions),
            List.copyOf(st),
            List.copyOf(attributes),
            List.copyOf(stacks));

    // Build resolved profiles
    List<OtelpProfile.Profile> profiles = new ArrayList<>(rawProfiles.size());
    for (RawProfile rp : rawProfiles) {
      OtelpProfile.ValueType sampleType =
          rp.sampleType != null
              ? new OtelpProfile.ValueType(
                  resolve(st, rp.sampleType.typeStrindex()),
                  resolve(st, rp.sampleType.unitStrindex()))
              : new OtelpProfile.ValueType("", "");
      OtelpProfile.ValueType periodType =
          rp.periodType != null
              ? new OtelpProfile.ValueType(
                  resolve(st, rp.periodType.typeStrindex()),
                  resolve(st, rp.periodType.unitStrindex()))
              : null;

      List<OtelpProfile.Sample> samples = new ArrayList<>(rp.samples.size());
      for (RawSample rs : rp.samples) {
        samples.add(
            new OtelpProfile.Sample(
                rs.stackIndex,
                List.copyOf(rs.attributeIndices),
                List.copyOf(rs.values),
                List.copyOf(rs.timestampsUnixNano)));
      }

      profiles.add(
          new OtelpProfile.Profile(
              sampleType,
              List.copyOf(samples),
              rp.timeUnixNano,
              rp.durationNano,
              periodType,
              rp.period));
    }

    return new OtelpProfile.ProfilesData(List.copyOf(profiles), dictionary);
  }

  // ---- Varint and fixed helpers ----

  /** Reads a varint (up to 64 bits) from {@code buf[pos]}. */
  static long readVarint(byte[] buf, int pos) throws IOException {
    long result = 0;
    int shift = 0;
    while (true) {
      if (pos >= buf.length)
        throw new IOException("Truncated protobuf: varint extends past buffer end");
      byte b = buf[pos++];
      result |= (long) (b & 0x7F) << shift;
      if ((b & 0x80) == 0) break;
      shift += 7;
      if (shift > 63) throw new IOException("Malformed protobuf: varint exceeds 64 bits");
    }
    return result;
  }

  /** Returns the byte length of the varint at {@code buf[pos]}. */
  static int varintLen(byte[] buf, int pos) throws IOException {
    int len = 1;
    while (true) {
      if (pos >= buf.length)
        throw new IOException("Truncated protobuf: varint extends past buffer end");
      if ((buf[pos++] & 0x80) == 0) break;
      len++;
      if (len > 10) throw new IOException("Malformed protobuf: varint exceeds 64 bits");
    }
    return len;
  }

  /** Reads a little-endian 64-bit fixed value from {@code buf[pos]}. */
  static long readFixed64(byte[] buf, int pos) throws IOException {
    if (pos + 8 > buf.length)
      throw new IOException("Truncated protobuf: fixed64 extends past buffer end");
    long result = 0;
    for (int i = 0; i < 8; i++) {
      result |= (long) (buf[pos + i] & 0xFF) << (8 * i);
    }
    return result;
  }

  /** Skips a field of the given wire type; returns the new position after the field. */
  private static int skipField(byte[] buf, int pos, int wireType) throws IOException {
    return switch (wireType) {
      case WIRE_VARINT -> pos + varintLen(buf, pos);
      case WIRE_I64 -> {
        if (pos + 8 > buf.length)
          throw new IOException("Truncated protobuf: fixed64 extends past buffer end");
        yield pos + 8;
      }
      case WIRE_LEN -> {
        int lenBytes = varintLen(buf, pos);
        int len = (int) readVarint(buf, pos);
        yield pos + lenBytes + len;
      }
      case WIRE_I32 -> {
        if (pos + 4 > buf.length)
          throw new IOException("Truncated protobuf: fixed32 extends past buffer end");
        yield pos + 4;
      }
      default -> throw new IOException("Cannot skip unknown wire type " + wireType);
    };
  }

  /** Resolves a string table index (0 = empty string). */
  private static String resolve(List<String> stringTable, int index) {
    if (index <= 0 || index >= stringTable.size()) return "";
    return stringTable.get(index);
  }
}

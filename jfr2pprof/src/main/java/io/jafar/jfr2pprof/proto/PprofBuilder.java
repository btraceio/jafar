package io.jafar.jfr2pprof.proto;

import io.jafar.jfr2pprof.config.FrameFormat;
import io.jafar.jfr2pprof.config.ValueTypePair;
import io.jafar.jfr2pprof.convert.FrameExtractor;
import io.jafar.parser.api.ArrayType;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

public final class PprofBuilder {

  private static record SampleKey(long[] locationIds, List<Label> labels) {
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof SampleKey other)) return false;
      return Arrays.equals(locationIds, other.locationIds) && labels.equals(other.labels);
    }

    @Override
    public int hashCode() {
      return 31 * Arrays.hashCode(locationIds) + labels.hashCode();
    }
  }

  private final int totalValueCount;
  private final List<String> stringTable = new ArrayList<>();
  private final Map<String, Long> stringIndex = new HashMap<>();
  private final Map<String, Long> functionIndex = new HashMap<>();
  private final List<long[]> functionEntries = new ArrayList<>();
  private final Map<Long, Long> locationIndex = new HashMap<>();
  private final List<long[]> locationEntries = new ArrayList<>();
  private final LinkedHashMap<SampleKey, long[]> samples = new LinkedHashMap<>();

  public PprofBuilder(int totalValueCount) {
    this.totalValueCount = totalValueCount;
    stringTable.add("");
    stringIndex.put("", 0L);
  }

  public long internString(String s) {
    if (s == null || s.isBlank()) return 0;
    Long idx = stringIndex.get(s);
    if (idx != null) return idx;
    long i = stringTable.size();
    stringTable.add(s);
    stringIndex.put(s, i);
    return i;
  }

  public long internFunction(String className, String methodName, long startLine) {
    String key = className + "\0" + methodName + "\0" + startLine;
    Long cached = functionIndex.get(key);
    if (cached != null) return cached;
    long id = functionEntries.size() + 1;
    functionEntries.add(
        new long[] {
          id, internString(className), internString(methodName), internString(""), startLine
        });
    functionIndex.put(key, id);
    return id;
  }

  public long internLocation(long functionId, long lineNumber) {
    long normalizedLine = lineNumber < 0 ? 0 : lineNumber;
    long key = functionId * 0x1_0000_0000L + normalizedLine;
    Long cached = locationIndex.get(key);
    if (cached != null) return cached;
    long id = locationEntries.size() + 1;
    locationEntries.add(new long[] {id, functionId, normalizedLine});
    locationIndex.put(key, id);
    return id;
  }

  public long[] internStack(Object frames, FrameFormat fmt) {
    Object[] frameArray;
    if (frames instanceof ArrayType at) {
      frameArray = (Object[]) at.getArray();
    } else if (frames instanceof Object[] arr) {
      frameArray = arr;
    } else {
      return new long[0];
    }

    List<Long> locIds = new ArrayList<>(frameArray.length);
    for (Object frameElement : frameArray) {
      FrameExtractor.FrameData data = FrameExtractor.extractData(frameElement);
      if (data == null) continue;

      String renderedName = fmt.render(data.className(), data.methodName(), data.lineNumber());
      long fnId =
          internFunction(
              renderedName, data.methodName(), data.lineNumber() < 0 ? 0 : data.lineNumber());
      long locId = internLocation(fnId, data.lineNumber() < 0 ? 0 : data.lineNumber());
      locIds.add(locId);
    }

    long[] result = new long[locIds.size()];
    for (int i = 0; i < result.length; i++) {
      result[i] = locIds.get(i);
    }
    return result;
  }

  public void addSample(long[] locationIds, int firstValueIndex, long[] vals, List<Label> labels) {
    SampleKey key = new SampleKey(locationIds, labels);
    samples.computeIfAbsent(key, k -> new long[totalValueCount]);
    long[] sampleVals = samples.get(key);
    for (int j = 0; j < vals.length; j++) {
      sampleVals[firstValueIndex + j] += vals[j];
    }
  }

  public Label label(String pprofKey, Object jfrValue) {
    if (jfrValue instanceof Number n) {
      return new Label(pprofKey, null, n.longValue(), null);
    }
    if (jfrValue instanceof String s) {
      try {
        long v = Long.parseLong(s);
        return new Label(pprofKey, null, v, null);
      } catch (NumberFormatException e) {
        return new Label(pprofKey, s, 0, null);
      }
    }
    return new Label(pprofKey, String.valueOf(jfrValue), 0, null);
  }

  public int sampleCount() {
    return samples.size();
  }

  public void build(
      List<ValueTypePair> valueTypes,
      long timeNanos,
      long durationNanos,
      boolean gzip,
      OutputStream out)
      throws IOException {
    byte[] profileBytes = serialize(valueTypes, timeNanos, durationNanos);
    if (gzip) {
      try (GZIPOutputStream gzOut = new GZIPOutputStream(out)) {
        gzOut.write(profileBytes);
      }
    } else {
      out.write(profileBytes);
    }
  }

  private byte[] serialize(List<ValueTypePair> vts, long timeNanos, long durNanos) {
    PprofWriter w = new PprofWriter();

    // sample_type[] (field 1)
    for (ValueTypePair vt : vts) {
      w.beginMessage();
      w.writeInt64(1, internString(vt.name()));
      w.writeInt64(2, internString(vt.unit()));
      w.endMessage(1);
    }

    // samples[] (field 2)
    for (Map.Entry<SampleKey, long[]> e : samples.entrySet()) {
      SampleKey k = e.getKey();
      long[] vals = e.getValue();
      w.beginMessage();
      for (long lid : k.locationIds()) {
        w.writeInt64(1, lid);
      }
      for (long v : vals) {
        w.writeInt64(2, v);
      }
      for (Label lbl : k.labels()) {
        w.beginMessage();
        w.writeInt64(1, internString(lbl.key()));
        if (lbl.isStr()) {
          w.writeInt64(2, internString(lbl.str()));
        } else {
          w.writeInt64(3, lbl.num());
        }
        w.endMessage(3);
      }
      w.endMessage(2);
    }

    // location[] (field 4)
    for (long[] loc : locationEntries) {
      w.beginMessage();
      w.writeInt64(1, loc[0]); // id
      // line sub-message (field 4)
      w.beginMessage();
      w.writeInt64(1, loc[1]); // function_id
      if (loc[2] > 0) {
        w.writeInt64(2, loc[2]); // line number
      }
      w.endMessage(4);
      w.endMessage(4);
    }

    // function[] (field 5)
    for (long[] fn : functionEntries) {
      w.beginMessage();
      w.writeInt64(1, fn[0]);
      w.writeInt64(2, fn[1]);
      w.writeInt64(3, fn[2]);
      w.writeInt64(4, fn[3]);
      if (fn[4] > 0) {
        w.writeInt64(5, fn[4]);
      }
      w.endMessage(5);
    }

    // string_table (field 6)
    for (String s : stringTable) {
      w.writeString(6, s);
    }

    // time_nanos (9), duration_nanos (10)
    if (timeNanos > 0) {
      w.writeInt64(9, timeNanos);
    }
    if (durNanos > 0) {
      w.writeInt64(10, durNanos);
    }

    return w.toByteArray();
  }
}

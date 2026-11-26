package io.jafar.pprof.shell;

import io.jafar.pprof.api.PprofParser;
import io.jafar.pprof.api.PprofProfile;
import io.jafar.shell.core.compare.FrameNames;
import io.jafar.shell.core.compare.ProfileReader;
import io.jafar.shell.core.compare.ProfileSelection;
import io.jafar.shell.core.compare.RecordingProfile;
import io.jafar.shell.core.compare.SampleTypeInfo;
import io.jafar.shell.core.compare.SamplerKind;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reads a pprof profile for a comparison: one sample type (one value column) as the weights, and
 * observation counts for the significance test where they can be derived.
 *
 * <p>Frames are keyed by {@link FrameNames#symbolKey} of the first non-empty of {@code
 * Function.name} and {@code Function.systemName}. Each {@code Line} of a {@code Location} is its
 * own frame, in the order the pprof shell flattens stacks: the leaf is the first line of the first
 * location.
 *
 * <p>Observation counts come, in order, from the selected column itself when its unit is {@code
 * count}; from another column with unit {@code count} in the same sample; from {@code value /
 * period} when the profile's period type has the selected column's unit; otherwise they are
 * unknown, and the comparison reports shares without calling any change significant. The sum is
 * over samples, never a count of {@code Sample} messages: a writer may merge samples with the same
 * stack into one.
 *
 * <p>pprof records no stack truncation, so the profile says truncation is unknown. {@code
 * default_sample_type} is not parsed (the parser does not read field 14), so the default sample
 * type is the first CPU one, else the first.
 */
public final class PprofProfileReader implements ProfileReader {

  /** The format name. */
  public static final String FORMAT = "pprof";

  @Override
  public String format() {
    return FORMAT;
  }

  @Override
  public List<SampleTypeInfo> sampleTypes(Path file) throws Exception {
    return sampleTypes(PprofParser.parse(file));
  }

  /** The sample types of an already parsed profile, in column order. */
  public static List<SampleTypeInfo> sampleTypes(PprofProfile.Profile profile) {
    List<SampleTypeInfo> types = new ArrayList<>();
    for (PprofProfile.ValueType type : profile.sampleTypes()) {
      types.add(new SampleTypeInfo(type.type(), type.unit(), kind(profile, type)));
    }
    return types;
  }

  @Override
  public RecordingProfile read(Path file, ProfileSelection selection) throws Exception {
    return read(PprofParser.parse(file), file, selection);
  }

  /**
   * Reads an already parsed profile.
   *
   * @param path where it was read from, for the description; may be {@code null}
   * @throws IllegalArgumentException when the selection names a sample type the profile lacks, or
   *     the profile has no sample type at all
   */
  public static RecordingProfile read(
      PprofProfile.Profile profile, Path path, ProfileSelection selection) {
    List<PprofProfile.ValueType> types = profile.sampleTypes();
    if (types.isEmpty()) {
      throw new IllegalArgumentException("pprof profile " + path + " has no sample type");
    }
    String reason;
    int column;
    if (selection.sampleType() != null) {
      column = find(types, selection.sampleType(), path);
      reason = "requested";
    } else {
      column = -1;
      for (int i = 0; i < types.size(); i++) {
        if (kind(profile, types.get(i)) == SamplerKind.CPU) {
          column = i;
          break;
        }
      }
      if (column >= 0) {
        reason = "default: the first CPU sample type";
      } else {
        column = 0;
        reason = "default: no CPU sample type, so the first sample type";
      }
    }
    PprofProfile.ValueType selected = types.get(column);
    CountSource counts = countSource(profile, column);

    Map<Long, PprofProfile.Location> locations = new HashMap<>();
    for (PprofProfile.Location location : profile.locations()) {
      locations.put(location.id(), location);
    }
    Map<Long, String> functionKeys = new HashMap<>();
    for (PprofProfile.Function function : profile.functions()) {
      String name = function.name().isEmpty() ? function.systemName() : function.name();
      String key = FrameNames.symbolKey(name);
      if (key != null) {
        functionKeys.put(function.id(), key);
      }
    }

    Map<String, Long> leafWeights = new LinkedHashMap<>();
    Map<String, Long> inclusiveWeights = new LinkedHashMap<>();
    Map<String, Long> leafCounts = new LinkedHashMap<>();
    Map<String, Long> inclusiveCounts = new LinkedHashMap<>();
    long totalWeight = 0;
    long sampleCount = 0;
    boolean inclusive = selection.inclusive();
    for (PprofProfile.Sample sample : profile.samples()) {
      long weight = value(sample, column);
      long count = counts.known ? counts.count(sample) : (weight != 0 ? 1 : 0);
      if (weight == 0 && count == 0) {
        continue;
      }
      totalWeight += weight;
      sampleCount += count;
      List<String> frames = frames(sample, locations, functionKeys, inclusive);
      if (frames.isEmpty()) {
        continue; // still a sample: it counts towards the total, under no frame
      }
      leafWeights.merge(frames.get(0), weight, Long::sum);
      leafCounts.merge(frames.get(0), count, Long::sum);
      if (inclusive) {
        // Once per sample: a recursive function is on the stack, not on it twice as often.
        for (String frame : new HashSet<>(frames)) {
          inclusiveWeights.merge(frame, weight, Long::sum);
          inclusiveCounts.merge(frame, count, Long::sum);
        }
      }
    }

    return new RecordingProfile(
        path,
        selected.type() + "/" + selected.unit(),
        sampleCount,
        profile.durationNanos() > 0 ? profile.durationNanos() / 1_000_000_000.0 : 0.0,
        leafCounts,
        inclusiveCounts,
        inclusive,
        0,
        Map.of(),
        0,
        FORMAT,
        selected.unit(),
        kind(profile, selected),
        totalWeight,
        leafWeights,
        inclusiveWeights,
        counts.known,
        false,
        reason + (counts.known ? "" : "; " + counts.why));
  }

  private static int find(List<PprofProfile.ValueType> types, String requested, Path path) {
    String want = requested.toLowerCase(Locale.ROOT);
    int slash = want.indexOf('/');
    String type = slash < 0 ? want : want.substring(0, slash);
    String unit = slash < 0 ? null : want.substring(slash + 1);
    for (int i = 0; i < types.size(); i++) {
      PprofProfile.ValueType t = types.get(i);
      if (t.type().toLowerCase(Locale.ROOT).equals(type)
          && (unit == null || t.unit().toLowerCase(Locale.ROOT).equals(unit))) {
        return i;
      }
    }
    throw new IllegalArgumentException(
        "pprof profile "
            + path
            + " has no sample type "
            + requested
            + "; it has "
            + types.stream().map(t -> t.type() + "/" + t.unit()).collect(Collectors.joining(", ")));
  }

  /** The kind of one of the profile's sample types, resolving {@code samples} by the others. */
  private static SamplerKind kind(PprofProfile.Profile profile, PprofProfile.ValueType type) {
    List<String[]> others = new ArrayList<>();
    if (profile.periodType() != null) {
      others.add(new String[] {profile.periodType().type(), profile.periodType().unit()});
    }
    for (PprofProfile.ValueType other : profile.sampleTypes()) {
      if (other != type) {
        others.add(new String[] {other.type(), other.unit()});
      }
    }
    return SamplerKind.forSampleType(type.type(), type.unit(), others);
  }

  private static long value(PprofProfile.Sample sample, int column) {
    long[] values = sample.values();
    return column < values.length ? values[column] : 0L;
  }

  /** Where a sample's observation count comes from. */
  private record CountSource(boolean known, int column, long period, String why) {
    long count(PprofProfile.Sample sample) {
      long value = value(sample, column);
      return period > 0 ? Math.round((double) value / period) : value;
    }
  }

  private static CountSource countSource(PprofProfile.Profile profile, int column) {
    List<PprofProfile.ValueType> types = profile.sampleTypes();
    String unit = types.get(column).unit();
    if ("count".equalsIgnoreCase(unit)) {
      return new CountSource(true, column, 0, null);
    }
    for (int i = 0; i < types.size(); i++) {
      if (i != column && "count".equalsIgnoreCase(types.get(i).unit())) {
        return new CountSource(true, i, 0, null);
      }
    }
    PprofProfile.ValueType periodType = profile.periodType();
    if (profile.period() > 0 && periodType != null && unit.equalsIgnoreCase(periodType.unit())) {
      return new CountSource(true, column, profile.period(), null);
    }
    return new CountSource(
        false, column, 0, "observation counts unknown: no count column, and no period in " + unit);
  }

  /** The sample's frame keys, leaf first; only the leaf unless {@code all}. */
  private static List<String> frames(
      PprofProfile.Sample sample,
      Map<Long, PprofProfile.Location> locations,
      Map<Long, String> functionKeys,
      boolean all) {
    List<String> frames = new ArrayList<>();
    for (long locationId : sample.locationIds()) {
      PprofProfile.Location location = locations.get(locationId);
      if (location == null) {
        continue;
      }
      for (PprofProfile.Line line : location.lines()) {
        String key = functionKeys.get(line.functionId());
        if (key == null) {
          continue;
        }
        frames.add(key);
        if (!all) {
          return frames;
        }
      }
    }
    return frames;
  }
}

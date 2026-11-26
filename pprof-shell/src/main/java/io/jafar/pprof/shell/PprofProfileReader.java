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
 * Function.name} and {@code Function.systemName}, dotting Java internal names only when the profile
 * {@link #looksJvm looks JVM-originated}. Each {@code Line} of a {@code Location} is its own frame,
 * in the order the pprof shell flattens stacks: the leaf is the first line of the first location.
 *
 * <p>Observation counts come from the source {@link #countSource} picks: the selected column when
 * it counts samples, its producer partner, or the values divided by the period; otherwise they are
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
    SamplerKind kind = kind(profile, selected);
    CountSource counts = countSource(profile, column, kind);

    Map<Long, PprofProfile.Location> locations = new HashMap<>();
    for (PprofProfile.Location location : profile.locations()) {
      locations.put(location.id(), location);
    }
    boolean jvm = looksJvm(profile);
    Map<Long, String> functionKeys = new HashMap<>();
    for (PprofProfile.Function function : profile.functions()) {
      String name = function.name().isEmpty() ? function.systemName() : function.name();
      String key = FrameNames.symbolKey(name, jvm);
      if (key != null) {
        functionKeys.put(function.id(), key);
      }
    }

    // Counts are accumulated raw, in the count source's unit, and divided by the period once per
    // frame and once for the total: rounding each sample would turn many samples shorter than
    // half a period into none.
    Map<String, Long> leafWeights = new LinkedHashMap<>();
    Map<String, Long> inclusiveWeights = new LinkedHashMap<>();
    Map<String, Long> leafCounts = new LinkedHashMap<>();
    Map<String, Long> inclusiveCounts = new LinkedHashMap<>();
    long totalWeight = 0;
    long totalCount = 0;
    boolean inclusive = selection.inclusive();
    for (PprofProfile.Sample sample : profile.samples()) {
      long weight = value(sample, column);
      long count = counts.known ? value(sample, counts.column) : (weight != 0 ? 1 : 0);
      if (weight == 0 && count == 0) {
        continue;
      }
      totalWeight += weight;
      totalCount += count;
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
    if (counts.period > 0) {
      leafCounts.replaceAll((frame, raw) -> counts.perPeriod(raw));
      inclusiveCounts.replaceAll((frame, raw) -> counts.perPeriod(raw));
      totalCount = counts.perPeriod(totalCount);
    }

    return new RecordingProfile(
        path,
        selected.type() + "/" + selected.unit(),
        totalCount,
        profile.durationNanos() > 0 ? profile.durationNanos() / 1_000_000_000.0 : 0.0,
        leafCounts,
        inclusiveCounts,
        inclusive,
        0,
        Map.of(),
        0,
        FORMAT,
        selected.unit(),
        kind,
        totalWeight,
        leafWeights,
        inclusiveWeights,
        counts.known,
        false,
        reason + "; " + counts.why);
  }

  /**
   * Whether the profile came from a JVM, so slashed Java internal names may be dotted: any function
   * has a JVM-only name ({@code <init>}, {@code <clinit>}, {@code $$Lambda}) or a JVM language's
   * source file. Decided per profile, because a Go name such as {@code encoding/json.Marshal} has a
   * Java internal name's shape.
   */
  static boolean looksJvm(PprofProfile.Profile profile) {
    for (PprofProfile.Function function : profile.functions()) {
      if (FrameNames.isJvmSymbol(function.name())
          || FrameNames.isJvmSymbol(function.systemName())
          || FrameNames.isJvmSourceFile(function.filename())) {
        return true;
      }
    }
    return false;
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

  /**
   * Where a sample's observation count comes from: a column, in its unit, divided by {@code period}
   * (once, at the aggregate) when it is positive.
   */
  private record CountSource(boolean known, int column, long period, String why) {
    long perPeriod(long raw) {
      return Math.round((double) raw / period);
    }
  }

  /**
   * The source of observation counts for the selected column, in order:
   *
   * <ol>
   *   <li>the column itself, when its unit is {@code count} and it counts samples: any kind but
   *       allocation and lock, whose {@code count} columns ({@code alloc_objects}, {@code
   *       contentions}) are estimates scaled up from the samples, not trials;
   *   <li>its partner by producer convention: the one {@code samples/count} column (Go's CPU
   *       profile pairs it with {@code cpu/nanoseconds}), or {@code <x>-samples}/{@code
   *       <x>_samples} in {@code count} for {@code <x>-time}/{@code <x>_time};
   *   <li>{@code value / period}, when the period has the selected column's unit and the kind is
   *       not allocation (an allocation period is a byte sampling rate, and the values are already
   *       scaled by it);
   *   <li>otherwise unknown: the comparison then calls nothing significant.
   * </ol>
   */
  private static CountSource countSource(
      PprofProfile.Profile profile, int column, SamplerKind kind) {
    List<PprofProfile.ValueType> types = profile.sampleTypes();
    PprofProfile.ValueType selected = types.get(column);
    String unit = selected.unit();
    boolean scaled = kind.isAllocation() || kind == SamplerKind.LOCK;
    if ("count".equalsIgnoreCase(unit) && !scaled) {
      return new CountSource(true, column, 0, "counts: the column itself");
    }
    int partner = partner(types, column);
    if (partner >= 0) {
      PprofProfile.ValueType p = types.get(partner);
      return new CountSource(
          true, partner, 0, "counts: the " + p.type() + "/" + p.unit() + " column");
    }
    PprofProfile.ValueType periodType = profile.periodType();
    if (!kind.isAllocation()
        && profile.period() > 0
        && periodType != null
        && unit.equalsIgnoreCase(periodType.unit())) {
      return new CountSource(
          true, column, profile.period(), "counts: value / period " + profile.period());
    }
    return new CountSource(
        false,
        column,
        0,
        "observation counts unknown: no sample-count column for "
            + selected.type()
            + "/"
            + unit
            + ", and no period to divide by");
  }

  /** The count column paired with {@code column} by producer convention, or {@code -1}. */
  private static int partner(List<PprofProfile.ValueType> types, int column) {
    int samples = -1;
    int matches = 0;
    for (int i = 0; i < types.size(); i++) {
      if (i != column && isSamplesCount(types.get(i), "samples")) {
        samples = i;
        matches++;
      }
    }
    if (matches == 1) {
      return samples;
    }
    String type = types.get(column).type().toLowerCase(Locale.ROOT);
    for (String suffix : List.of("-time", "_time")) {
      if (type.endsWith(suffix) && type.length() > suffix.length()) {
        String stem = type.substring(0, type.length() - suffix.length());
        String partnerName = stem + suffix.charAt(0) + "samples";
        for (int i = 0; i < types.size(); i++) {
          if (i != column && isSamplesCount(types.get(i), partnerName)) {
            return i;
          }
        }
      }
    }
    return -1;
  }

  private static boolean isSamplesCount(PprofProfile.ValueType type, String name) {
    return type.type().equalsIgnoreCase(name) && "count".equalsIgnoreCase(type.unit());
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

package io.jafar.otlp.shell;

import io.jafar.otlp.api.OtlpParser;
import io.jafar.otlp.api.OtlpProfile;
import io.jafar.otlp.shell.otlppath.OtlpPathEvaluator;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads an OTLP profiles file for a comparison: the profiles of one sample type as the weights, and
 * observation counts for the significance test where they can be derived. The OTLP counterpart of
 * {@code PprofProfileReader}, with the same rules.
 *
 * <p>An OTLP file holds any number of profiles, each with one sample type, over one shared
 * dictionary. The sample types a file offers are its profiles' distinct {@code type/unit} pairs;
 * the selected one reads every profile of that type (a collector may batch several), and the
 * default is the first CPU sample type, else the first. async-profiler writes one profile per file.
 *
 * <p>A sample's weight is {@link OtlpPathEvaluator#value}: the sum of its values, or its timestamp
 * count when it has none. Frames are keyed by {@link FrameNames#symbolKey} of the first non-empty
 * of {@code Function.name} and {@code Function.systemName}, dotting Java internal names only when
 * the selected profiles {@link #looksJvm look JVM-originated}. Each {@code Line} of a {@code
 * Location} is its own frame, leaf first, as OtlpPath flattens stacks. Table indices are absolute:
 * index 0 of every table is its zero value.
 *
 * <p>Observation counts come from {@link #countSource}; otherwise they are unknown, and the
 * comparison reports shares without calling any change significant. OTLP records no stack
 * truncation, so the profile says truncation is unknown.
 */
public final class OtlpProfileReader implements ProfileReader {

  /** The format name. */
  public static final String FORMAT = "otlp";

  @Override
  public String format() {
    return FORMAT;
  }

  @Override
  public List<SampleTypeInfo> sampleTypes(Path file) throws Exception {
    return sampleTypes(OtlpParser.parse(file));
  }

  /** The distinct sample types of an already parsed file, in the order its profiles first use. */
  public static List<SampleTypeInfo> sampleTypes(OtlpProfile.ProfilesData data) {
    List<SampleTypeInfo> types = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (OtlpProfile.Profile profile : data.profiles()) {
      OtlpProfile.ValueType type = sampleType(profile);
      if (seen.add(label(type))) {
        types.add(new SampleTypeInfo(type.type(), type.unit(), kind(data, profile)));
      }
    }
    return types;
  }

  @Override
  public RecordingProfile read(Path file, ProfileSelection selection) throws Exception {
    return read(OtlpParser.parse(file), file, selection);
  }

  /**
   * Reads an already parsed file.
   *
   * @param path where it was read from, for the description; may be {@code null}
   * @throws IllegalArgumentException when the selection names a sample type the file lacks, or the
   *     file holds no profile at all
   */
  public static RecordingProfile read(
      OtlpProfile.ProfilesData data, Path path, ProfileSelection selection) {
    if (data.profiles().isEmpty()) {
      throw new IllegalArgumentException("OTLP file " + path + " holds no profile");
    }
    String reason;
    OtlpProfile.Profile first;
    if (selection.sampleType() != null) {
      first = find(data, selection.sampleType(), path);
      reason = "requested";
    } else {
      first = null;
      for (OtlpProfile.Profile profile : data.profiles()) {
        if (kind(data, profile) == SamplerKind.CPU) {
          first = profile;
          break;
        }
      }
      if (first != null) {
        reason = "default: the first CPU sample type";
      } else {
        first = data.profiles().get(0);
        reason = "default: no CPU sample type, so the first sample type";
      }
    }
    OtlpProfile.ValueType selected = sampleType(first);
    String label = label(selected);
    List<OtlpProfile.Profile> profiles = new ArrayList<>();
    for (OtlpProfile.Profile profile : data.profiles()) {
      if (label(sampleType(profile)).equals(label)) {
        profiles.add(profile);
      }
    }
    SamplerKind kind = kind(data, first);
    if (profiles.size() > 1) {
      reason += "; " + profiles.size() + " profiles of " + label + " read together";
    }

    OtlpProfile.Dictionary dict = data.dictionary();
    boolean jvm = looksJvm(dict, profiles);
    Map<Integer, String> functionKeys = new HashMap<>();
    List<OtlpProfile.Function> functions = dict.functionTable();
    for (int i = 1; i < functions.size(); i++) {
      OtlpProfile.Function function = functions.get(i);
      String name = function.name().isEmpty() ? function.systemName() : function.name();
      String key = FrameNames.symbolKey(name, jvm);
      if (key != null) {
        functionKeys.put(i, key);
      }
    }

    Map<String, Long> leafWeights = new LinkedHashMap<>();
    Map<String, Long> inclusiveWeights = new LinkedHashMap<>();
    Map<String, Long> leafCounts = new LinkedHashMap<>();
    Map<String, Long> inclusiveCounts = new LinkedHashMap<>();
    long totalWeight = 0;
    long totalCount = 0;
    boolean countsKnown = true;
    Set<String> whys = new LinkedHashSet<>();
    boolean inclusive = selection.inclusive();
    for (OtlpProfile.Profile profile : profiles) {
      CountSource counts = countSource(profile, kind);
      countsKnown &= counts.known();
      whys.add(counts.why());
      // Counts are accumulated raw, in the count source's unit, and divided by the period once per
      // frame and once for the total of each profile: rounding each sample would turn many samples
      // shorter than half a period into none.
      Map<String, Long> profileLeaf = new LinkedHashMap<>();
      Map<String, Long> profileInclusive = new LinkedHashMap<>();
      long profileCount = 0;
      for (OtlpProfile.Sample sample : profile.samples()) {
        long weight = OtlpPathEvaluator.value(sample);
        long count = counts.count(sample, weight);
        if (weight == 0 && count == 0) {
          continue;
        }
        totalWeight += weight;
        profileCount += count;
        List<String> frames = frames(sample, dict, functionKeys, inclusive);
        if (frames.isEmpty()) {
          continue; // still a sample: it counts towards the total, under no frame
        }
        leafWeights.merge(frames.get(0), weight, Long::sum);
        profileLeaf.merge(frames.get(0), count, Long::sum);
        if (inclusive) {
          // Once per sample: a recursive function is on the stack, not on it twice as often.
          for (String frame : new HashSet<>(frames)) {
            inclusiveWeights.merge(frame, weight, Long::sum);
            profileInclusive.merge(frame, count, Long::sum);
          }
        }
      }
      if (counts.period() > 0) {
        profileLeaf.replaceAll((frame, raw) -> counts.perPeriod(raw));
        profileInclusive.replaceAll((frame, raw) -> counts.perPeriod(raw));
        profileCount = counts.perPeriod(profileCount);
      }
      profileLeaf.forEach((frame, c) -> leafCounts.merge(frame, c, Long::sum));
      profileInclusive.forEach((frame, c) -> inclusiveCounts.merge(frame, c, Long::sum));
      totalCount += profileCount;
    }
    if (!countsKnown) {
      // As for pprof: without observations, sampleCount is the number of sample records.
      totalCount = profiles.stream().mapToLong(p -> p.samples().size()).sum();
    }

    return new RecordingProfile(
        path,
        label,
        totalCount,
        durationSeconds(profiles),
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
        countsKnown,
        false,
        reason + "; " + String.join("; ", whys));
  }

  /**
   * The span the profiles cover, in seconds: from the earliest {@code time_unix_nano} to the latest
   * end ({@code time_unix_nano + duration_nano}), or the longest {@code duration_nano} when a
   * profile has no time; failing both, the span of the samples' timestamps; else {@code 0}.
   */
  private static double durationSeconds(List<OtlpProfile.Profile> profiles) {
    long start = Long.MAX_VALUE;
    long end = Long.MIN_VALUE;
    long longest = 0;
    boolean timed = true;
    for (OtlpProfile.Profile p : profiles) {
      longest = Math.max(longest, p.durationNano());
      if (p.timeUnixNano() <= 0 || p.durationNano() <= 0) {
        timed = false;
      } else {
        start = Math.min(start, p.timeUnixNano());
        end = Math.max(end, p.timeUnixNano() + p.durationNano());
      }
    }
    long nanos = timed ? end - start : longest;
    if (nanos <= 0) {
      long first = Long.MAX_VALUE;
      long last = Long.MIN_VALUE;
      for (OtlpProfile.Profile p : profiles) {
        for (OtlpProfile.Sample s : p.samples()) {
          for (long t : s.timestampsUnixNano()) {
            first = Math.min(first, t);
            last = Math.max(last, t);
          }
        }
      }
      nanos = last > first ? last - first : 0;
    }
    return nanos / 1_000_000_000.0;
  }

  /**
   * Whether the selected profiles came from a JVM, so slashed Java internal names may be dotted:
   * any function their stacks reach has a JVM-only name ({@code <init>}, {@code <clinit>}, {@code
   * $$Lambda}) or a JVM language's source file. Decided over the profiles read, not the whole
   * dictionary, which a collector may share between processes in different languages.
   */
  static boolean looksJvm(OtlpProfile.Dictionary dict, List<OtlpProfile.Profile> profiles) {
    Set<Integer> seenStacks = new HashSet<>();
    for (OtlpProfile.Profile profile : profiles) {
      for (OtlpProfile.Sample sample : profile.samples()) {
        if (!seenStacks.add(sample.stackIndex())) {
          continue;
        }
        OtlpProfile.Stack stack = at(dict.stackTable(), sample.stackIndex());
        if (stack == null) {
          continue;
        }
        for (int locationIndex : stack.locationIndices()) {
          OtlpProfile.Location location = at(dict.locationTable(), locationIndex);
          if (location == null) {
            continue;
          }
          for (OtlpProfile.Line line : location.lines()) {
            OtlpProfile.Function function = at(dict.functionTable(), line.functionIndex());
            if (function != null
                && (FrameNames.isJvmSymbol(function.name())
                    || FrameNames.isJvmSymbol(function.systemName())
                    || FrameNames.isJvmSourceFile(function.filename()))) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  private static OtlpProfile.Profile find(
      OtlpProfile.ProfilesData data, String requested, Path path) {
    String want = requested.toLowerCase(Locale.ROOT);
    int slash = want.indexOf('/');
    String type = slash < 0 ? want : want.substring(0, slash);
    String unit = slash < 0 ? null : want.substring(slash + 1);
    for (OtlpProfile.Profile profile : data.profiles()) {
      OtlpProfile.ValueType t = sampleType(profile);
      if (t.type().toLowerCase(Locale.ROOT).equals(type)
          && (unit == null || t.unit().toLowerCase(Locale.ROOT).equals(unit))) {
        return profile;
      }
    }
    throw new IllegalArgumentException(
        "OTLP file "
            + path
            + " has no sample type "
            + requested
            + "; it has "
            + sampleTypes(data).stream()
                .map(SampleTypeInfo::label)
                .collect(Collectors.joining(", ")));
  }

  private static OtlpProfile.ValueType sampleType(OtlpProfile.Profile profile) {
    return Objects.requireNonNullElse(profile.sampleType(), new OtlpProfile.ValueType("", ""));
  }

  private static String label(OtlpProfile.ValueType type) {
    return type.type() + "/" + type.unit();
  }

  /**
   * A profile's kind, resolving {@code samples} and {@code perf_events} by its period type and by
   * the file's other sample types.
   */
  private static SamplerKind kind(OtlpProfile.ProfilesData data, OtlpProfile.Profile profile) {
    OtlpProfile.ValueType type = sampleType(profile);
    List<String[]> others = new ArrayList<>();
    if (profile.periodType() != null) {
      others.add(new String[] {profile.periodType().type(), profile.periodType().unit()});
    }
    for (OtlpProfile.Profile other : data.profiles()) {
      if (other != profile) {
        OtlpProfile.ValueType t = sampleType(other);
        others.add(new String[] {t.type(), t.unit()});
      }
    }
    return SamplerKind.forSampleType(type.type(), type.unit(), others);
  }

  /** How a sample's observation count is derived. */
  private enum Rule {
    /** The value itself counts samples. */
    VALUE,
    /** One observation per timestamp. */
    TIMESTAMPS,
    /** The value divided by the period, once at the aggregate. */
    PERIOD,
    /** Not derivable. */
    UNKNOWN
  }

  /** Where a profile's observation counts come from. */
  private record CountSource(Rule rule, long period, String why) {
    boolean known() {
      return rule != Rule.UNKNOWN;
    }

    long count(OtlpProfile.Sample sample, long weight) {
      return switch (rule) {
        case VALUE, PERIOD -> weight;
        case TIMESTAMPS -> sample.timestampsUnixNano().size();
        case UNKNOWN -> weight != 0 ? 1 : 0;
      };
    }

    long perPeriod(long raw) {
      return Math.round((double) raw / period);
    }
  }

  /**
   * The source of a profile's observation counts, as {@code PprofProfileReader.countSource} decides
   * for a pprof column, in order:
   *
   * <ol>
   *   <li>the value itself, when its unit is {@code count} and it counts samples: any kind but
   *       allocation and lock, whose counts producers scale up from the samples (async-profiler's
   *       agent writes {@code object_sampler/count}, sampled allocations, and {@code
   *       lock_tracer/count});
   *   <li>one per timestamp, when every sample of the profile has timestamps and the kind is
   *       neither allocation nor lock: opentelemetry-proto v1.10.0 says a sample's timestamps are
   *       its events, so a {@code cpu/nanoseconds} profile with timestamps counts its samples;
   *   <li>{@code value / period}, when the period has the sample type's unit and the kind is
   *       neither allocation nor lock;
   *   <li>otherwise unknown: the comparison then calls nothing significant.
   * </ol>
   *
   * There is no partner column as in pprof: each OTLP profile has one sample type, and its samples
   * cannot be paired with another profile's.
   */
  private static CountSource countSource(OtlpProfile.Profile profile, SamplerKind kind) {
    OtlpProfile.ValueType selected = sampleType(profile);
    String unit = selected.unit();
    String label = label(selected);
    boolean scaled = kind.isAllocation() || kind == SamplerKind.LOCK;
    if (scaled) {
      return new CountSource(
          Rule.UNKNOWN,
          0,
          "observation counts unknown: "
              + label
              + (kind == SamplerKind.LOCK ? " is a lock" : " is an allocation")
              + " measure, which producers scale up from the sampled events (so its period does"
              + " not give counts either)");
    }
    if ("count".equalsIgnoreCase(unit)) {
      return new CountSource(Rule.VALUE, 0, "counts: the sample type itself");
    }
    boolean allTimestamped = !profile.samples().isEmpty();
    for (OtlpProfile.Sample sample : profile.samples()) {
      if (sample.timestampsUnixNano().isEmpty()) {
        allTimestamped = false;
        break;
      }
    }
    if (allTimestamped) {
      return new CountSource(Rule.TIMESTAMPS, 0, "counts: one per sample timestamp");
    }
    OtlpProfile.ValueType periodType = profile.periodType();
    if (profile.period() > 0 && periodType != null && unit.equalsIgnoreCase(periodType.unit())) {
      return new CountSource(
          Rule.PERIOD, profile.period(), "counts: value / period " + profile.period());
    }
    return new CountSource(
        Rule.UNKNOWN,
        0,
        "observation counts unknown: "
            + label
            + " is not a count, its samples have no timestamps, and no period to divide by");
  }

  /** The sample's frame keys, leaf first; only the leaf unless {@code all}. */
  private static List<String> frames(
      OtlpProfile.Sample sample,
      OtlpProfile.Dictionary dict,
      Map<Integer, String> functionKeys,
      boolean all) {
    List<String> frames = new ArrayList<>();
    OtlpProfile.Stack stack = at(dict.stackTable(), sample.stackIndex());
    if (stack == null) {
      return frames;
    }
    for (int locationIndex : stack.locationIndices()) {
      OtlpProfile.Location location = at(dict.locationTable(), locationIndex);
      if (location == null) {
        continue;
      }
      for (OtlpProfile.Line line : location.lines()) {
        String key = functionKeys.get(line.functionIndex());
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

  /** Entry {@code index} of a dictionary table; {@code null} for the zero value or out of range. */
  private static <T> T at(List<T> table, int index) {
    return index > 0 && index < table.size() ? table.get(index) : null;
  }
}

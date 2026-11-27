package io.jafar.shell.core.compare;

import java.util.List;
import java.util.Locale;

/**
 * What a profile's samples measure, whatever the format. Two profiles compare share for share only
 * when their samplers measure the same thing: a CPU sampler and a wall-clock sampler see different
 * threads, and an allocation profile's shares are of bytes or objects, not of time.
 */
public enum SamplerKind {
  /** Threads on CPU. */
  CPU,
  /** Threads in any state, wall clock. */
  WALL,
  /** Bytes allocated or in use. */
  ALLOC_BYTES,
  /** Objects allocated or in use, or allocation events. */
  ALLOC_COUNT,
  /** Lock contention or waiting. */
  LOCK,
  /** Not recognised: the comparison runs, with a note. */
  UNKNOWN;

  /** Whether this is one of the two allocation kinds. */
  public boolean isAllocation() {
    return this == ALLOC_BYTES || this == ALLOC_COUNT;
  }

  /** Whether this is CPU or wall-clock time. */
  public boolean isTime() {
    return this == CPU || this == WALL;
  }

  /** The kind of a JFR event type read as samples. */
  public static SamplerKind forJfrEventType(String eventType) {
    if (eventType == null) {
      return UNKNOWN;
    }
    return switch (eventType) {
      case "jdk.ExecutionSample", "jdk.CPUTimeSample", "datadog.ExecutionSample" -> CPU;
      case "jdk.NativeMethodSample", "datadog.MethodSample" -> WALL;
      case "jdk.ObjectAllocationSample",
          "jdk.ObjectAllocationInNewTLAB",
          "jdk.ObjectAllocationOutsideTLAB",
          "datadog.ObjectSample" ->
          ALLOC_COUNT;
      case "jdk.JavaMonitorEnter", "jdk.JavaMonitorWait", "jdk.ThreadPark" -> LOCK;
      default -> UNKNOWN;
    };
  }

  /**
   * The kind of a pprof (or OTLP) sample type, by its name and unit: a name starting with {@code
   * cpu} ({@code cpu}, {@code cpu-time}, {@code cpu_time}, {@code cpu-samples}) is {@link #CPU},
   * one starting with {@code wall} ({@code wall}, {@code wall-time}, {@code wall_time}) is {@link
   * #WALL}; {@code alloc_space}/{@code inuse_space} (or any {@code alloc*} or {@code malloc} in
   * bytes) are {@link #ALLOC_BYTES}, {@code alloc_objects}/{@code inuse_objects} (or any other
   * {@code alloc*} or {@code malloc}, as async-profiler's {@code allocations/count}) {@link
   * #ALLOC_COUNT}, and {@code contentions}, {@code delay}, {@code lock}, {@code locks} {@link
   * #LOCK}. Matching ignores case. {@code samples} names a count of samples of whatever the profile
   * samples, so it takes the kind of the profile's period type, or of another sample type in the
   * profile, when that one is CPU or wall.
   *
   * @param others the profile's other sample types and its period type as {@code {type, unit}}
   *     pairs, to resolve {@code samples}; may be empty
   */
  public static SamplerKind forSampleType(String type, String unit, List<String[]> others) {
    SamplerKind own = byName(type, unit);
    if (own != UNKNOWN || !"samples".equals(lower(type))) {
      return own;
    }
    for (String[] other : others) {
      SamplerKind kind = byName(other[0], other[1]);
      if (kind.isTime()) {
        return kind;
      }
    }
    return UNKNOWN;
  }

  private static SamplerKind byName(String type, String unit) {
    String t = lower(type);
    String u = lower(unit);
    // Producers spell the same sampler several ways: cpu, cpu-time, cpu_time, cpu-samples (and
    // jfr2pprof's own names); wall, wall-time, wall_time. A name starting with "cpu" or "wall" is
    // that kind, whatever its unit: the unit says how it is weighed, not what was sampled.
    if (t.startsWith("cpu")) {
      return CPU;
    }
    if (t.startsWith("wall")) {
      return WALL;
    }
    if (t.equals("alloc_space") || t.equals("inuse_space")) {
      return ALLOC_BYTES;
    }
    if (t.equals("alloc_objects") || t.equals("inuse_objects")) {
      return ALLOC_COUNT;
    }
    // async-profiler's jfrconv (4.1) names its columns locks, allocations and malloc, in count or,
    // with --total, in nanoseconds or bytes. Read by name only, allocations/count was UNKNOWN, so
    // an allocation profile compared against a CPU one instead of being refused.
    if (t.equals("contentions") || t.equals("delay") || t.equals("lock") || t.equals("locks")) {
      return LOCK;
    }
    if (t.startsWith("alloc") || t.equals("malloc")) {
      return u.equals("bytes") ? ALLOC_BYTES : ALLOC_COUNT;
    }
    return UNKNOWN;
  }

  private static String lower(String s) {
    return s == null ? "" : s.toLowerCase(Locale.ROOT);
  }
}

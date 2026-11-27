package io.jafar.shell.core.compare;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import org.junit.jupiter.api.Test;

/** How sample-type names map to sampler kinds, including the spellings producers use. */
class SamplerKindTest {

  @Test
  void cpuVariantsAreCpu() {
    for (String name : List.of("cpu", "cpu-time", "cpu_time", "cpu-samples", "CPU", "Cpu_Time")) {
      assertSame(SamplerKind.CPU, kind(name, "nanoseconds"), name);
    }
    assertSame(SamplerKind.CPU, kind("cpu-samples", "count"));
  }

  @Test
  void wallVariantsAreWall() {
    for (String name : List.of("wall", "wall-time", "wall_time", "WALL")) {
      assertSame(SamplerKind.WALL, kind(name, "nanoseconds"), name);
    }
  }

  @Test
  void allocationAndLockNamesKeepTheirKinds() {
    assertSame(SamplerKind.ALLOC_BYTES, kind("alloc_space", "bytes"));
    assertSame(SamplerKind.ALLOC_BYTES, kind("inuse_space", "bytes"));
    assertSame(SamplerKind.ALLOC_BYTES, kind("alloc-bytes", "bytes"));
    assertSame(SamplerKind.ALLOC_COUNT, kind("alloc_objects", "count"));
    assertSame(SamplerKind.ALLOC_COUNT, kind("inuse_objects", "count"));
    assertSame(SamplerKind.LOCK, kind("contentions", "count"));
    assertSame(SamplerKind.LOCK, kind("delay", "nanoseconds"));
    assertSame(SamplerKind.UNKNOWN, kind("goroutine", "count"));
  }

  @Test
  void asyncProfilerColumnNamesHaveTheirKinds() {
    // jfrconv 4.1's names for --alloc/--live, --lock and --nativemem, without and with --total.
    assertSame(SamplerKind.ALLOC_COUNT, kind("allocations", "count"));
    assertSame(SamplerKind.ALLOC_BYTES, kind("allocations", "bytes"));
    assertSame(SamplerKind.LOCK, kind("locks", "count"));
    assertSame(SamplerKind.LOCK, kind("locks", "nanoseconds"));
    assertSame(SamplerKind.ALLOC_COUNT, kind("malloc", "count"));
    assertSame(SamplerKind.ALLOC_BYTES, kind("malloc", "bytes"));
  }

  @Test
  void asyncProfilerEngineNamesHaveTheirKinds() {
    // The 4.6 agent's OTLP sample types: the engine name, in count, with the period type beside.
    assertSame(SamplerKind.CPU, kind("itimer", "count"));
    assertSame(SamplerKind.CPU, kind("ctimer", "count"));
    assertSame(SamplerKind.WALL, kind("wall", "count"));
    assertSame(SamplerKind.ALLOC_COUNT, kind("object_sampler", "count"));
    assertSame(SamplerKind.ALLOC_COUNT, kind("malloc_tracer", "count"));
    assertSame(SamplerKind.LOCK, kind("lock_tracer", "count"));
    // perf_events is CPU only with its CPU-clock period type; a hardware counter is not.
    assertSame(
        SamplerKind.CPU,
        SamplerKind.forSampleType(
            "perf_events", "count", List.<String[]>of(new String[] {"perf_events", "ns"})));
    assertSame(SamplerKind.UNKNOWN, kind("perf_events", "count"));
    assertSame(
        SamplerKind.UNKNOWN,
        SamplerKind.forSampleType(
            "perf_events", "count", List.<String[]>of(new String[] {"perf_events", "events"})));
  }

  @Test
  void samplesTakesTheKindOfItsPartner() {
    assertSame(
        SamplerKind.CPU,
        SamplerKind.forSampleType(
            "samples", "count", List.<String[]>of(new String[] {"cpu-time", "nanoseconds"})));
    assertSame(
        SamplerKind.UNKNOWN, SamplerKind.forSampleType("samples", "count", List.<String[]>of()));
  }

  private static SamplerKind kind(String type, String unit) {
    return SamplerKind.forSampleType(type, unit, List.of());
  }
}

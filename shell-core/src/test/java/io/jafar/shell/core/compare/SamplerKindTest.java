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

package io.jafar.jfr2pprof;

import static org.assertj.core.api.Assertions.assertThat;

import io.jafar.jfr2pprof.config.ValueTypePair;
import io.jafar.jfr2pprof.proto.PprofBuilder;
import io.jafar.pprof.shell.PprofProfile;
import io.jafar.pprof.shell.PprofReader;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PprofBuilderRoundTripTest {

  @TempDir Path tempDir;

  private PprofProfile.Profile buildAndRead(PprofBuilder builder, List<ValueTypePair> valueTypes)
      throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    builder.build(valueTypes, 0, 1_000_000L, true, baos);
    Path tmp = tempDir.resolve("test.pprof");
    Files.write(tmp, baos.toByteArray());
    return PprofReader.read(tmp);
  }

  @Test
  void testBasicRoundTrip() throws Exception {
    PprofBuilder b = new PprofBuilder(1);

    long fn1 = b.internFunction("com.A", "a", 0);
    long loc1 = b.internLocation(fn1, -1);

    long fn2 = b.internFunction("com.B", "b", 0);
    long loc2 = b.internLocation(fn2, -1);

    b.addSample(new long[] {loc1}, 0, new long[] {42L}, List.of());
    b.addSample(new long[] {loc2}, 0, new long[] {7L}, List.of());

    List<ValueTypePair> valueTypes = List.of(new ValueTypePair("cpu-time", "nanoseconds"));
    PprofProfile.Profile p = buildAndRead(b, valueTypes);

    assertThat(p.sampleTypes()).hasSize(1);
    assertThat(p.samples()).hasSize(2);
    assertThat(p.stringTable().get(0)).isEmpty();
    assertThat(p.durationNanos()).isEqualTo(1_000_000L);

    // Verify individual sample values
    long val1 = p.samples().get(0).values().get(0);
    long val2 = p.samples().get(1).values().get(0);
    assertThat(val1 + val2).isEqualTo(49L); // 42 + 7
  }

  @Test
  void testMerge() throws Exception {
    PprofBuilder b = new PprofBuilder(1);

    long fn = b.internFunction("com.A", "a", 0);
    long loc = b.internLocation(fn, -1);

    b.addSample(new long[] {loc}, 0, new long[] {42L}, List.of());
    b.addSample(new long[] {loc}, 0, new long[] {10L}, List.of());

    // Same stack -> merged into one sample
    assertThat(b.sampleCount()).isEqualTo(1);

    List<ValueTypePair> valueTypes = List.of(new ValueTypePair("cpu-time", "nanoseconds"));
    PprofProfile.Profile p = buildAndRead(b, valueTypes);

    assertThat(p.samples()).hasSize(1);
    assertThat(p.samples().get(0).values().get(0)).isEqualTo(52L); // 42 + 10
  }

  @Test
  void testMultiProfileValueWidth() throws Exception {
    PprofBuilder b = new PprofBuilder(2);

    long fn = b.internFunction("X", "x", 0);
    long loc = b.internLocation(fn, -1);

    // First profile column (index 0), one value
    b.addSample(new long[] {loc}, 0, new long[] {100L}, List.of());
    // Second profile column (index 1), same stack -> merge
    b.addSample(new long[] {loc}, 1, new long[] {200L}, List.of());

    assertThat(b.sampleCount()).isEqualTo(1);

    List<ValueTypePair> valueTypes =
        List.of(
            new ValueTypePair("cpu-time", "nanoseconds"),
            new ValueTypePair("alloc-space", "bytes"));

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    b.build(valueTypes, 0, 1_000_000L, true, baos);
    Path tmp = tempDir.resolve("multi.pprof");
    Files.write(tmp, baos.toByteArray());
    PprofProfile.Profile p = PprofReader.read(tmp);

    assertThat(p.sampleTypes()).hasSize(2);
    assertThat(p.samples()).hasSize(1);

    List<Long> vals = p.samples().get(0).values();
    assertThat(vals).hasSize(2);
    assertThat(vals.get(0)).isEqualTo(100L);
    assertThat(vals.get(1)).isEqualTo(200L);
  }
}

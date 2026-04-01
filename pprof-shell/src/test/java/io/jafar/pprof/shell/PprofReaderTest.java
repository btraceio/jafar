package io.jafar.pprof.shell;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link PprofReader}: round-trip encoding and parsing of synthetic profiles. */
class PprofReaderTest {

  @TempDir Path tempDir;

  /**
   * Builds a profile with known values and verifies that the reader reconstructs them correctly.
   */
  @Test
  void roundTripBasicProfile() throws Exception {
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    int cpu = b.addString("cpu");
    int ns = b.addString("nanoseconds");
    int fnName = b.addString("com.example.Foo.bar");
    int filename = b.addString("Foo.java");
    int threadKey = b.addString("thread");
    int mainStr = b.addString("main");

    b.addSampleType(cpu, ns);
    b.setDurationNanos(5_000_000_000L);

    long fn1 = b.addFunction(fnName, filename);
    long loc1 = b.addLocation(fn1, 42);

    b.addSample(List.of(loc1), List.of(1_234_567L), List.of(new long[] {threadKey, mainStr, 0, 0}));

    Path file = b.write(tempDir);
    PprofProfile.Profile profile = PprofReader.read(file);

    // sample types
    assertEquals(1, profile.sampleTypes().size());
    assertEquals("cpu", profile.sampleTypes().get(0).type());
    assertEquals("nanoseconds", profile.sampleTypes().get(0).unit());

    // duration
    assertEquals(5_000_000_000L, profile.durationNanos());

    // samples
    assertEquals(1, profile.samples().size());
    PprofProfile.Sample sample = profile.samples().get(0);
    assertEquals(1, sample.values().size());
    assertEquals(1_234_567L, sample.values().get(0));
    assertEquals(1, sample.locationIds().size());

    // labels
    assertEquals(1, sample.labels().size());
    PprofProfile.Label label = sample.labels().get(0);
    assertEquals("thread", label.key());
    assertEquals("main", label.str());

    // functions
    assertEquals(1, profile.functions().size());
    PprofProfile.Function fn = profile.functions().get(0);
    assertEquals("com.example.Foo.bar", fn.name());
    assertEquals("Foo.java", fn.filename());

    // locations
    assertEquals(1, profile.locations().size());
    PprofProfile.Location loc = profile.locations().get(0);
    assertEquals(1, loc.lines().size());
    assertEquals(42L, loc.lines().get(0).lineNumber());
    assertEquals(fn.id(), loc.lines().get(0).functionId());
  }

  @Test
  void multipleSampleTypes() throws Exception {
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    int cpu = b.addString("cpu");
    int ns = b.addString("nanoseconds");
    int alloc = b.addString("alloc_objects");
    int count = b.addString("count");

    b.addSampleType(cpu, ns);
    b.addSampleType(alloc, count);

    long fn1 = b.addFunction(b.addString("com.Foo.bar"), b.addString("Foo.java"));
    long loc1 = b.addLocation(fn1, 1);

    b.addSample(List.of(loc1), List.of(1_000_000L, 3L), List.of());

    Path file = b.write(tempDir);
    PprofProfile.Profile profile = PprofReader.read(file);

    assertEquals(2, profile.sampleTypes().size());
    assertEquals("cpu", profile.sampleTypes().get(0).type());
    assertEquals("alloc_objects", profile.sampleTypes().get(1).type());

    PprofProfile.Sample sample = profile.samples().get(0);
    assertEquals(2, sample.values().size());
    assertEquals(1_000_000L, sample.values().get(0));
    assertEquals(3L, sample.values().get(1));
  }

  @Test
  void multipleSamplesAndLocations() throws Exception {
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    int cpu = b.addString("cpu");
    int ns = b.addString("nanoseconds");
    b.addSampleType(cpu, ns);

    long fn1 = b.addFunction(b.addString("a.A.foo"), b.addString("A.java"));
    long fn2 = b.addFunction(b.addString("b.B.bar"), b.addString("B.java"));
    long loc1 = b.addLocation(fn1, 10);
    long loc2 = b.addLocation(fn2, 20);
    long loc3 = b.addLocation(fn1, 11);

    b.addSample(List.of(loc1, loc2), List.of(100L), List.of());
    b.addSample(List.of(loc3, loc2), List.of(200L), List.of());

    Path file = b.write(tempDir);
    PprofProfile.Profile profile = PprofReader.read(file);

    assertEquals(2, profile.samples().size());
    assertEquals(2, profile.functions().size());
    assertEquals(3, profile.locations().size());

    // First sample references loc1 and loc2
    List<Long> ids0 = profile.samples().get(0).locationIds();
    assertEquals(2, ids0.size());
  }

  @Test
  void emptyStringAtIndexZero() throws Exception {
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    int cpu = b.addString("cpu");
    int ns = b.addString("nanoseconds");
    b.addSampleType(cpu, ns);

    Path file = b.write(tempDir);
    PprofProfile.Profile profile = PprofReader.read(file);

    // The string table always starts with "" at index 0
    assertFalse(profile.stringTable().isEmpty());
    assertEquals("", profile.stringTable().get(0));
  }

  @Test
  void numericLabel() throws Exception {
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    int cpu = b.addString("cpu");
    int ns = b.addString("nanoseconds");
    b.addSampleType(cpu, ns);

    int goroutineKey = b.addString("goroutine");
    long fn1 = b.addFunction(b.addString("main.run"), b.addString("main.go"));
    long loc1 = b.addLocation(fn1, 1);

    // numeric label: goroutine=42
    b.addSample(List.of(loc1), List.of(500L), List.of(new long[] {goroutineKey, 0, 42L, 0}));

    Path file = b.write(tempDir);
    PprofProfile.Profile profile = PprofReader.read(file);

    PprofProfile.Sample sample = profile.samples().get(0);
    assertEquals(1, sample.labels().size());
    PprofProfile.Label label = sample.labels().get(0);
    assertEquals("goroutine", label.key());
    assertEquals(42L, label.num());
    assertNull(label.str()); // str should be empty/null for numeric label
  }
}

package io.jafar.pprof.alloc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.pprof.MinimalPprofBuilder;
import io.jafar.pprof.api.PprofParser;
import io.jafar.pprof.api.PprofProfile;
import io.jafar.testsupport.AllocationBudgets;
import io.jafar.testsupport.AllocationMeter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Allocation budget of the pprof entry point ({@link PprofParser#parse(Path)}) on the {@code
 * MinimalPprofBuilder} basic profile (AC-3.1).
 *
 * <p>The parse is single-threaded — {@code PprofReader} is a hand-rolled single-phase decoder with
 * no executor, no worker thread and no parallel stream anywhere in {@code pprof-parser/src/main} —
 * so the normal {@link AllocationMeter} semantics (calling-thread delta) measure the whole parse,
 * and the test runs in the module's normal {@code test} task (D9), mirroring the hdump cell.
 *
 * <p>Determinism: the fixture is built once, outside any measurement — the builder writes to a temp
 * file whose name varies per run, but the profile bytes are fixed by the construction below — and
 * two warm-up parses are discarded before the measured parse (first parse pays the lazy
 * gzip/decoder initialization).
 */
class AllocationBudgetTest {

  /** Generated once per class: the basic round-trip profile of {@code PprofParserTest}. */
  @TempDir static Path tempDir;

  private static Path fixture;

  /** Budget key without the JDK suffix; {@link AllocationBudgets#check} appends {@code jdk21}. */
  private static final String KEY = "pprof-parser.parse.minimal";

  @BeforeAll
  static void buildFixture() throws IOException {
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
    fixture = b.write(tempDir);
  }

  @Test
  void pprofParseStaysWithinAllocationBudget() throws Exception {
    // Two discarded warm-up parses: the first pays lazy gzip/decoder initialization, the second
    // one more lazy step observed on JDK 21 (kb/a4-jdk8-alloc, same pattern as parser-core).
    for (int i = 0; i < 2; i++) {
      assertPayload(PprofParser.parse(fixture), "warm-up parse " + i);
    }

    AllocationMeter meter = AllocationMeter.start();
    PprofProfile.Profile profile = PprofParser.parse(fixture);
    long bytes = meter.stop();
    System.out.println(
        "alloc "
            + KEY
            + "."
            + AllocationBudgets.jdkKey()
            + " aggregate="
            + bytes
            + " poolThreads="
            + meter.poolThreadIds().size());

    // Payload asserted AFTER the meter (R9: the verdict must not rest on a parse that returned
    // nothing), but outside the measurement — the assertions themselves allocate.
    assertPayload(profile, "measured parse");
    AllocationBudgets.check(KEY, bytes);
  }

  /** The basic-profile payload, mirroring {@code PprofParserTest#roundTripBasicProfile}. */
  private static void assertPayload(PprofProfile.Profile profile, String when) {
    assertEquals(1, profile.sampleTypes().size(), when + ": basic profile changed?");
    assertEquals("cpu", profile.sampleTypes().get(0).type(), when);
    assertEquals(1, profile.samples().size(), when);
    PprofProfile.Sample sample = profile.samples().get(0);
    assertArrayEquals(new long[] {1_234_567L}, sample.values(), when);
    assertEquals(1, sample.labels().size(), when);
    assertEquals("main", sample.labels().get(0).str(), when);
    assertEquals(1, profile.functions().size(), when);
    assertEquals(1, profile.locations().size(), when);
    assertEquals(5_000_000_000L, profile.durationNanos(), when);
  }
}

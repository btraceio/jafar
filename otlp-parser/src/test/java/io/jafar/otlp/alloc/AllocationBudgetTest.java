package io.jafar.otlp.alloc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.jafar.otlp.MinimalOtlpBuilder;
import io.jafar.otlp.api.OtlpParser;
import io.jafar.otlp.api.OtlpProfile;
import io.jafar.testsupport.AllocationBudgets;
import io.jafar.testsupport.AllocationMeter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Allocation budget of the otlp entry point ({@link OtlpParser#parse(Path)}) on the {@code
 * MinimalOtlpBuilder} basic profile (AC-3.1).
 *
 * <p>The parse is single-threaded — {@code OtlpReader} is a hand-rolled single-phase protobuf
 * wire-format decoder with no executor, no worker thread and no parallel stream anywhere in {@code
 * otlp-parser/src/main} — so the normal {@link AllocationMeter} semantics (calling-thread delta)
 * measure the whole parse, and the test runs in the module's normal {@code test} task (D9),
 * mirroring the hdump and pprof cells.
 *
 * <p>Determinism: the fixture is built once, outside any measurement — the builder writes to a temp
 * file whose name varies per run, but the profile bytes are fixed by the construction below — and
 * two warm-up parses are discarded before the measured parse (first parse pays the lazy
 * gzip/decoder initialization).
 */
class AllocationBudgetTest {

  /** Generated once per class: the basic round-trip profile of {@code OtlpParserTest}. */
  @TempDir static Path tempDir;

  private static Path fixture;

  /** Budget key without the JDK suffix; {@link AllocationBudgets#check} appends {@code jdk21}. */
  private static final String KEY = "otlp-parser.parse.minimal";

  @BeforeAll
  static void buildFixture() throws IOException {
    MinimalOtlpBuilder b = new MinimalOtlpBuilder();
    int typeIdx = b.addString("cpu");
    int unitIdx = b.addString("nanoseconds");
    int fnName = b.addString("com.example.Foo.bar");
    int filename = b.addString("Foo.java");
    int threadKey = b.addString("thread");

    b.setSampleType(typeIdx, unitIdx);
    b.setDurationNanos(5_000_000_000L);

    int fnIdx = b.addFunction(fnName, filename);
    int locIdx = b.addLocation(fnIdx, 42);
    int stackIdx = b.addStack(List.of(locIdx));
    int attrIdx = b.addAttribute(threadKey, "main");

    b.addSample(stackIdx, List.of(attrIdx), List.of(1_234_567L));
    fixture = b.write(tempDir);
  }

  @Test
  void otlpParseStaysWithinAllocationBudget() throws Exception {
    // Two discarded warm-up parses: the first pays lazy gzip/decoder initialization, the second
    // one more lazy step observed on JDK 21 (kb/a4-jdk8-alloc, same pattern as parser-core).
    for (int i = 0; i < 2; i++) {
      assertPayload(OtlpParser.parse(fixture), "warm-up parse " + i);
    }

    AllocationMeter meter = AllocationMeter.start();
    OtlpProfile.ProfilesData data = OtlpParser.parse(fixture);
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
    assertPayload(data, "measured parse");
    AllocationBudgets.check(KEY, bytes);
  }

  /** The basic-profile payload, mirroring {@code OtlpParserTest#roundTripBasicProfile}. */
  private static void assertPayload(OtlpProfile.ProfilesData data, String when) {
    assertEquals(1, data.profiles().size(), when + ": basic profile changed?");
    OtlpProfile.Profile profile = data.profiles().get(0);
    assertNotNull(profile.sampleType(), when);
    assertEquals("cpu", profile.sampleType().type(), when);
    assertEquals("nanoseconds", profile.sampleType().unit(), when);
    assertEquals(5_000_000_000L, profile.durationNano(), when);
    assertEquals(1, profile.samples().size(), when);
    assertEquals(1, profile.samples().get(0).values().size(), when);
    assertEquals(1_234_567L, profile.samples().get(0).values().get(0), when);
    OtlpProfile.Dictionary dict = data.dictionary();
    assertEquals(1, dict.stackTable().size(), when);
    assertEquals(1, dict.functionTable().size(), when);
    assertEquals("com.example.Foo.bar", dict.functionTable().get(0).name(), when);
    assertEquals(1, dict.locationTable().size(), when);
    assertEquals(1, dict.attributeTable().size(), when);
    assertEquals("thread", dict.attributeTable().get(0).key(), when);
    assertEquals("main", dict.attributeTable().get(0).value(), when);
  }
}

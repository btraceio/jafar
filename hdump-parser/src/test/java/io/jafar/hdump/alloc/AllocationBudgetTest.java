package io.jafar.hdump.alloc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapDumpParser;
import io.jafar.hdump.api.HeapObject;
import io.jafar.hdump.test.SyntheticHeapDumpGenerator;
import io.jafar.testsupport.AllocationBudgets;
import io.jafar.testsupport.AllocationMeter;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Allocation budget of the hdump entry point ({@link HeapDumpParser#parse(Path)}) on the {@code
 * generateA5InstanceFields} synthetic fixture (AC-3.1).
 *
 * <p>The parse is single-threaded — no {@code StreamingChunkParser} chunk pool is involved — so the
 * normal {@link AllocationMeter} semantics (calling-thread delta) measure the whole parse; unlike
 * the parser-core cell there is nothing to mark from a pool-thread callback, and the test runs in
 * the module's normal {@code test} task (D9). The single-threadedness evidence is recorded in the
 * budget-file comment: no {@code Executor}/{@code new Thread} in {@code hdump-parser/src/main}, and
 * the module's only parallelism ({@code DominatorTreeComputer}'s parallel common-pool streams) is
 * reached solely through {@code computeDominators()}, which the default-options {@code parse(path)}
 * does not call.
 *
 * <p>Determinism: the fixture is generated once, outside any measurement — its file name may vary
 * with the temp dir, its bytes must not — and two warm-up parses are discarded before the measured
 * parse, mirroring the parser-core cell (first parse pays the lazy-init share).
 */
class AllocationBudgetTest {

  /** Generated once per class: the A5 instance-fields dump (1 Holder + 2 Target objects). */
  @TempDir static Path tempDir;

  private static Path fixture;

  /** Budget key without the JDK suffix; {@link AllocationBudgets#check} appends {@code jdk21}. */
  private static final String KEY = "hdump-parser.parse.a5-instance-fields";

  /** Objects the A5 fixture contains, asserted on every parse so a fixture swap cannot hide. */
  private static final int OBJECTS = 3;

  @BeforeAll
  static void generateFixture() throws IOException {
    fixture = tempDir.resolve("a5-instance-fields.hprof");
    SyntheticHeapDumpGenerator.generateA5InstanceFields(fixture);
  }

  @Test
  void heapDumpParseStaysWithinAllocationBudget() throws Exception {
    // Two discarded warm-up parses: the first pays lazy parser/logger initialization, the second
    // one more lazy step observed on JDK 21 (kb/a4-jdk8-alloc, same pattern as parser-core).
    for (int i = 0; i < 2; i++) {
      try (HeapDump dump = HeapDumpParser.parse(fixture)) {
        assertPayload(dump, "warm-up parse " + i);
      }
    }

    AllocationMeter meter = AllocationMeter.start();
    HeapDump dump = HeapDumpParser.parse(fixture);
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
    try (HeapDump measured = dump) {
      assertPayload(measured, "measured parse");
    }
    AllocationBudgets.check(KEY, bytes);
  }

  /**
   * The A5 payload, mirroring {@code HprofSpecificationComplianceTest#testA5_InstanceFields}: 3
   * objects, and the Holder's two instance-field references resolved to both Target objects.
   */
  private static void assertPayload(HeapDump dump, String when) {
    assertEquals(OBJECTS, dump.getObjects().count(), when + ": A5 fixture changed?");
    HeapObject holder = dump.getObjectById(1000).orElseThrow();
    assertNotNull(holder.getHeapClass(), when + ": holder object should have a class");
    assertEquals("Holder", holder.getHeapClass().getName(), when);
    long[] refIds = holder.getOutboundReferences().mapToLong(HeapObject::getId).sorted().toArray();
    assertArrayEquals(
        new long[] {2000, 2001}, refIds, when + ": holder should reference both targets");
  }
}

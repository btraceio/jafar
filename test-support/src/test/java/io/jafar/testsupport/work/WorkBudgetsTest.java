package io.jafar.testsupport.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Permanent proofs of every {@link WorkBudgets} outcome: no fallback is silent — a missing system
 * property, an unreadable file, a missing key, a malformed value and a stale key each fail loudly;
 * counts are exact, so one over fails and one under fails with "re-tighten"; every check prints the
 * measured count next to its budget; and the update mode rewrites only the entry's lines. Each case
 * drives the real {@code check()} through a temp budget file.
 */
class WorkBudgetsTest {

  private static final String ENTRY = "parser-core.untyped.tck-test.jfr";

  @TempDir Path dir;

  @AfterEach
  void clearProperties() {
    System.clearProperty(WorkBudgets.BUDGETS_PROPERTY);
    System.clearProperty(WorkBudgets.UPDATE_PROPERTY);
  }

  private static Map<String, Long> counts(long decoded, long varints) {
    Map<String, Long> counts = new LinkedHashMap<>();
    counts.put("events.decoded", decoded);
    counts.put("varints.read", varints);
    return counts;
  }

  private Path budgets(String... lines) throws IOException {
    Path file = dir.resolve("work-budgets.properties");
    Files.write(file, String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8));
    System.setProperty(WorkBudgets.BUDGETS_PROPERTY, file.toString());
    return file;
  }

  private static String fails(Map<String, Long> measured) {
    return assertThrows(AssertionError.class, () -> WorkBudgets.check(ENTRY, measured))
        .getMessage();
  }

  @Test
  void exactCountsPassAndPrintEveryCounter() throws IOException {
    budgets("# c", ENTRY + ".events.decoded=10", ENTRY + ".varints.read=20");
    String printed = stdout(() -> WorkBudgets.check(ENTRY, counts(10, 20)));
    assertTrue(
        printed.contains("work " + ENTRY + ".events.decoded measured=10 budget=10"), printed);
    assertTrue(printed.contains("work " + ENTRY + ".varints.read measured=20 budget=20"), printed);
  }

  @Test
  void oneOverFails() throws IOException {
    budgets(ENTRY + ".events.decoded=10", ENTRY + ".varints.read=20");
    String message = fails(counts(11, 20));
    assertTrue(
        message.contains(ENTRY + ".events.decoded over budget by 1 (measured 11, budget 10)"),
        message);
    assertTrue(message.contains("Work-Budget: <reason>"), message);
  }

  @Test
  void oneUnderFailsWithReTighten() throws IOException {
    budgets(ENTRY + ".events.decoded=10", ENTRY + ".varints.read=20");
    String message = fails(counts(10, 19));
    assertTrue(
        message.contains(
            ENTRY
                + ".varints.read under budget by 1 — re-tighten: set "
                + ENTRY
                + ".varints.read=19"),
        message);
  }

  @Test
  void everyProblemIsListedNotJustTheFirst() throws IOException {
    budgets(ENTRY + ".events.decoded=10", ENTRY + ".varints.read=20");
    String message = fails(counts(12, 18));
    assertTrue(message.contains("events.decoded over budget by 2"), message);
    assertTrue(message.contains("varints.read under budget by 2"), message);
  }

  @Test
  void missingKeyFailsWithTheLineToAdd() throws IOException {
    budgets(ENTRY + ".events.decoded=10", "other.entry.fixture.varints.read=20");
    String message = fails(counts(10, 20));
    assertTrue(
        message.contains(
            "no budget for "
                + ENTRY
                + ".varints.read; add "
                + ENTRY
                + ".varints.read=20 to gradle/work-budgets.properties"),
        message);
  }

  @Test
  void malformedValueFails() throws IOException {
    budgets(ENTRY + ".events.decoded=ten", ENTRY + ".varints.read=20");
    String message = fails(counts(10, 20));
    assertTrue(message.contains("malformed work budget for " + ENTRY + ".events.decoded"), message);
  }

  @Test
  void staleKeyFails() throws IOException {
    budgets(ENTRY + ".events.decoded=10", ENTRY + ".varints.read=20", ENTRY + ".bytes.read=30");
    String message = fails(counts(10, 20));
    assertTrue(message.contains("stale work budget " + ENTRY + ".bytes.read"), message);
  }

  @Test
  void anotherEntrysKeysAreNotStale() throws IOException {
    budgets(
        ENTRY + ".events.decoded=10",
        ENTRY + ".varints.read=20",
        "parser-core.untyped-filtered.tck-test.jfr.bytes.read=30");
    WorkBudgets.check(ENTRY, counts(10, 20));
  }

  @Test
  void missingPropertyFails() {
    String message = fails(counts(1, 1));
    assertTrue(message.contains("jafar.work.budgets is not set"), message);
  }

  @Test
  void unreadableFileFails() {
    System.setProperty(WorkBudgets.BUDGETS_PROPERTY, dir.resolve("absent.properties").toString());
    String message = fails(counts(1, 1));
    assertTrue(message.contains("cannot read the work budget file"), message);
  }

  @Test
  void updateRewritesOnlyTheEntryKeepingCommentsAndOrder() throws IOException {
    Path file =
        budgets(
            "# header",
            "",
            "# untyped",
            ENTRY + ".events.decoded=1",
            ENTRY + ".bytes.read=5",
            "",
            "# other",
            "other.entry.fixture.varints.read=7");
    System.setProperty(WorkBudgets.UPDATE_PROPERTY, "true");
    WorkBudgets.check(ENTRY, counts(10, 20));
    String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    assertEquals(
        "# header\n\n# untyped\n"
            + ENTRY
            + ".events.decoded=10\n"
            + ENTRY
            + ".varints.read=20\n\n# other\nother.entry.fixture.varints.read=7\n",
        text);
    // and the rewritten file passes a plain check
    System.clearProperty(WorkBudgets.UPDATE_PROPERTY);
    WorkBudgets.check(ENTRY, counts(10, 20));
  }

  @Test
  void updateAppendsANewEntryAtTheEnd() {
    String text = WorkBudgets.rewrite("# header\nother.e.f.a.b=1\n", ENTRY, counts(3, 4));
    assertEquals(
        "# header\nother.e.f.a.b=1\n\n"
            + ENTRY
            + ".events.decoded=3\n"
            + ENTRY
            + ".varints.read=4\n",
        text);
  }

  @Test
  void measureRefusesWithoutTheAgent() {
    System.clearProperty(WorkProbes.AGENT_PROPERTY);
    AssertionError error = assertThrows(AssertionError.class, () -> WorkCounters.measure(() -> {}));
    assertTrue(error.getMessage().contains("-javaagent"), error.getMessage());
  }

  @Test
  void runsThatDifferAreRefusedWithBothCounts() throws Exception {
    System.setProperty(WorkProbes.AGENT_PROPERTY, "installed"); // stands in for premain
    try {
      long[] calls = {0};
      AssertionError error =
          assertThrows(
              AssertionError.class,
              () ->
                  WorkBudgets.measureAndCheck(
                      ENTRY,
                      () -> {
                        if (calls[0]++ == 1) {
                          WorkCounters.hit(WorkProbes.indexOf(WorkProbes.EVENTS_DECODED));
                        }
                      }));
      assertTrue(error.getMessage().contains("parse 2 counted different work"), error.getMessage());
      assertTrue(error.getMessage().contains("events.decoded=1"), error.getMessage());
    } finally {
      System.clearProperty(WorkProbes.AGENT_PROPERTY);
    }
  }

  @Test
  void agentErrorsFailTheMeasurement() {
    System.setProperty(WorkProbes.AGENT_PROPERTY, "installed");
    System.setProperty(WorkProbes.ERRORS_PROPERTY, "probe x found no method y");
    try {
      AssertionError error =
          assertThrows(AssertionError.class, () -> WorkCounters.measure(() -> {}));
      assertTrue(error.getMessage().contains("probe x found no method y"), error.getMessage());
    } finally {
      System.clearProperty(WorkProbes.AGENT_PROPERTY);
      System.clearProperty(WorkProbes.ERRORS_PROPERTY);
    }
  }

  private static String stdout(Runnable action) {
    PrintStream original = System.out;
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    System.setOut(new PrintStream(buffer, true));
    try {
      action.run();
    } finally {
      System.setOut(original);
    }
    return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
  }
}

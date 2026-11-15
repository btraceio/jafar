package io.jafar.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

/**
 * Permanent proofs of every {@link AllocationBudgets} outcome (AC-3.4): the loader never falls back
 * silently — a missing system property, an unreadable file, a missing key and a malformed value
 * each fail loudly, an excluded cell aborts with its reason, and the tolerance band decides
 * over/under/within. Each case drives the real {@code check()} through a temp properties file, so
 * the tested surface is exactly the one the allocation tasks use.
 */
class AllocationBudgetsTest {

  @TempDir Path dir;

  /**
   * (1) Missing key: the failure names the full key (with the JDK suffix), the measured bytes, the
   * VM, and the exact line to add.
   */
  @Test
  void missingKeyFailsNamingKeyAndMeasuredValue() throws IOException {
    final String key = "parser-core.untyped.tck-test.jfr";
    final String full = key + "." + AllocationBudgets.jdkKey();
    Path file =
        budgets(
            "# comment recording the JDK build",
            "other.entry.fixture." + AllocationBudgets.jdkKey() + "=1000");
    final AssertionError failure = checkFails(file, key, 123456);
    String message = failure.getMessage();
    assertTrue(
        message.contains("no budget for " + full),
        "expected the failure to name the missing key, got: " + message);
    assertTrue(
        message.contains("123456"),
        "expected the failure to name the measured bytes, got: " + message);
    assertTrue(
        message.contains("measured 123456 bytes on "),
        "expected the failure to carry the VM the measurement ran on, got: " + message);
    assertTrue(
        message.contains("add " + full + "=123456 to gradle/allocation-budgets.properties"),
        "expected the failure to give the exact line to add, got: " + message);
  }

  /**
   * (2) Over budget: fails with the excess, the measured figure, the budget, the tolerance and the
   * VM.
   */
  @Test
  void overBudgetFails() throws IOException {
    String key = "module.entry.fixture";
    String full = key + "." + AllocationBudgets.jdkKey();
    Path file = budgets(full + "=1000", full + ".tolerance=10");
    AssertionError failure = checkFails(file, key, 1500);
    String message = failure.getMessage();
    assertTrue(
        message.contains("over budget by 490 bytes"),
        "expected the failure to name the excess, got: " + message);
    assertTrue(
        message.contains("measured 1500, budget 1000, tolerance 10"),
        "expected the failure to carry all four figures, got: " + message);
  }

  /**
   * (3) Under budget by more than the tolerance: fails with the re-tighten hint (D2) and the value
   * to set.
   */
  @Test
  void underBudgetFailsWithReTightenHint() throws IOException {
    String key = "module.entry.fixture";
    String full = key + "." + AllocationBudgets.jdkKey();
    Path file = budgets(full + "=1000", full + ".tolerance=10");
    AssertionError failure = checkFails(file, key, 500);
    String message = failure.getMessage();
    assertTrue(
        message.contains("under budget by 490 bytes"),
        "expected the failure to name the slack, got: " + message);
    assertTrue(
        message.contains("re-tighten: set " + full + "=500"),
        "expected the failure to give the re-tightened value, got: " + message);
  }

  /**
   * (4) Within tolerance — both band edges inclusive — passes, and every pass still prints the
   * audit line with the measured figure, the budget, the tolerance and the VM (R3).
   */
  @Test
  void withinTolerancePassesAndAlwaysPrintsTheAuditLine() throws IOException {
    String key = "module.entry.fixture";
    final String full = key + "." + AllocationBudgets.jdkKey();
    Path file = budgets(full + "=1000", full + ".tolerance=10");
    withBudgetsFile(
        file,
        () -> {
          for (long measured : new long[] {1000, 1010, 990}) {
            try (StdoutCapture captured = captureStdout()) {
              AllocationBudgets.check(key, measured);
              String printed = captured.text();
              assertTrue(
                  printed.contains(
                      "alloc " + full + " measured=" + measured + " budget=1000 tol=10 vm="),
                  "expected the audit line on a pass, got: " + printed);
            }
          }
        });
  }

  /** (5) Default tolerance is 0: with no tolerance entry, one byte over the budget fails. */
  @Test
  void defaultToleranceIsZero() throws IOException {
    String key = "module.entry.fixture";
    String full = key + "." + AllocationBudgets.jdkKey();
    Path file = budgets(full + "=1000");
    AssertionError failure = checkFails(file, key, 1001);
    assertTrue(
        failure.getMessage().contains("tolerance 0"),
        "expected the default tolerance of 0 in the failure, got: " + failure.getMessage());
    assertTrue(
        failure.getMessage().contains("over budget by 1 byte"),
        "expected the one-byte excess in the failure, got: " + failure.getMessage());
  }

  /**
   * (6) An excluded cell aborts the test with the recorded reason — skipped, not compared, not
   * failed.
   */
  @Test
  void excludedCellAbortsWithTheReason() throws IOException {
    String key = "hdump-parser.parse.a5-instance-fields";
    String full = key + "." + AllocationBudgets.jdkKey();
    Path file = budgets(full + "=excluded: module targets Java 21; never runs on JDK 8");
    final TestAbortedException[] aborted = new TestAbortedException[1];
    withBudgetsFile(
        file,
        () -> {
          try {
            AllocationBudgets.check(key, 42);
            fail("an excluded cell must abort, not pass");
          } catch (TestAbortedException e) {
            aborted[0] = e;
          }
        });
    assertTrue(
        aborted[0] != null && aborted[0].getMessage().contains("module targets Java 21"),
        "expected the abort to carry the exclusion reason, got: "
            + (aborted[0] == null ? "no abort" : aborted[0].getMessage()));
  }

  /** (7) Missing system property: a loud AssertionError, never a silently passing check (R3). */
  @Test
  void missingSystemPropertyFails() {
    String saved = System.clearProperty(AllocationBudgets.BUDGETS_PROPERTY);
    try {
      AssertionError failure =
          assertThrows(AssertionError.class, () -> AllocationBudgets.check("a.b.c", 1));
      assertTrue(
          failure.getMessage().contains("jafar.alloc.budgets"),
          "expected the failure to name the missing property, got: " + failure.getMessage());
      assertTrue(
          failure.getMessage().contains("misconfiguration"),
          "expected the failure to say this is a harness misconfiguration, got: "
              + failure.getMessage());
    } finally {
      restoreProperty(AllocationBudgets.BUDGETS_PROPERTY, saved);
    }
  }

  /**
   * (8) JDK key derivation: pre-9 specification versions carry the major after the 1. prefix, later
   * ones are the major themselves; anything else fails loudly.
   */
  @Test
  void jdkKeyDerivation() {
    assertEquals("jdk8", AllocationBudgets.jdkKey("1.8"));
    assertEquals("jdk21", AllocationBudgets.jdkKey("21"));
    assertEquals("jdk11", AllocationBudgets.jdkKey("11"));
    // The running JVM derives a well-formed key — this is what check() appends
    assertTrue(
        AllocationBudgets.jdkKey().startsWith("jdk"),
        "expected a jdk<major> key for this JVM, got: " + AllocationBudgets.jdkKey());
    assertThrows(AssertionError.class, () -> AllocationBudgets.jdkKey("weird"));
    assertThrows(AssertionError.class, () -> AllocationBudgets.jdkKey(null));
  }

  /**
   * (9) A malformed budget value fails naming the key — a garbage figure is never treated as 0 or
   * skipped (R3).
   */
  @Test
  void malformedBudgetFailsNamingTheKey() throws IOException {
    String key = "module.entry.fixture";
    String full = key + "." + AllocationBudgets.jdkKey();
    Path file = budgets(full + "=about a thousand");
    AssertionError failure = checkFails(file, key, 100);
    String message = failure.getMessage();
    assertTrue(
        message.contains(full), "expected the failure to name the malformed key, got: " + message);
    assertTrue(
        message.contains("\"about a thousand\""),
        "expected the failure to quote the malformed value, got: " + message);
  }

  /** (10) An unreadable budget file fails naming the path the system property points at (R3). */
  @Test
  void unreadableBudgetFileFailsNamingThePath() throws IOException {
    Path missing = dir.resolve("no-such-budgets.properties");
    final AssertionError failure = checkFails(missing, "module.entry.fixture", 100);
    String message = failure.getMessage();
    assertTrue(
        message.contains(missing.toString()),
        "expected the failure to name the unreadable path, got: " + message);
    assertTrue(
        message.contains("jafar.alloc.budgets"),
        "expected the failure to name the source property, got: " + message);
  }

  // --- helpers -----------------------------------------------------------

  /** Writes a budget file into the temp dir and returns its path. */
  private Path budgets(String... lines) throws IOException {
    Path file = dir.resolve("allocation-budgets.properties");
    Files.write(file, Arrays.asList(lines), StandardCharsets.UTF_8);
    return file;
  }

  /**
   * Runs {@link AllocationBudgets#check} against {@code file}, expecting an {@link AssertionError},
   * and restores the previous {@code jafar.alloc.budgets} value afterwards.
   */
  private AssertionError checkFails(Path file, String keyWithoutJdk, long measured) {
    final AssertionError[] failure = new AssertionError[1];
    withBudgetsFile(
        file,
        () -> {
          try {
            AllocationBudgets.check(keyWithoutJdk, measured);
          } catch (AssertionError e) {
            failure[0] = e;
          }
          if (failure[0] == null) {
            throw new AssertionError(
                "expected check(\""
                    + keyWithoutJdk
                    + "\", "
                    + measured
                    + ") to fail, but it passed");
          }
        });
    return failure[0];
  }

  /** Sets the budget-file property, runs {@code body}, restores the previous value. */
  private void withBudgetsFile(Path file, Runnable body) {
    String saved =
        System.setProperty(AllocationBudgets.BUDGETS_PROPERTY, file.toAbsolutePath().toString());
    try {
      body.run();
    } finally {
      restoreProperty(AllocationBudgets.BUDGETS_PROPERTY, saved);
    }
  }

  private static void restoreProperty(String name, String saved) {
    if (saved == null) {
      System.clearProperty(name);
    } else {
      System.setProperty(name, saved);
    }
  }

  /**
   * Captures {@code System.out} for the duration of one check; closing restores the original
   * stream. The audit line of a passing check is the thing under test, so it must be readable, not
   * just emitted.
   */
  private static StdoutCapture captureStdout() {
    return new StdoutCapture(System.out);
  }

  private static final class StdoutCapture implements AutoCloseable {
    private final PrintStream original;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    StdoutCapture(PrintStream original) {
      this.original = original;
      System.setOut(new PrintStream(out));
    }

    String text() {
      try {
        return out.toString(StandardCharsets.UTF_8.name());
      } catch (java.io.UnsupportedEncodingException e) {
        throw new AssertionError("UTF-8 is somehow unsupported", e);
      }
    }

    @Override
    public void close() {
      System.out.flush();
      System.setOut(original);
    }
  }
}

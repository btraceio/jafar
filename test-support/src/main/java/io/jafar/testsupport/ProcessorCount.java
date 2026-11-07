package io.jafar.testsupport;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * In-JVM guard of the processor-count matrix ({@code -Pjafar.activeProcessorCount=N}, safety
 * harness step 5).
 *
 * <p>Why the matrix exists (kb/design-concurrency item 1): the one proven, documented jafar hazard
 * is that chunk callbacks run concurrently on {@code StreamingChunkParser}'s pool of {@code
 * max(processors - 2, 1)} threads, so single-threaded-looking listener state corrupts silently —
 * and a 1-2 core CI runner <em>cannot</em> reproduce a race that needs more cores. The matrix runs
 * the existing determinism tests at {@code ActiveProcessorCount=1} and {@code =16}; every test
 * class covered by it calls {@link #assertExpected()} so a run proves the flag actually reached its
 * forked JVM.
 *
 * <p>Gradle wiring (root {@code build.gradle}): when the property is given, every {@code Test} task
 * forks with {@code -XX:ActiveProcessorCount=$N} plus {@code jafar.expectedProcessors=$N} — except
 * {@code allocationTest*} tasks, which stay pinned to 4 because their budgets assume a pool of
 * exactly 2 threads (D9).
 */
public final class ProcessorCount {

  /**
   * Set by the Gradle matrix wiring to the {@code -XX:ActiveProcessorCount} value of the forked
   * JVM. Absent outside a matrix run (and always absent in {@code allocationTest*}).
   */
  public static final String EXPECTED_PROCESSORS_PROPERTY = "jafar.expectedProcessors";

  private ProcessorCount() {}

  /**
   * Asserts that the value of {@link #EXPECTED_PROCESSORS_PROPERTY}, when set, equals this JVM's
   * {@code availableProcessors()}; prints the audit line either way (R9: a green run must prove
   * what the forked JVM actually saw, not just pass).
   *
   * <p>Call from {@code @BeforeAll} of every test class covered by the matrix. A mismatch means the
   * Gradle wiring did not reach this JVM — that is a misconfiguration of the matrix, never a parser
   * result, so it fails loudly instead of passing on a silently unpinned JVM (R3).
   */
  public static void assertExpected() {
    String expected = System.getProperty(EXPECTED_PROCESSORS_PROPERTY);
    int actual = Runtime.getRuntime().availableProcessors();
    report(actual, expected);
    if (expected == null) {
      return; // not a matrix run (or an allocationTest* cell): nothing to compare against
    }
    int parsed = parseExpected(expected);
    if (actual != parsed) {
      fail(
          "processor-count matrix did not reach this JVM: availableProcessors()="
              + actual
              + " but "
              + EXPECTED_PROCESSORS_PROPERTY
              + "="
              + parsed
              + ". The root build.gradle wiring adds -XX:ActiveProcessorCount=N and"
              + " jafar.expectedProcessors=N to every Test task except allocationTest* (pinned"
              + " to 4, D9); when running a test outside Gradle, pass both flags yourself.");
    }
  }

  /**
   * The pool size {@code StreamingChunkParser} derives from a processor count: {@code
   * max(processors - 2, 1)}. Exposed for the audit line and the arithmetic's own test; kept next to
   * {@code max(n-2,1)} in {@code StreamingChunkParser} by hand.
   */
  static int poolSize(int processors) {
    return Math.max(processors - 2, 1);
  }

  /**
   * Rejects a malformed or non-positive {@link #EXPECTED_PROCESSORS_PROPERTY} instead of guessing
   * (R3): a blank or non-numeric value would otherwise be indistinguishable from a wiring bug when
   * compared against {@code availableProcessors()}.
   */
  private static int parseExpected(String expected) {
    int parsed;
    try {
      parsed = Integer.parseInt(expected.trim());
    } catch (NumberFormatException e) {
      throw new AssertionError(
          "malformed "
              + EXPECTED_PROCESSORS_PROPERTY
              + "=\""
              + expected
              + "\": not a processor count");
    }
    if (parsed < 1) {
      throw new AssertionError(
          EXPECTED_PROCESSORS_PROPERTY + "=" + parsed + ": not a usable processor count");
    }
    return parsed;
  }

  /**
   * The always-printed audit line (R9): {@code availableProcessors=<n> expected=<N|unset>
   * pool=<max(n-2,1)>}. The pool figure shows the chunk-pool size this JVM gives the parser, so a
   * matrix run's log states both the flag's arrival and the concurrency it created.
   */
  private static void report(int actual, String expected) {
    System.out.println(
        "availableProcessors="
            + actual
            + " expected="
            + (expected == null ? "unset" : expected.trim())
            + " pool="
            + poolSize(actual));
  }
}

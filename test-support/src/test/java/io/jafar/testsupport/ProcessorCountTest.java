package io.jafar.testsupport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Permanent fire tests for {@link ProcessorCount} (safety harness step 5): the matrix guard must
 * FAIL when the flag did not reach the JVM, and must always print the audit line that proves what
 * the forked JVM saw (R9). The whole class runs inside the matrix runs too, so the audit line it
 * prints for real is what the Gradle {@code grep availableProcessors=<N>} check looks at.
 */
class ProcessorCountTest {

  /** Saved so the tests never leak the value Gradle's matrix wiring set for this JVM. */
  private String savedExpected;

  @BeforeEach
  void saveProperty() {
    savedExpected = System.getProperty(ProcessorCount.EXPECTED_PROCESSORS_PROPERTY);
  }

  @AfterEach
  void restoreProperty() {
    if (savedExpected == null) {
      System.clearProperty(ProcessorCount.EXPECTED_PROCESSORS_PROPERTY);
    } else {
      System.setProperty(ProcessorCount.EXPECTED_PROCESSORS_PROPERTY, savedExpected);
    }
  }

  /**
   * Runs {@code body} with {@code System.out} redirected, returning what it printed. Used by the
   * tests that assert the audit line's exact shape; {@link #auditLineGoesToRealStdout()} must NOT
   * use it — a {@code @BeforeEach} redirect would swallow the real-stdout line the matrix run's log
   * is grepped for.
   */
  private String captured(Runnable body) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream original = System.out;
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    try {
      body.run();
    } finally {
      System.setOut(original);
    }
    return new String(out.toByteArray(), StandardCharsets.UTF_8);
  }

  private void setExpected(String value) {
    if (value == null) {
      System.clearProperty(ProcessorCount.EXPECTED_PROCESSORS_PROPERTY);
    } else {
      System.setProperty(ProcessorCount.EXPECTED_PROCESSORS_PROPERTY, value);
    }
  }

  @Test
  void mismatchFailsNamingBothCounts() {
    // Actual + 1000 can never match, whatever the JVM this runs on
    int actual = Runtime.getRuntime().availableProcessors();
    String unreachable = Integer.toString(actual + 1000);
    setExpected(unreachable);
    // R9: the audit line is printed even on the failing path
    String printed =
        captured(() -> assertThrows(AssertionError.class, ProcessorCount::assertExpected));
    assertTrue(
        printed.contains("availableProcessors=" + actual + " "),
        "audit line missing on failure: " + printed);
    // The property and both counts are named, so the failure states what did not reach the JVM
    AssertionError failure = assertThrows(AssertionError.class, ProcessorCount::assertExpected);
    assertTrue(
        failure.getMessage().contains(ProcessorCount.EXPECTED_PROCESSORS_PROPERTY),
        "message must name the property: " + failure.getMessage());
    assertTrue(
        failure.getMessage().contains(Integer.toString(actual)),
        "message must name the actual count: " + failure.getMessage());
    assertTrue(
        failure.getMessage().contains(unreachable),
        "message must name the expected count: " + failure.getMessage());
  }

  @Test
  void matchPassesAndPrintsTheAuditLine() {
    final int actual = Runtime.getRuntime().availableProcessors();
    setExpected(Integer.toString(actual));
    String printed = captured(() -> assertDoesNotThrow(ProcessorCount::assertExpected));
    assertEquals(
        "availableProcessors="
            + actual
            + " expected="
            + actual
            + " pool="
            + ProcessorCount.poolSize(actual)
            + System.lineSeparator(),
        printed);
  }

  @Test
  void unsetExpectedPassesAndPrintsUnset() {
    setExpected(null);
    final int actual = Runtime.getRuntime().availableProcessors();
    String printed = captured(() -> assertDoesNotThrow(ProcessorCount::assertExpected));
    assertEquals(
        "availableProcessors="
            + actual
            + " expected=unset pool="
            + ProcessorCount.poolSize(actual)
            + System.lineSeparator(),
        printed);
  }

  @Test
  void malformedExpectedFailsInsteadOfGuessing() {
    setExpected("not-a-number");
    AssertionError failure = assertThrows(AssertionError.class, ProcessorCount::assertExpected);
    assertTrue(
        failure.getMessage().contains("not-a-number"),
        "message must name the malformed value: " + failure.getMessage());
  }

  @Test
  void nonPositiveExpectedFails() {
    setExpected("0");
    assertThrows(AssertionError.class, ProcessorCount::assertExpected);
  }

  /**
   * The pool arithmetic the audit line reports — the exact expression {@code StreamingChunkParser}
   * uses to size its chunk pool.
   */
  @Test
  void poolSizeMatchesTheChunkPoolExpression() {
    assertEquals(1, ProcessorCount.poolSize(1));
    assertEquals(1, ProcessorCount.poolSize(2));
    assertEquals(1, ProcessorCount.poolSize(3));
    assertEquals(2, ProcessorCount.poolSize(4));
    assertEquals(14, ProcessorCount.poolSize(16));
  }

  /**
   * Printed to the real stdout (not captured), so a matrix run's Gradle log carries the audit line
   * for this JVM even though the other tests here redirect to assert its exact shape.
   */
  @Test
  void auditLineGoesToRealStdout() {
    ProcessorCount.assertExpected();
  }
}

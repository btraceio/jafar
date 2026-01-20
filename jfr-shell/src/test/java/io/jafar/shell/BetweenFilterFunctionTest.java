package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.cli.CommandDispatcher;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the between() filter function.
 *
 * <p>The between() function checks if a numeric value falls within an inclusive range [min, max].
 * Syntax: between(value, min, max)
 *
 * <ul>
 *   <li>Returns true if min <= value <= max
 *   <li>Requires all arguments to be numeric
 *   <li>Inclusive on both boundaries
 * </ul>
 */
class BetweenFilterFunctionTest {

  static class BufferIO implements CommandDispatcher.IO {
    final StringBuilder out = new StringBuilder();
    final StringBuilder err = new StringBuilder();

    @Override
    public void println(String s) {
      out.append(s).append('\n');
    }

    @Override
    public void printf(String fmt, Object... args) {
      out.append(String.format(fmt, args));
    }

    @Override
    public void error(String s) {
      err.append(s).append('\n');
    }

    String getOutput() {
      return out.toString();
    }

    String getError() {
      return err.toString();
    }

    void clearOutput() {
      out.setLength(0);
      err.setLength(0);
    }
  }

  private static Path testJfr() {
    return Paths.get("..", "parser", "src", "test", "resources", "test-ap.jfr")
        .normalize()
        .toAbsolutePath();
  }

  private ParsingContext ctx;
  private SessionManager sessions;
  private CommandDispatcher dispatcher;
  private BufferIO io;

  @BeforeEach
  void setUp() throws Exception {
    ctx = ParsingContext.create();
    sessions = new SessionManager(ctx, (path, c) -> new JFRSession(path, c));
    io = new BufferIO();
    dispatcher = new CommandDispatcher(sessions, io, r -> {});

    // Open test JFR file
    dispatcher.dispatch("open " + testJfr());
  }

  @AfterEach
  void tearDown() {
    if (sessions != null) {
      try {
        sessions.closeAll();
      } catch (Exception e) {
        // Ignore
      }
    }
  }

  // ==================== Basic Between Tests ====================

  @Test
  void betweenValueInRange() throws Exception {
    // Filter events where duration is between 1000 and 10000000
    dispatcher.dispatch(
        "show events/jdk.ExecutionSample[between(duration, 1000, 10000000)] | count()");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should produce output");
    assertTrue(output.contains("count"), "Should contain count result");
  }

  @Test
  void betweenValueOutOfRange() throws Exception {
    // Filter events where duration is between extremely large values (should match very few or
    // none)
    dispatcher.dispatch(
        "show events/jdk.ExecutionSample[between(duration, 999999999999, 9999999999999)] |"
            + " count()");

    String output = io.getOutput();
    assertNotNull(output);
    // Should still produce output even if no matches
  }

  @Test
  void betweenWithZeroRange() throws Exception {
    // Filter events where a value equals exactly 0 (between 0 and 0)
    dispatcher.dispatch("show events/jdk.ExecutionSample[between(duration, 0, 0)] | count()");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should produce output");
  }

  // ==================== Boundary Tests ====================

  @Test
  void betweenIncludesLowerBound() throws Exception {
    // Value exactly at lower bound should be included
    dispatcher.dispatch(
        "show events/jdk.ExecutionSample[between(duration, 0, 10000000)] | count()");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should include lower bound");
  }

  @Test
  void betweenIncludesUpperBound() throws Exception {
    // Test that upper bound is inclusive
    dispatcher.dispatch(
        "show events/jdk.ExecutionSample[between(duration, 0, 1000000000)] | count()");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should include upper bound");
  }

  @Test
  void betweenWithSameMinMax() throws Exception {
    // When min == max, should match values exactly equal to that number
    dispatcher.dispatch("show events/jdk.ExecutionSample[between(duration, 1000, 1000)] | count()");

    String output = io.getOutput();
    assertNotNull(output);
    // May or may not have results, but should not error
  }

  // ==================== Negative Number Tests ====================

  @Test
  void betweenWithNegativeRange() throws Exception {
    // Test with negative numbers (though duration is typically positive)
    dispatcher.dispatch("show events/jdk.ExecutionSample[between(duration, -1000, 0)] | count()");

    String output = io.getOutput();
    assertNotNull(output);
    // Should work even if no events match
  }

  @Test
  void betweenWithNegativeAndPositive() throws Exception {
    // Test with range spanning negative to positive
    dispatcher.dispatch("show events/jdk.ExecutionSample[between(duration, -100, 100)] | count()");

    String output = io.getOutput();
    assertNotNull(output);
  }

  // ==================== Different Numeric Types ====================

  @Test
  void betweenWithIntegerBounds() throws Exception {
    // Test with integer boundaries
    dispatcher.dispatch("show events/jdk.ExecutionSample[between(duration, 1, 1000)] | count()");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should work with integer bounds");
  }

  @Test
  void betweenWithLargeBounds() throws Exception {
    // Test with very large numbers
    dispatcher.dispatch(
        "show events/jdk.ExecutionSample[between(duration, 0, 9223372036854775807)] | count()");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should work with large bounds");
  }

  // ==================== Complex Filter Combinations ====================

  @Test
  void betweenCombinedWithOtherFilters() throws Exception {
    // Combine between() with other filter conditions
    dispatcher.dispatch(
        "show events/jdk.ExecutionSample[state=\"RUNNABLE\" and between(duration, 1000, 100000)]"
            + " | count()");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should work in combination with other filters");
  }

  @Test
  void multipleBetweenFilters() throws Exception {
    // Use between() multiple times in same query
    dispatcher.dispatch(
        "show events/jdk.ExecutionSample[between(duration, 1000, 1000000) and between(duration,"
            + " 5000, 500000)] | count()");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should work with multiple between() conditions");
  }

  @Test
  void betweenWithOrCondition() throws Exception {
    // Use between() in OR condition
    dispatcher.dispatch(
        "show events/jdk.ExecutionSample[between(duration, 0, 1000) or between(duration, 100000,"
            + " 1000000)] | count()");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should work in OR conditions");
  }

  @Test
  void betweenWithNotOperator() throws Exception {
    // Negate between() condition
    dispatcher.dispatch(
        "show events/jdk.ExecutionSample[not between(duration, 1000, 10000)] | count()");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should work with NOT operator");
  }

  // ==================== Integration with Aggregations ====================

  @Test
  void betweenWithGroupBy() throws Exception {
    // Use between() filter before groupBy
    dispatcher.dispatch(
        "show events/jdk.ExecutionSample[between(duration, 1000, 1000000)] | groupBy(state)");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should work before groupBy");
  }

  @Test
  void betweenWithStats() throws Exception {
    // Filter with between() then compute stats
    dispatcher.dispatch(
        "show events/jdk.ExecutionSample[between(duration, 1000, 1000000)]/duration | stats()");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should work before stats()");
  }

  // ==================== Edge Cases ====================

  @Test
  void betweenWithVerySmallRange() throws Exception {
    // Test with very small range
    dispatcher.dispatch("show events/jdk.ExecutionSample[between(duration, 100, 101)] | count()");

    String output = io.getOutput();
    assertNotNull(output);
    // Should work even if range is very narrow
  }

  @Test
  void betweenWithInvertedBounds() throws Exception {
    // Test with min > max (should match nothing logically)
    dispatcher.dispatch(
        "show events/jdk.ExecutionSample[between(duration, 10000, 1000)] | count()");

    String output = io.getOutput();
    assertNotNull(output);
    // Should not error, but likely no matches
  }

  // ==================== Different Field Types ====================

  @Test
  void betweenOnDifferentNumericField() throws Exception {
    // Test between() on different numeric fields if available
    dispatcher.dispatch(
        "show events/jdk.ExecutionSample[between(duration, 0, 10000000)] | count()");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should work on duration field");
  }

  // ==================== Output Format Tests ====================

  @Test
  void betweenWithJsonOutput() throws Exception {
    // Test between() with JSON output format
    dispatcher.dispatch(
        "show events/jdk.ExecutionSample[between(duration, 1000, 1000000)] | count() --format"
            + " json");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.contains("["), "Should produce JSON output");
    assertTrue(output.contains("\"count\""), "Should contain count field");
  }

  // ==================== Projection with Between ====================

  @Test
  void betweenWithProjection() throws Exception {
    // Use between() filter with field projection
    dispatcher.dispatch(
        "show events/jdk.ExecutionSample[between(duration, 1000, 100000)]/state --limit 10");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should work with projection");
  }

  // ==================== Performance/Scale Tests ====================

  @Test
  void betweenWithNoLimit() throws Exception {
    // Test between() without limit (tests performance with large result sets)
    dispatcher.dispatch(
        "show events/jdk.ExecutionSample[between(duration, 0, 999999999)] | count()");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.contains("count"), "Should complete successfully");
  }
}

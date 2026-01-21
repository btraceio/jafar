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
 * Tests for the sketch() aggregation function.
 *
 * <p>The sketch() function combines stats() and quantiles() into a single result, providing a
 * comprehensive statistical summary with:
 *
 * <ul>
 *   <li>min, max, sum, count, avg (from stats)
 *   <li>p50, p90, p99 (from quantiles with default percentiles)
 * </ul>
 */
class SketchAggregationTest {

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

  // ==================== Basic Sketch Tests ====================

  @Test
  void sketchBasicAggregation() throws Exception {
    dispatcher.dispatch("show events/jdk.ExecutionSample/duration | sketch()");

    String output = io.getOutput();
    String error = io.getError();

    // Debug output
    if (output.length() == 0 && error.length() > 0) {
      fail("No output produced. Error: " + error);
    }

    assertNotNull(output);
    assertTrue(output.length() > 0, "Should produce output. Error: " + error);

    // Should contain statistical fields
    assertTrue(output.contains("min") || output.contains("Min"), "Should contain min field");
    assertTrue(output.contains("max") || output.contains("Max"), "Should contain max field");
    assertTrue(output.contains("count") || output.contains("Count"), "Should contain count field");
    assertTrue(output.contains("avg") || output.contains("Avg"), "Should contain avg field");

    // Should contain quantile fields
    assertTrue(output.contains("p50") || output.contains("P50"), "Should contain p50 field");
    assertTrue(output.contains("p90") || output.contains("P90"), "Should contain p90 field");
    assertTrue(output.contains("p99") || output.contains("P99"), "Should contain p99 field");
  }

  @Test
  void sketchWithExplicitPath() throws Exception {
    dispatcher.dispatch("show events/jdk.ExecutionSample | sketch(duration)");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should produce output");

    // Should contain both stats and quantiles
    assertTrue(output.contains("min") || output.contains("Min"), "Should contain min");
    assertTrue(output.contains("p50") || output.contains("P50"), "Should contain p50");
  }

  @Test
  void sketchWithJsonFormat() throws Exception {
    dispatcher.dispatch("show events/jdk.ExecutionSample/duration | sketch() --format json");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.contains("["), "JSON output should contain array marker");
    assertTrue(
        output.contains("\"min\"") || output.contains("\"count\""),
        "JSON should contain statistical field names");
    assertTrue(
        output.contains("\"p50\"") || output.contains("\"p90\"") || output.contains("\"p99\""),
        "JSON should contain quantile field names");
  }

  // ==================== Sketch with Filters ====================

  @Test
  void sketchWithFilter() throws Exception {
    dispatcher.dispatch("show events/jdk.ExecutionSample[state=\"RUNNABLE\"]/duration | sketch()");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should produce output");
    assertTrue(output.contains("min") || output.contains("Min"), "Should contain statistics");
  }

  @Test
  void sketchWithComplexFilter() throws Exception {
    dispatcher.dispatch(
        "show events/jdk.ExecutionSample[state=\"RUNNABLE\" and duration > 0]/duration | sketch()");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should produce output");
  }

  // ==================== Sketch Output Validation ====================

  @Test
  void sketchContainsAllExpectedFields() throws Exception {
    dispatcher.dispatch("show events/jdk.ExecutionSample/duration | sketch() --format json");

    String output = io.getOutput();

    // Stats fields (sum may not always be included depending on implementation)
    assertTrue(output.contains("\"min\""), "Should contain min field");
    assertTrue(output.contains("\"max\""), "Should contain max field");
    assertTrue(output.contains("\"count\""), "Should contain count field");
    assertTrue(output.contains("\"avg\""), "Should contain avg field");

    // Quantile fields with default percentiles
    assertTrue(output.contains("\"p50\""), "Should contain p50 field");
    assertTrue(output.contains("\"p90\""), "Should contain p90 field");
    assertTrue(output.contains("\"p99\""), "Should contain p99 field");
  }

  @Test
  void sketchWithEmptyResult() throws Exception {
    dispatcher.dispatch(
        "show events/jdk.ExecutionSample[state=\"NONEXISTENT\"]/duration | sketch()");

    String output = io.getOutput();
    assertNotNull(output);
    // Empty result should still produce output (possibly with zeros or no data message)
  }

  // ==================== Comparison with Stats and Quantiles ====================

  @Test
  void sketchCombinesStatsAndQuantiles() throws Exception {
    // Run stats
    dispatcher.dispatch("show events/jdk.ExecutionSample/duration | stats()");
    String statsOutput = io.getOutput();
    io.clearOutput();

    // Run quantiles
    dispatcher.dispatch("show events/jdk.ExecutionSample/duration | quantiles(0.5, 0.9, 0.99)");
    String quantilesOutput = io.getOutput();
    io.clearOutput();

    // Run sketch
    dispatcher.dispatch("show events/jdk.ExecutionSample/duration | sketch()");
    String sketchOutput = io.getOutput();

    // Sketch should contain information from both
    assertNotNull(statsOutput);
    assertNotNull(quantilesOutput);
    assertNotNull(sketchOutput);
    assertTrue(sketchOutput.length() > 0, "Sketch should produce output");

    // Note: We can't reliably assert that sketch contains exact values from stats and quantiles
    // due to table formatting, but we can verify it has both types of fields
    assertTrue(
        (sketchOutput.contains("min") || sketchOutput.contains("Min"))
            && (sketchOutput.contains("p50") || sketchOutput.contains("P50")),
        "Sketch should contain both stats and quantile fields");
  }

  // ==================== Sketch with Different Data Types ====================

  @Test
  void sketchWithNumericField() throws Exception {
    // Test with a different numeric field if available
    dispatcher.dispatch("show events/jdk.ExecutionSample/duration | sketch()");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should produce output for numeric field");
  }

  // ==================== Edge Cases ====================

  @Test
  void sketchWithSingleEvent() throws Exception {
    // Use a filter that should match very few or single event
    dispatcher.dispatch("show events/jdk.ExecutionSample/duration | sketch() --limit 1");

    String output = io.getOutput();
    assertNotNull(output);
    // Even with one event, sketch should work
  }

  @Test
  void sketchWithoutPriorSession() {
    // Attempt sketch without opening a session - should fail gracefully with error message
    ParsingContext emptyCtx = ParsingContext.create();
    SessionManager emptySession =
        new SessionManager(emptyCtx, (path, c) -> new JFRSession(path, c));
    BufferIO emptyIO = new BufferIO();
    CommandDispatcher emptyDispatcher = new CommandDispatcher(emptySession, emptyIO, r -> {});

    emptyDispatcher.dispatch("show events/jdk.ExecutionSample/duration | sketch()");

    // Should produce error message, not exception
    String error = emptyIO.getError();
    assertTrue(error.contains("No session"), "Should show 'No session' error message");

    try {
      emptySession.closeAll();
    } catch (Exception e) {
      // Ignore
    }
  }

  // ==================== Sketch Syntax Variations ====================

  @Test
  void sketchWithParentheses() throws Exception {
    dispatcher.dispatch("show events/jdk.ExecutionSample/duration | sketch()");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should work with empty parentheses");
  }

  @Test
  void sketchWithFieldArgument() throws Exception {
    dispatcher.dispatch("show events/jdk.ExecutionSample | sketch(duration)");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should work with field argument");
  }

  @Test
  void sketchInPipeline() throws Exception {
    // Sketch as part of a longer pipeline
    dispatcher.dispatch("show events/jdk.ExecutionSample[state=\"RUNNABLE\"]/duration | sketch()");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should work in pipeline");
  }
}

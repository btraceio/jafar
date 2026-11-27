package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.cli.CommandDispatcher;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
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
    return Paths.get("..", "parser-core", "src", "test", "resources", "test-ap.jfr")
        .normalize()
        .toAbsolutePath();
  }

  private ParsingContext ctx;
  private SessionManager<Session> sessions;
  private CommandDispatcher dispatcher;
  private BufferIO io;

  @BeforeEach
  void setUp() throws Exception {
    ctx = ParsingContext.create();
    sessions = new SessionManager<>((path, c) -> new JFRSession(path, (ParsingContext) c), ctx);
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
    dispatcher.dispatch("show events/jdk.GCHeapSummary/heapUsed | sketch()");

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
    // test-ap.jfr's 160 jdk.GCHeapSummary events (jdk.ExecutionSample has no duration: these
    // sketched nothing, #299). The values come from the seeded generator (test-recordings
    // Shapes.writeGc); a change there changes them
    assertTrue(output.contains("| 160 "), output);
    assertTrue(output.contains("6.7108864E7") && output.contains("7.86432E8"), output);
  }

  @Test
  void sketchWithExplicitPath() throws Exception {
    dispatcher.dispatch("show events/jdk.GCHeapSummary | sketch(heapUsed)");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should produce output");

    // Should contain both stats and quantiles
    assertTrue(output.contains("min") || output.contains("Min"), "Should contain min");
    assertTrue(output.contains("p50") || output.contains("P50"), "Should contain p50");
    assertTrue(output.contains("| 160 "), output);
    assertTrue(output.contains("2.32521728E8"), output); // p50
  }

  @Test
  void sketchWithJsonFormat() throws Exception {
    dispatcher.dispatch("show events/jdk.GCHeapSummary/heapUsed | sketch() --format json");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.contains("["), "JSON output should contain array marker");
    assertTrue(
        output.contains("\"min\"") || output.contains("\"count\""),
        "JSON should contain statistical field names");
    assertTrue(
        output.contains("\"p50\"") || output.contains("\"p90\"") || output.contains("\"p99\""),
        "JSON should contain quantile field names");
    assertTrue(output.contains("\"count\": 160"), output);
  }

  // ==================== Sketch with Filters ====================

  @Test
  void sketchWithFilter() throws Exception {
    dispatcher.dispatch(
        "show events/jdk.ExecutionSample[state=\"STATE_RUNNABLE\"]/startTime | sketch()");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should produce output");
    assertTrue(output.contains("min") || output.contains("Min"), "Should contain statistics");
    // test-ap.jfr's samples have no duration, so startTime is sketched. 6467 of its 8000 are
    // STATE_RUNNABLE; [state="RUNNABLE"] matches none (#288)
    assertTrue(output.contains("6467"), output);
  }

  @Test
  void sketchWithComplexFilter() throws Exception {
    dispatcher.dispatch(
        "show events/jdk.ExecutionSample[state=\"STATE_RUNNABLE\" and startTime < 1704067230000000000]/startTime"
            + " | sketch()");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should produce output");
    // Both clauses count: 6467 are STATE_RUNNABLE, 4063 in the window, 3263 both
    assertTrue(output.contains("3263"), output);
  }

  // ==================== Sketch Output Validation ====================

  @Test
  void sketchContainsAllExpectedFields() throws Exception {
    dispatcher.dispatch("show events/jdk.GCHeapSummary/heapUsed | sketch() --format json");

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
    assertTrue(output.contains("\"min\": 6.7108864E7"), output);
    assertTrue(output.contains("\"max\": 7.86432E8"), output);
    // Each statistic under its own name, so a swapped pair fails
    for (String pair :
        List.of(
            "\"avg\": 3.330048E8",
            "\"p50\": 2.32521728E8",
            "\"p90\": 7.08837376E8",
            "\"p99\": 7.8118912E8",
            "\"stddev\": 2.329920258251914E8")) {
      assertTrue(output.contains(pair), pair + " in " + output);
    }
    // count 160, whichever key follows it
    assertTrue(output.matches("(?s).*\"count\": 160\\s*[,}].*"), output);
  }

  @Test
  void sketchWithEmptyResult() throws Exception {
    dispatcher.dispatch("show events/jdk.GCHeapSummary[gcId > 1000]/heapUsed | sketch()");

    String output = io.getOutput();
    assertNotNull(output);
    // Empty result should still produce output (possibly with zeros or no data message)
    // No gcId above 1000: nothing to sketch
    assertTrue(output.contains("| 0 "), output);
  }

  // ==================== Comparison with Stats and Quantiles ====================

  @Test
  void sketchCombinesStatsAndQuantiles() throws Exception {
    // Run stats
    dispatcher.dispatch("show events/jdk.GCHeapSummary/heapUsed | stats()");
    String statsOutput = io.getOutput();
    io.clearOutput();

    // Run quantiles
    dispatcher.dispatch("show events/jdk.GCHeapSummary/heapUsed | quantiles(0.5, 0.9, 0.99)");
    String quantilesOutput = io.getOutput();
    io.clearOutput();

    // Run sketch
    dispatcher.dispatch("show events/jdk.GCHeapSummary/heapUsed | sketch()");
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
    // The same values as stats() and quantiles()
    for (String value : List.of("6.7108864E7", "7.86432E8", "3.330048E8")) {
      assertTrue(statsOutput.contains(value) && sketchOutput.contains(value), value);
    }
    for (String value : List.of("2.32521728E8", "7.08837376E8", "7.8118912E8")) {
      assertTrue(quantilesOutput.contains(value) && sketchOutput.contains(value), value);
    }
  }

  // ==================== Sketch with Different Data Types ====================

  @Test
  void sketchWithNumericField() throws Exception {
    // Test with a different numeric field if available
    dispatcher.dispatch("show events/jdk.GCHeapSummary/heapUsed | sketch()");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should produce output for numeric field");
    assertTrue(output.contains("| 160 "), output);
  }

  // ==================== Edge Cases ====================

  @Test
  void sketchWithSingleEvent() throws Exception {
    // Use a filter that should match very few or single event
    dispatcher.dispatch("show events/jdk.GCHeapSummary[heapUsed = 646971392]/heapUsed | sketch()");

    String output = io.getOutput();
    assertNotNull(output);
    // Even with one event, sketch should work
    // One event: count 1, min = max = its value
    assertTrue(output.contains("| 1 "), output);
    // min, max, avg, p50, p90 and p99 are all that one value
    assertEquals(6, output.split("6\\.46971392E8", -1).length - 1, output);
  }

  @Test
  void sketchWithoutPriorSession() {
    // Attempt sketch without opening a session - should fail gracefully with error message
    ParsingContext emptyCtx = ParsingContext.create();
    SessionManager<Session> emptySession =
        new SessionManager<>((path, c) -> new JFRSession(path, (ParsingContext) c), emptyCtx);
    BufferIO emptyIO = new BufferIO();
    CommandDispatcher emptyDispatcher = new CommandDispatcher(emptySession, emptyIO, r -> {});

    emptyDispatcher.dispatch("show events/jdk.GCHeapSummary/heapUsed | sketch()");

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
    dispatcher.dispatch("show events/jdk.GCHeapSummary/heapUsed | sketch()");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should work with empty parentheses");
    assertTrue(output.contains("| 160 "), output);
  }

  @Test
  void sketchWithFieldArgument() throws Exception {
    dispatcher.dispatch("show events/jdk.GCHeapSummary | sketch(heapUsed)");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should work with field argument");
    assertTrue(output.contains("| 160 "), output);
  }

  @Test
  void sketchInPipeline() throws Exception {
    // Sketch as part of a longer pipeline
    dispatcher.dispatch(
        "show events/jdk.ExecutionSample[state=\"STATE_RUNNABLE\"]/startTime | sketch()");

    String output = io.getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should work in pipeline");
    assertTrue(output.contains("6467"), output);
  }
}

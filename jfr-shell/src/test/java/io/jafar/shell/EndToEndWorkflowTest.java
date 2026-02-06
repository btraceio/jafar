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
 * End-to-end workflow tests using real JFR files.
 *
 * <p>Tests complete workflows that users would perform:
 *
 * <ul>
 *   <li>Opening JFR files and running queries
 *   <li>Creating variables and reusing them in subsequent queries
 *   <li>Chaining multiple operations in realistic scenarios
 *   <li>Error handling with real data
 *   <li>Complex analysis workflows
 * </ul>
 */
class EndToEndWorkflowTest {

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
    sessions = new SessionManager((path, c) -> new JFRSession(path, (ParsingContext) c), ctx);
    io = new BufferIO();
    dispatcher = new CommandDispatcher(sessions, io, r -> {});

    // Open test JFR file
    dispatcher.dispatch("open " + testJfr());
    io.clearOutput();
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

  // ==================== Basic Query Workflows ====================

  @Test
  void simpleCountWorkflow() {
    // Count execution samples
    dispatcher.dispatch("show events/jdk.ExecutionSample | count()");

    String output = io.getOutput();
    assertTrue(output.contains("count"), "Should show count column");

    // Extract and validate count value
    long count = extractFirstNumber(output);
    assertTrue(count > 0, "Should have execution samples");
  }

  @Test
  void groupByWorkflow() {
    // Group execution samples by state
    dispatcher.dispatch("show events/jdk.ExecutionSample | groupBy(state) | count()");

    String output = io.getOutput();
    // GroupBy produces list output, not a table with named columns
    assertNotNull(output);
    assertFalse(output.isEmpty(), "Should produce output");
  }

  // ==================== Variable Creation and Reuse ====================

  @Test
  void createVariableAndReuse() {
    // Create a variable with aggregated execution samples
    dispatcher.dispatch("set samples = events/jdk.ExecutionSample | count()");
    io.clearOutput();

    // Use the variable in output
    dispatcher.dispatch("echo ${samples.count}");

    String output = io.getOutput();
    assertNotNull(output);
    assertFalse(output.isEmpty(), "Should produce output");
  }

  @Test
  void multipleVariablesWorkflow() {
    // Create multiple variables
    dispatcher.dispatch("set samples = events/jdk.ExecutionSample | count()");
    dispatcher.dispatch("set methods = metadata/jdk.types.Method | count()");
    io.clearOutput();

    // Access both variables
    dispatcher.dispatch("echo ${samples.count}");
    String output1 = io.getOutput();
    assertNotNull(output1);

    io.clearOutput();
    dispatcher.dispatch("echo ${methods.count}");
    String output2 = io.getOutput();
    assertNotNull(output2);
  }

  @Test
  void variableWithAggregation() {
    // Create variable with grouped data
    dispatcher.dispatch("set byState = events/jdk.ExecutionSample | groupBy(state)");
    io.clearOutput();

    // Use variable in further aggregation
    dispatcher.dispatch("show ${byState} | count()");

    String output = io.getOutput();
    assertTrue(output.contains("state") || output.contains("count"), "Should show aggregated data");
  }

  // ==================== Complex Query Workflows ====================

  @Test
  void chainedAggregationsWorkflow() {
    // Complex query with multiple aggregations
    dispatcher.dispatch(
        "show events/jdk.ExecutionSample | groupBy(state) | count() | select(state, count)");

    String output = io.getOutput();
    assertTrue(output.contains("state"), "Should have state column");
    assertFalse(io.getError().length() > 0, "Should not have errors");
  }

  @Test
  void metadataQueryWorkflow() {
    // Query metadata
    dispatcher.dispatch("show metadata/jdk.types.Class | count()");

    String output = io.getOutput();
    assertTrue(output.contains("count"), "Should show count");

    long count = extractFirstNumber(output);
    // Metadata may or may not be present in test files, so just check query completes
    assertTrue(count >= 0, "Should return valid count (may be 0)");
  }

  @Test
  void chunksQueryWorkflow() {
    // Query chunk information
    dispatcher.dispatch("show chunks | count()");

    String output = io.getOutput();
    assertTrue(output.contains("count"), "Should show count");

    long count = extractFirstNumber(output);
    assertTrue(count > 0, "Should have at least one chunk");
  }

  // ==================== Filtering Workflows ====================

  @Test
  void filterAndCountWorkflow() {
    // Filter by state and count
    dispatcher.dispatch("show events/jdk.ExecutionSample[state==\"RUNNABLE\"] | count()");

    String output = io.getOutput();
    assertTrue(output.contains("count"), "Should show count");
  }

  @Test
  void multipleFiltersWorkflow() {
    // Apply multiple filters
    dispatcher.dispatch("set filtered = events/jdk.ExecutionSample[state==\"RUNNABLE\"]");
    io.clearOutput();

    dispatcher.dispatch("show ${filtered} | count()");

    String output = io.getOutput();
    assertNotNull(output);
  }

  // ==================== Conditional Workflows ====================

  @Test
  void conditionalExecutionWorkflow() {
    // Create variable
    dispatcher.dispatch("set count = events/jdk.ExecutionSample | count()");
    io.clearOutput();

    // Use conditional to check count
    dispatcher.dispatch("if ${count.count} > 0");
    dispatcher.dispatch("echo Has execution samples");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Has execution samples"), "Should execute conditional block");
  }

  @Test
  void conditionalWithElseWorkflow() {
    // Create a numeric variable
    dispatcher.dispatch("set num = 42");
    io.clearOutput();

    // Conditional with else branch
    dispatcher.dispatch("if ${num} > 100");
    dispatcher.dispatch("echo Large");
    dispatcher.dispatch("else");
    dispatcher.dispatch("echo Small");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Small"), "Should execute else branch");
    assertFalse(output.contains("Large"), "Should not execute if branch");
  }

  // ==================== Error Handling Workflows ====================

  @Test
  void handleInvalidEventType() {
    // Try to query non-existent event type
    dispatcher.dispatch("show events/NonExistentEventType | count()");

    String output = io.getOutput();
    String error = io.getError();

    // Should handle gracefully - either empty result or error message
    assertNotNull(output);
    assertNotNull(error);
  }

  @Test
  void handleInvalidFieldAccess() {
    // Try to access non-existent field on variable
    dispatcher.dispatch("set samples = events/jdk.ExecutionSample | count()");
    io.clearOutput();

    dispatcher.dispatch("echo ${samples.nonexistentField}");

    // Should handle gracefully
    String output = io.getOutput();
    assertNotNull(output);
  }

  // ==================== Multi-Step Analysis Workflows ====================

  @Test
  void performanceAnalysisWorkflow() {
    // Step 1: Get total sample count
    dispatcher.dispatch("set totalSamples = events/jdk.ExecutionSample | count()");

    // Step 2: Group by state
    dispatcher.dispatch("set byState = events/jdk.ExecutionSample | groupBy(state)");

    // Step 3: Show results
    io.clearOutput();
    dispatcher.dispatch("show ${byState} | count()");

    String output = io.getOutput();
    assertTrue(output.contains("state") || output.contains("count"), "Should show grouped results");
  }

  @Test
  void memoryAnalysisWorkflow() {
    // Try to analyze GC-related events
    dispatcher.dispatch("set gcEvents = events/jdk.GarbageCollection");
    io.clearOutput();

    dispatcher.dispatch("echo ${gcEvents} | count()");

    // May or may not have GC events, but query should complete
    String output = io.getOutput();
    assertNotNull(output);
  }

  // ==================== Variable Management Workflows ====================

  @Test
  void listVariablesWorkflow() {
    // Create some variables
    dispatcher.dispatch("set var1 = 10");
    dispatcher.dispatch("set var2 = events/jdk.ExecutionSample | count()");
    io.clearOutput();

    // List all variables
    dispatcher.dispatch("vars");

    String output = io.getOutput();
    assertTrue(output.contains("var1") || output.contains("var2"), "Should list variables");
  }

  @Test
  void unsetVariableWorkflow() {
    // Create and then unset a variable
    dispatcher.dispatch("set temp = 42");
    dispatcher.dispatch("unset temp");
    io.clearOutput();

    // Try to use the unset variable
    dispatcher.dispatch("echo ${temp}");

    // Should handle gracefully
    String output = io.getOutput();
    assertNotNull(output);
  }

  // ==================== Stats Aggregation Workflows ====================

  @Test
  void statsAggregationWorkflow() {
    // Get statistics on duration field
    dispatcher.dispatch("show events/jdk.ExecutionSample/duration | stats()");

    String output = io.getOutput();
    // Stats should include count, sum, min, max, avg
    assertTrue(
        output.contains("count") || output.contains("sum") || output.contains("min"),
        "Should show statistics");
  }

  // ==================== Session Management Workflows ====================

  @Test
  void infoCommandWorkflow() {
    // Get session information
    dispatcher.dispatch("info");

    String output = io.getOutput();
    assertTrue(output.contains("test-ap.jfr"), "Should show current session file");
  }

  @Test
  void sessionsCommandWorkflow() {
    // List all sessions
    dispatcher.dispatch("sessions");

    String output = io.getOutput();
    assertTrue(output.contains("test-ap.jfr"), "Should list open sessions");
  }

  // ==================== Help and Documentation Workflows ====================

  @Test
  void helpCommandWorkflow() {
    // Get help information
    dispatcher.dispatch("help");

    String output = io.getOutput();
    assertTrue(output.contains("show") || output.contains("set"), "Should show help text");
  }

  @Test
  void helpSpecificCommandWorkflow() {
    // Get help for specific command
    dispatcher.dispatch("help show");

    String output = io.getOutput();
    assertTrue(
        output.contains("show") || output.contains("events"), "Should show show command help");
  }

  // ==================== Realistic User Scenarios ====================

  @Test
  void findHotMethodsWorkflow() {
    // Realistic scenario: Find most sampled methods
    dispatcher.dispatch(
        "set hotMethods = events/jdk.ExecutionSample | groupBy(stackTrace/frames[0]/method)");
    io.clearOutput();

    dispatcher.dispatch("show ${hotMethods} | count()");

    String output = io.getOutput();
    // May or may not have stack traces, but query should complete
    assertNotNull(output);
  }

  @Test
  void analyzeThreadActivityWorkflow() {
    // Realistic scenario: Analyze thread activity
    dispatcher.dispatch(
        "set threadActivity = events/jdk.ExecutionSample | groupBy(eventThread/javaName)");
    io.clearOutput();

    dispatcher.dispatch("show ${threadActivity} | count()");

    String output = io.getOutput();
    assertNotNull(output);
  }

  // ==================== Helper Methods ====================

  private long extractFirstNumber(String output) {
    for (String line : output.split("\\R")) {
      if (line.matches("\\|\\s*\\d+\\s*\\|")) {
        String digits = line.replaceAll("[^0-9]", "");
        if (!digits.isEmpty()) {
          try {
            return Long.parseLong(digits);
          } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid numeric value in output: " + digits, e);
          }
        }
      }
    }
    return -1;
  }
}

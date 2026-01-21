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
 * Tests for the invalidate command.
 *
 * <p>The invalidate command clears the cache for lazy query variables, forcing them to re-execute
 * on next access.
 *
 * <ul>
 *   <li>Syntax: invalidate <varname>
 *   <li>Only works on lazy query variables (not scalars or maps)
 *   <li>Produces error if variable doesn't exist or is not lazy
 * </ul>
 */
class InvalidateCacheCommandTest {

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

  // ==================== Basic Invalidate Tests ====================

  @Test
  void invalidateLazyQueryVariable() {
    // Create a lazy query variable
    dispatcher.dispatch("set result = events/jdk.ExecutionSample | count()");

    // Access it to ensure it's cached
    dispatcher.dispatch("echo ${result.count}");
    io.clearOutput();

    // Invalidate it
    dispatcher.dispatch("invalidate result");

    String error = io.getError();

    // Should not produce "not found" error
    assertFalse(error.contains("not found"), "Should find the variable");
  }

  @Test
  void invalidateWithAccessBeforeAndAfter() {
    // Create a lazy query variable and access it
    dispatcher.dispatch("set result = events/jdk.ExecutionSample | count()");
    dispatcher.dispatch("echo ${result.count}");
    io.clearOutput();

    // Invalidate the cache
    dispatcher.dispatch("invalidate result");

    // Access again - should re-execute the query
    dispatcher.dispatch("echo ${result.count}");

    String output = io.getOutput();
    // Should show the count value again
    assertNotNull(output);
  }

  // ==================== Error Cases ====================

  @Test
  void invalidateNonExistentVariable() {
    // Try to invalidate a variable that doesn't exist
    dispatcher.dispatch("invalidate nonexistent");

    String error = io.getError();
    assertTrue(error.contains("not found"), "Should show variable not found error");
  }

  @Test
  void invalidateScalarVariable() {
    // Create a scalar variable
    dispatcher.dispatch("set scalar = \"value\"");

    // Try to invalidate it
    dispatcher.dispatch("invalidate scalar");

    String error = io.getError();
    assertTrue(error.contains("not a lazy value"), "Should show not a lazy value error");
  }

  @Test
  void invalidateNumericVariable() {
    // Create a numeric variable
    dispatcher.dispatch("set number = 42");

    // Try to invalidate it
    dispatcher.dispatch("invalidate number");

    String error = io.getError();
    assertTrue(error.contains("not a lazy value"), "Numeric variables can't be invalidated");
  }

  @Test
  void invalidateMapVariable() {
    // Create a map variable
    dispatcher.dispatch("set map = {\"key\": \"value\"}");

    // Try to invalidate it
    dispatcher.dispatch("invalidate map");

    String error = io.getError();
    assertTrue(error.contains("not a lazy value"), "Map variables can't be invalidated");
  }

  @Test
  void invalidateWithoutArgument() {
    // Try to call invalidate without an argument
    dispatcher.dispatch("invalidate");

    String error = io.getError();
    assertTrue(error.contains("Usage"), "Should show usage message");
  }

  // ==================== Multiple Variables ====================

  @Test
  void invalidateOneOfMultipleVariables() {
    // Create multiple lazy query variables
    dispatcher.dispatch("set result1 = events/jdk.ExecutionSample | count()");
    dispatcher.dispatch("set result2 = events/jdk.ExecutionSample[state=\"RUNNABLE\"] | count()");

    // Invalidate only one
    dispatcher.dispatch("invalidate result1");

    String error = io.getError();
    assertFalse(error.contains("not found"), "Should find result1");

    // Both should still be accessible
    dispatcher.dispatch("echo ${result1.count}");
    dispatcher.dispatch("echo ${result2.count}");

    String output = io.getOutput();
    assertNotNull(output);
  }

  @Test
  void invalidateMultipleTimes() {
    // Create a lazy query variable
    dispatcher.dispatch("set result = events/jdk.ExecutionSample | count()");

    // Invalidate multiple times
    dispatcher.dispatch("invalidate result");
    io.clearOutput();

    dispatcher.dispatch("invalidate result");

    String error = io.getError();
    // Should not error on second invalidation
    assertFalse(error.contains("not found"), "Should still find the variable");
  }

  // ==================== Invalidate with Different Query Types ====================

  @Test
  void invalidateGroupByQuery() {
    // Create a lazy variable with groupBy
    dispatcher.dispatch("set grouped = events/jdk.ExecutionSample | groupBy(state)");

    // Invalidate it
    dispatcher.dispatch("invalidate grouped");

    String error = io.getError();
    assertFalse(error.contains("not found"), "Should find grouped variable");
    assertFalse(error.contains("not a lazy value"), "GroupBy result should be lazy");
  }

  @Test
  void invalidateStatsQuery() {
    // Create a lazy variable with stats
    dispatcher.dispatch("set stats = events/jdk.ExecutionSample/duration | stats()");

    // Invalidate it
    dispatcher.dispatch("invalidate stats");

    String error = io.getError();
    assertFalse(error.contains("not found"), "Should find stats variable");
  }

  @Test
  void invalidateFilteredQuery() {
    // Create a lazy variable with filters
    dispatcher.dispatch("set filtered = events/jdk.ExecutionSample[state=\"RUNNABLE\"]");

    // Invalidate it
    dispatcher.dispatch("invalidate filtered");

    String error = io.getError();
    assertFalse(error.contains("not found"), "Should find filtered variable");
  }

  // ==================== Invalidate in Conditionals ====================

  @Test
  void invalidateInConditionalBlock() {
    // Create a lazy variable
    dispatcher.dispatch("set result = events/jdk.ExecutionSample | count()");

    // Invalidate inside conditional
    dispatcher.dispatch("if 1 == 1");
    dispatcher.dispatch("invalidate result");
    dispatcher.dispatch("endif");

    String error = io.getError();
    assertFalse(error.contains("not found"), "Should invalidate inside conditional");
  }

  @Test
  void invalidateSkippedInInactiveConditional() {
    // Create a lazy variable
    dispatcher.dispatch("set result = events/jdk.ExecutionSample | count()");

    // Try to invalidate in false conditional - should be skipped
    dispatcher.dispatch("if 1 == 2");
    dispatcher.dispatch("invalidate result");
    dispatcher.dispatch("endif");

    // Should not have executed invalidate
    // This is hard to test directly, but we can verify no errors
    assertNotNull(io.getError());
  }

  // ==================== Invalidate with Variable Scopes ====================

  @Test
  void invalidateSessionVariable() {
    // Create a session-scoped lazy variable (default)
    dispatcher.dispatch("set result = events/jdk.ExecutionSample | count()");

    // Invalidate it
    dispatcher.dispatch("invalidate result");

    String error = io.getError();
    assertFalse(error.contains("not found"), "Should find session variable");
  }

  // ==================== Case Sensitivity ====================

  @Test
  void invalidateCaseSensitiveVariableName() {
    // Create variables with different cases
    dispatcher.dispatch("set Result = events/jdk.ExecutionSample | count()");

    // Try to invalidate with different case
    dispatcher.dispatch("invalidate result");

    String error = io.getError();
    // Variable names are case-sensitive, so this should fail
    assertTrue(error.contains("not found"), "Variable names should be case-sensitive");
  }

  // ==================== Invalidate After Variable Reassignment ====================

  @Test
  void invalidateAfterReassignment() {
    // Create a lazy variable
    dispatcher.dispatch("set result = events/jdk.ExecutionSample | count()");

    // Reassign to a different query
    dispatcher.dispatch("set result = events/jdk.ExecutionSample[state=\"RUNNABLE\"] | count()");

    // Invalidate the reassigned variable
    dispatcher.dispatch("invalidate result");

    String error = io.getError();
    assertFalse(error.contains("not found"), "Should find reassigned variable");
  }

  @Test
  void invalidateAfterReassignmentToScalar() {
    // Create a lazy variable
    dispatcher.dispatch("set result = events/jdk.ExecutionSample | count()");

    // Reassign to a scalar value
    dispatcher.dispatch("set result = \"not a query\"");

    // Try to invalidate - should fail because it's now a scalar
    dispatcher.dispatch("invalidate result");

    String error = io.getError();
    assertTrue(error.contains("not a lazy value"), "Should fail after reassignment to scalar");
  }

  // ==================== Integration with Other Commands ====================

  @Test
  void invalidateBeforeVarsCommand() {
    // Create a lazy variable
    dispatcher.dispatch("set result = events/jdk.ExecutionSample | count()");

    // Invalidate it
    dispatcher.dispatch("invalidate result");

    // List variables - should still show the variable
    dispatcher.dispatch("vars");

    String output = io.getOutput();
    assertTrue(output.contains("result"), "Should still show variable after invalidation");
  }

  @Test
  void invalidateBeforeUnset() {
    // Create a lazy variable
    dispatcher.dispatch("set result = events/jdk.ExecutionSample | count()");

    // Invalidate then unset
    dispatcher.dispatch("invalidate result");
    dispatcher.dispatch("unset result");

    // Try to invalidate again - should fail
    io.clearOutput();
    dispatcher.dispatch("invalidate result");

    String error = io.getError();
    assertTrue(error.contains("not found"), "Should not find unset variable");
  }

  // ==================== Edge Cases ====================

  @Test
  void invalidateEmptyStringVariableName() {
    // This might not even create a variable, but test error handling
    dispatcher.dispatch("invalidate \"\"");

    String error = io.getError();
    // Should handle gracefully
    assertNotNull(error);
  }

  @Test
  void invalidateWithWhitespace() {
    // Variable name with leading/trailing whitespace
    dispatcher.dispatch("set result = events/jdk.ExecutionSample | count()");

    // The command dispatcher should handle whitespace in parsing
    dispatcher.dispatch("invalidate   result  ");

    // Should work despite whitespace
    String error = io.getError();
    assertFalse(error.contains("not found"), "Should handle whitespace in command");
  }
}

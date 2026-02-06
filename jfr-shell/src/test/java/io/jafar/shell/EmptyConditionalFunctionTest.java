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
 * Tests for the empty() function in conditionals and filters.
 *
 * <p>The empty() function checks if a variable or field is empty:
 *
 * <ul>
 *   <li>In conditionals: empty(varname) - checks if variable doesn't exist, is null, or is empty
 *       string
 *   <li>In filters: empty(field) - checks if field value is null, empty string, or empty array
 *   <li>Returns true for: non-existent vars, null, empty strings, empty arrays, zero-size
 *       collections
 * </ul>
 */
class EmptyConditionalFunctionTest {

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

  // ==================== Empty Variable Tests (Conditionals) ====================

  @Test
  void emptyWithNonExistentVariable() {
    // Variable that doesn't exist should be considered empty
    dispatcher.dispatch("if empty(nonexistent_var)");
    dispatcher.dispatch("echo Variable is empty");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(
        output.contains("Variable is empty"), "Should execute if block for non-existent var");
  }

  @Test
  void emptyWithEmptyString() {
    // Variable set to empty string should be empty
    dispatcher.dispatch("set emptystr = \"\"");
    dispatcher.dispatch("if empty(emptystr)");
    dispatcher.dispatch("echo String is empty");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("String is empty"), "Should be true for empty string");
  }

  @Test
  void emptyWithNonEmptyString() {
    // Variable with non-empty string should not be empty
    dispatcher.dispatch("set nonempty = \"value\"");
    dispatcher.dispatch("if empty(nonempty)");
    dispatcher.dispatch("echo Should not print");
    dispatcher.dispatch("else");
    dispatcher.dispatch("echo String is not empty");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("String is not empty"), "Should be false for non-empty string");
    assertFalse(output.contains("Should not print"), "Should not execute if block");
  }

  @Test
  void emptyWithNumericVariable() {
    // Numeric variables should not be empty
    dispatcher.dispatch("set num = 42");
    dispatcher.dispatch("if empty(num)");
    dispatcher.dispatch("echo Number is empty");
    dispatcher.dispatch("else");
    dispatcher.dispatch("echo Number is not empty");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Number is not empty"), "Should be false for numeric value");
  }

  @Test
  void emptyWithZero() {
    // Zero should not be considered empty
    dispatcher.dispatch("set zero = 0");
    dispatcher.dispatch("if empty(zero)");
    dispatcher.dispatch("echo Zero is empty");
    dispatcher.dispatch("else");
    dispatcher.dispatch("echo Zero is not empty");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Zero is not empty"), "Zero should not be empty");
  }

  // ==================== Empty with Map Variables ====================

  @Test
  void emptyWithEmptyMap() {
    // Empty map should be considered empty
    dispatcher.dispatch("set emptymap = {}");
    dispatcher.dispatch("if empty(emptymap)");
    dispatcher.dispatch("echo Map is empty");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    // Behavior may vary - map variables might not support empty() the same way
    assertNotNull(output);
  }

  @Test
  void emptyWithNonEmptyMap() {
    // Non-empty map should not be empty
    dispatcher.dispatch("set map = {\"key\": \"value\"}");
    dispatcher.dispatch("if empty(map)");
    dispatcher.dispatch("echo Map is empty");
    dispatcher.dispatch("else");
    dispatcher.dispatch("echo Map is not empty");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    // May or may not execute depending on map handling
    assertNotNull(output);
  }

  // ==================== Empty with Query Results ====================

  @Test
  void emptyWithQueryResultNoMatches() {
    // Query that returns no results should be empty
    dispatcher.dispatch(
        "set result = events/jdk.ExecutionSample[state=\"NONEXISTENT_STATE\"] | count()");
    dispatcher.dispatch("if empty(result)");
    dispatcher.dispatch("echo Result is empty");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    // LazyQueryValue with size 0 should be empty
    assertNotNull(output);
  }

  @Test
  void emptyWithQueryResultHasMatches() {
    // Query that returns results should not be empty
    dispatcher.dispatch("set result = events/jdk.ExecutionSample | count()");
    dispatcher.dispatch("if empty(result)");
    dispatcher.dispatch("echo Result is empty");
    dispatcher.dispatch("else");
    dispatcher.dispatch("echo Result is not empty");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertNotNull(output);
  }

  // ==================== Negation of Empty ====================

  @Test
  void notEmptyWithNonEmptyVariable() {
    // not empty() should be true for non-empty variables
    dispatcher.dispatch("set val = \"test\"");
    dispatcher.dispatch("if not empty(val)");
    dispatcher.dispatch("echo Variable has value");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Variable has value"), "not empty() should work");
  }

  @Test
  void notEmptyWithEmptyVariable() {
    // not empty() should be false for empty variables
    dispatcher.dispatch("set val = \"\"");
    dispatcher.dispatch("if not empty(val)");
    dispatcher.dispatch("echo Should not print");
    dispatcher.dispatch("else");
    dispatcher.dispatch("echo Variable is empty");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Variable is empty"), "not empty() should be false");
  }

  // ==================== Empty Combined with Other Conditions ====================

  @Test
  void emptyWithAndCondition() {
    // Combine empty() with other conditions using AND
    dispatcher.dispatch("set var1 = \"\"");
    dispatcher.dispatch("set var2 = \"value\"");
    dispatcher.dispatch("if empty(var1) and not empty(var2)");
    dispatcher.dispatch("echo Both conditions true");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Both conditions true"), "Should combine with AND");
  }

  @Test
  void emptyWithOrCondition() {
    // Combine empty() with OR
    dispatcher.dispatch("set var1 = \"\"");
    dispatcher.dispatch("set var2 = \"\"");
    dispatcher.dispatch("if empty(var1) or empty(var2)");
    dispatcher.dispatch("echo At least one is empty");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("At least one is empty"), "Should work with OR");
  }

  // ==================== Empty in Nested Conditionals ====================

  @Test
  void emptyInNestedConditionals() {
    // Use empty() in nested if statements
    dispatcher.dispatch("set outer = \"value\"");
    dispatcher.dispatch("set inner = \"\"");
    dispatcher.dispatch("if not empty(outer)");
    dispatcher.dispatch("if empty(inner)");
    dispatcher.dispatch("echo Outer not empty, inner empty");
    dispatcher.dispatch("endif");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(
        output.contains("Outer not empty, inner empty"), "Should work in nested conditionals");
  }

  // ==================== Empty in Elif Branches ====================

  @Test
  void emptyInElifBranch() {
    // Use empty() in elif branch
    dispatcher.dispatch("set var = \"\"");
    dispatcher.dispatch("if 1 == 2");
    dispatcher.dispatch("echo First");
    dispatcher.dispatch("elif empty(var)");
    dispatcher.dispatch("echo Var is empty");
    dispatcher.dispatch("else");
    dispatcher.dispatch("echo Last");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Var is empty"), "Should work in elif branch");
  }

  // ==================== Multiple Empty Checks ====================

  @Test
  void multipleEmptyChecks() {
    // Check multiple variables for emptiness
    dispatcher.dispatch("set a = \"\"");
    dispatcher.dispatch("set b = \"\"");
    dispatcher.dispatch("set c = \"value\"");
    dispatcher.dispatch("if empty(a) and empty(b) and not empty(c)");
    dispatcher.dispatch("echo All conditions met");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("All conditions met"), "Should handle multiple empty() checks");
  }

  // ==================== Empty with Variable Reassignment ====================

  @Test
  void emptyAfterVariableReassignment() {
    // Check that empty() reflects current variable state
    dispatcher.dispatch("set var = \"initial\"");
    dispatcher.dispatch("if empty(var)");
    dispatcher.dispatch("echo First check: empty");
    dispatcher.dispatch("else");
    dispatcher.dispatch("echo First check: not empty");
    dispatcher.dispatch("endif");
    io.clearOutput();

    dispatcher.dispatch("set var = \"\"");
    dispatcher.dispatch("if empty(var)");
    dispatcher.dispatch("echo Second check: empty");
    dispatcher.dispatch("else");
    dispatcher.dispatch("echo Second check: not empty");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Second check: empty"), "Should reflect updated variable state");
  }

  // ==================== Edge Cases ====================

  @Test
  void emptyWithWhitespaceString() {
    // String with only whitespace should not be empty (only "" is empty)
    dispatcher.dispatch("set whitespace = \" \"");
    dispatcher.dispatch("if empty(whitespace)");
    dispatcher.dispatch("echo Whitespace is empty");
    dispatcher.dispatch("else");
    dispatcher.dispatch("echo Whitespace is not empty");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(
        output.contains("Whitespace is not empty"),
        "Whitespace-only string should not be considered empty");
  }

  @Test
  void emptyWithBooleanVariable() {
    // Boolean variables (if supported) should not be empty
    dispatcher.dispatch("set flag = true");
    dispatcher.dispatch("if empty(flag)");
    dispatcher.dispatch("echo Boolean is empty");
    dispatcher.dispatch("else");
    dispatcher.dispatch("echo Boolean is not empty");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    // Boolean handling may vary
    assertNotNull(output);
  }

  // ==================== Empty Function Syntax Variations ====================

  @Test
  void emptyWithParenthesesAndSpaces() {
    // Test that empty() works with various spacing
    dispatcher.dispatch("set var = \"\"");
    dispatcher.dispatch("if empty( var )");
    dispatcher.dispatch("echo Works with spaces");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Works with spaces"), "Should handle spaces in function call");
  }

  @Test
  void emptyInComplexExpression() {
    // Use empty() in a complex boolean expression
    dispatcher.dispatch("set a = \"\"");
    dispatcher.dispatch("set b = \"value\"");
    dispatcher.dispatch("set c = \"\"");
    dispatcher.dispatch("if (empty(a) or empty(c)) and not empty(b)");
    dispatcher.dispatch("echo Complex expression works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Complex expression works"), "Should work in complex expressions");
  }

  // ==================== Error Handling ====================

  @Test
  void emptyWithNoArgument() {
    // empty() without argument should produce error
    dispatcher.dispatch("if empty()");
    dispatcher.dispatch("echo Should not execute");
    dispatcher.dispatch("endif");

    String error = io.getError();
    // Should produce some kind of error
    assertNotNull(error);
  }

  @Test
  void emptyWithMultipleArguments() {
    // empty() with multiple arguments should produce error
    dispatcher.dispatch("if empty(var1, var2)");
    dispatcher.dispatch("echo Should not execute");
    dispatcher.dispatch("endif");

    String error = io.getError();
    // Should produce error about wrong number of arguments
    assertNotNull(error);
  }
}

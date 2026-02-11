package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Tests for conditional control flow (if/elif/else/endif) robustness, particularly error handling
 * that was causing both branches to execute.
 */
class ConditionalFlowTest {

  static class BufferIO implements CommandDispatcher.IO {
    final List<String> output = new ArrayList<>();
    final List<String> errors = new ArrayList<>();

    @Override
    public void println(String s) {
      output.add(s);
    }

    @Override
    public void printf(String fmt, Object... args) {
      output.add(String.format(fmt, args));
    }

    @Override
    public void error(String s) {
      errors.add(s);
    }

    boolean hasOutput(String text) {
      return output.stream().anyMatch(line -> line.contains(text));
    }

    boolean hasError(String text) {
      return errors.stream().anyMatch(line -> line.contains(text));
    }

    void clear() {
      output.clear();
      errors.clear();
    }
  }

  ParsingContext ctx;
  SessionManager sm;
  BufferIO io;
  CommandDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    ctx = ParsingContext.create();
    SessionManager.JFRSessionFactory factory =
        (path, c) -> {
          JFRSession session = Mockito.mock(JFRSession.class);
          Mockito.when(session.getRecordingPath()).thenReturn(path);
          Mockito.when(session.getAvailableTypes()).thenReturn(java.util.Set.of());
          Mockito.when(session.getHandlerCount()).thenReturn(0);
          Mockito.when(session.hasRun()).thenReturn(false);
          return session;
        };
    sm = new SessionManager(ctx, factory);
    io = new BufferIO();
    dispatcher = new CommandDispatcher(sm, io, ref -> {});
  }

  // ==================== Original Bug Reproduction Tests ====================

  @Test
  void testOriginalBug_OrOperatorDoesNotExecuteBothBranches() {
    // Original bug: unsupported 'or' caused exception, conditional state not updated,
    // both branches executed
    dispatcher.dispatch("set scenario = \"ddprof_only\"");
    io.clear();

    dispatcher.dispatch(
        "if \"${scenario}\" == \"ddprof_with_tracer\" or \"${scenario}\" == \"ddprof_only\"");
    dispatcher.dispatch("echo Matched with OR");
    dispatcher.dispatch("else");
    dispatcher.dispatch("echo Did not match");
    dispatcher.dispatch("endif");

    // Should only execute the if branch
    assertTrue(io.hasOutput("Matched with OR"), "Should execute if branch");
    assertFalse(io.hasOutput("Did not match"), "Should NOT execute else branch");
  }

  @Test
  void testOriginalBug_ContainsOperatorDoesNotExecuteBothBranches() {
    // Original bug: unsupported 'contains' caused both branches to execute
    dispatcher.dispatch("set test_string = \"foo,bar,baz\"");
    io.clear();

    dispatcher.dispatch("if \"${test_string}\" contains \"bar\"");
    dispatcher.dispatch("echo Contains bar: YES");
    dispatcher.dispatch("else");
    dispatcher.dispatch("echo Contains bar: NO");
    dispatcher.dispatch("endif");

    // Should only execute the if branch
    assertTrue(io.hasOutput("Contains bar: YES"), "Should execute if branch");
    assertFalse(io.hasOutput("Contains bar: NO"), "Should NOT execute else branch");
  }

  // ==================== Error Handling Tests ====================

  @Test
  void testInvalidCondition_DoesNotExecuteBothBranches() {
    // When condition has syntax error, should treat as false and only execute else
    dispatcher.dispatch("if 1 badoperator 2");
    dispatcher.dispatch("set branch1 = \"executed\"");
    dispatcher.dispatch("else");
    dispatcher.dispatch("set branch2 = \"executed\"");
    dispatcher.dispatch("endif");

    // Should have an error
    assertFalse(io.errors.isEmpty(), "Should have error for invalid condition");

    // Should execute else branch only (condition treated as false)
    // Variable branch2 should be set, branch1 should not
    assertTrue(
        dispatcher.getGlobalStore().contains("branch2"), "branch2 should be set (else executed)");
    assertFalse(
        dispatcher.getGlobalStore().contains("branch1"),
        "branch1 should NOT be set (if not executed)");
  }

  @Test
  void testEmptyCondition_DoesNotExecuteBothBranches() {
    dispatcher.dispatch("if");
    dispatcher.dispatch("echo branch1");
    dispatcher.dispatch("else");
    dispatcher.dispatch("echo branch2");
    dispatcher.dispatch("endif");

    // Should have error for empty condition
    assertTrue(io.hasError("if requires a condition"), "Should error on empty condition");

    // Should execute else branch
    assertTrue(io.hasOutput("branch2"), "Should execute else branch");
    assertFalse(io.hasOutput("branch1"), "Should NOT execute if branch");
  }

  @Test
  void testElifAfterFailedIf_MaintainsCorrectState() {
    // If the if condition has error, elif should still work correctly
    dispatcher.dispatch("if 1 badoperator 2");
    dispatcher.dispatch("set if_branch = \"executed\"");
    dispatcher.dispatch("elif 1 == 1");
    dispatcher.dispatch("set elif_branch = \"executed\"");
    dispatcher.dispatch("else");
    dispatcher.dispatch("set else_branch = \"executed\"");
    dispatcher.dispatch("endif");

    // Should execute elif branch only
    assertTrue(dispatcher.getGlobalStore().contains("elif_branch"), "elif_branch should be set");
    assertFalse(dispatcher.getGlobalStore().contains("if_branch"), "if_branch should NOT be set");
    assertFalse(
        dispatcher.getGlobalStore().contains("else_branch"), "else_branch should NOT be set");
  }

  // ==================== Nested Conditionals Tests ====================

  @Test
  void testNestedConditionals_WithOrKeyword() {
    dispatcher.dispatch("set x = \"test\"");

    dispatcher.dispatch("if \"${x}\" == \"test\" or \"${x}\" == \"other\"");
    dispatcher.dispatch("if \"${x}\" == \"test\"");
    dispatcher.dispatch("set nested_if = \"executed\"");
    dispatcher.dispatch("else");
    dispatcher.dispatch("set nested_else = \"executed\"");
    dispatcher.dispatch("endif");
    dispatcher.dispatch("else");
    dispatcher.dispatch("set outer_else = \"executed\"");
    dispatcher.dispatch("endif");

    assertTrue(dispatcher.getGlobalStore().contains("nested_if"), "nested_if should be set");
    assertFalse(
        dispatcher.getGlobalStore().contains("nested_else"), "nested_else should NOT be set");
    assertFalse(dispatcher.getGlobalStore().contains("outer_else"), "outer_else should NOT be set");
  }

  @Test
  void testNestedConditionals_WithContains() {
    dispatcher.dispatch("set str = \"foo,bar,baz\"");

    dispatcher.dispatch("if \"${str}\" contains \"bar\"");
    dispatcher.dispatch("if \"${str}\" contains \"foo\"");
    dispatcher.dispatch("set both_found = \"yes\"");
    dispatcher.dispatch("else");
    dispatcher.dispatch("set bar_only = \"yes\"");
    dispatcher.dispatch("endif");
    dispatcher.dispatch("else");
    dispatcher.dispatch("set not_found = \"yes\"");
    dispatcher.dispatch("endif");

    assertTrue(dispatcher.getGlobalStore().contains("both_found"), "both_found should be set");
    assertFalse(dispatcher.getGlobalStore().contains("bar_only"), "bar_only should NOT be set");
    assertFalse(dispatcher.getGlobalStore().contains("not_found"), "not_found should NOT be set");
  }

  // ==================== Multiple Elif Tests ====================

  @Test
  void testMultipleElif_WithOrKeyword() {
    dispatcher.dispatch("set value = \"3\"");
    io.clear();

    dispatcher.dispatch("if \"${value}\" == \"1\" or \"${value}\" == \"2\"");
    dispatcher.dispatch("  echo branch1or2");
    dispatcher.dispatch("elif \"${value}\" == \"3\" or \"${value}\" == \"4\"");
    dispatcher.dispatch("  echo branch3or4");
    dispatcher.dispatch("elif \"${value}\" == \"5\"");
    dispatcher.dispatch("  echo branch5");
    dispatcher.dispatch("else");
    dispatcher.dispatch("  echo other");
    dispatcher.dispatch("endif");

    assertTrue(io.hasOutput("branch3or4"), "Should execute second elif");
    assertFalse(io.hasOutput("branch1or2"), "Should NOT execute if");
    assertFalse(io.hasOutput("branch5"), "Should NOT execute third elif");
    assertFalse(io.hasOutput("other"), "Should NOT execute else");
  }

  // ==================== Edge Cases ====================

  @Test
  void testMultipleConditionals_Sequential() {
    dispatcher.dispatch("set x = \"test\"");
    io.clear();

    // First conditional
    dispatcher.dispatch("if \"${x}\" == \"test\" or \"${x}\" == \"other\"");
    dispatcher.dispatch("  echo first-if");
    dispatcher.dispatch("else");
    dispatcher.dispatch("  echo first-else");
    dispatcher.dispatch("endif");

    io.clear();

    // Second conditional (should not be affected by first)
    dispatcher.dispatch("if \"${x}\" contains \"es\"");
    dispatcher.dispatch("  echo second-if");
    dispatcher.dispatch("else");
    dispatcher.dispatch("  echo second-else");
    dispatcher.dispatch("endif");

    assertTrue(io.hasOutput("second-if"), "Should execute second if");
    assertFalse(io.hasOutput("second-else"), "Should NOT execute second else");
  }

  @Test
  void testConditional_WithComplexOrExpression() {
    dispatcher.dispatch("set a = \"1\"");
    dispatcher.dispatch("set b = \"2\"");
    dispatcher.dispatch("set c = \"3\"");
    io.clear();

    dispatcher.dispatch("if \"${a}\" == \"0\" or \"${b}\" == \"0\" or \"${c}\" == \"3\"");
    dispatcher.dispatch("  echo matched");
    dispatcher.dispatch("else");
    dispatcher.dispatch("  echo not-matched");
    dispatcher.dispatch("endif");

    assertTrue(io.hasOutput("matched"), "Should match on third condition");
    assertFalse(io.hasOutput("not-matched"), "Should NOT execute else");
  }

  @Test
  void testConditional_WithComplexAndExpression() {
    dispatcher.dispatch("set a = \"1\"");
    dispatcher.dispatch("set b = \"2\"");
    dispatcher.dispatch("set c = \"3\"");
    io.clear();

    dispatcher.dispatch("if \"${a}\" == \"1\" and \"${b}\" == \"2\" and \"${c}\" == \"3\"");
    dispatcher.dispatch("  echo all-match");
    dispatcher.dispatch("else");
    dispatcher.dispatch("  echo not-all-match");
    dispatcher.dispatch("endif");

    assertTrue(io.hasOutput("all-match"), "Should match all conditions");
    assertFalse(io.hasOutput("not-all-match"), "Should NOT execute else");
  }

  @Test
  void testConditional_MixedOrAndKeywords() {
    dispatcher.dispatch("set x = \"test\"");
    io.clear();

    dispatcher.dispatch(
        "if (\"${x}\" == \"test\" or \"${x}\" == \"other\") and \"${x}\" contains \"es\"");
    dispatcher.dispatch("  echo matched");
    dispatcher.dispatch("else");
    dispatcher.dispatch("  echo not-matched");
    dispatcher.dispatch("endif");

    assertTrue(io.hasOutput("matched"), "Should match complex condition");
    assertFalse(io.hasOutput("not-matched"), "Should NOT execute else");
  }

  // ==================== Regression Tests ====================

  @Test
  void testSymbolicOperators_StillWork() {
    // Ensure || and && still work after adding keyword support
    dispatcher.dispatch("if 1 == 1 || 2 == 3");
    dispatcher.dispatch("  echo symbolic-or");
    dispatcher.dispatch("else");
    dispatcher.dispatch("  echo symbolic-or-else");
    dispatcher.dispatch("endif");

    assertTrue(io.hasOutput("symbolic-or"), "Symbolic || should still work");
    assertFalse(io.hasOutput("symbolic-or-else"), "Should NOT execute else");

    io.clear();

    dispatcher.dispatch("if 1 == 1 && 2 == 2");
    dispatcher.dispatch("  echo symbolic-and");
    dispatcher.dispatch("else");
    dispatcher.dispatch("  echo symbolic-and-else");
    dispatcher.dispatch("endif");

    assertTrue(io.hasOutput("symbolic-and"), "Symbolic && should still work");
    assertFalse(io.hasOutput("symbolic-and-else"), "Should NOT execute else");
  }

  @Test
  void testExclamationNot_StillWorks() {
    // Ensure ! still works after adding 'not' keyword
    dispatcher.dispatch("if !false");
    dispatcher.dispatch("  echo exclamation-not");
    dispatcher.dispatch("else");
    dispatcher.dispatch("  echo exclamation-not-else");
    dispatcher.dispatch("endif");

    assertTrue(io.hasOutput("exclamation-not"), "! operator should still work");
    assertFalse(io.hasOutput("exclamation-not-else"), "Should NOT execute else");
  }
}

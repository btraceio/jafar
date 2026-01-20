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
 * Tests for arithmetic expressions in conditional statements.
 *
 * <p>Supports arithmetic operations in if/elif conditions:
 *
 * <ul>
 *   <li>Addition: +
 *   <li>Subtraction: -
 *   <li>Multiplication: *
 *   <li>Division: /
 *   <li>Unary minus: -
 *   <li>Parentheses for grouping: ()
 *   <li>Operator precedence: *, / before +, -
 * </ul>
 */
class ArithmeticExpressionTest {

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

  // ==================== Basic Addition ====================

  @Test
  void additionBasic() {
    dispatcher.dispatch("if 2 + 3 == 5");
    dispatcher.dispatch("echo Addition works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Addition works"), "2 + 3 should equal 5");
  }

  @Test
  void additionMultipleTerms() {
    dispatcher.dispatch("if 1 + 2 + 3 + 4 == 10");
    dispatcher.dispatch("echo Multiple addition works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Multiple addition works"), "1+2+3+4 should equal 10");
  }

  @Test
  void additionWithNegativeResult() {
    dispatcher.dispatch("if -5 + 3 == -2");
    dispatcher.dispatch("echo Negative addition works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Negative addition works"), "-5 + 3 should equal -2");
  }

  // ==================== Basic Subtraction ====================

  @Test
  void subtractionBasic() {
    dispatcher.dispatch("if 10 - 3 == 7");
    dispatcher.dispatch("echo Subtraction works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Subtraction works"), "10 - 3 should equal 7");
  }

  @Test
  void subtractionToNegative() {
    dispatcher.dispatch("if 5 - 10 == -5");
    dispatcher.dispatch("echo Subtraction to negative works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Subtraction to negative works"), "5 - 10 should equal -5");
  }

  @Test
  void subtractionChained() {
    dispatcher.dispatch("if 20 - 5 - 3 - 2 == 10");
    dispatcher.dispatch("echo Chained subtraction works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Chained subtraction works"), "20-5-3-2 should equal 10");
  }

  // ==================== Basic Multiplication ====================

  @Test
  void multiplicationBasic() {
    dispatcher.dispatch("if 3 * 4 == 12");
    dispatcher.dispatch("echo Multiplication works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Multiplication works"), "3 * 4 should equal 12");
  }

  @Test
  void multiplicationByZero() {
    dispatcher.dispatch("if 5 * 0 == 0");
    dispatcher.dispatch("echo Multiply by zero works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Multiply by zero works"), "5 * 0 should equal 0");
  }

  @Test
  void multiplicationNegative() {
    dispatcher.dispatch("if -3 * 4 == -12");
    dispatcher.dispatch("echo Negative multiplication works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Negative multiplication works"), "-3 * 4 should equal -12");
  }

  // ==================== Basic Division ====================

  @Test
  void divisionBasic() {
    dispatcher.dispatch("if 20 / 4 == 5");
    dispatcher.dispatch("echo Division works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Division works"), "20 / 4 should equal 5");
  }

  @Test
  void divisionWithRemainder() {
    dispatcher.dispatch("if 10 / 3 > 3 and 10 / 3 < 4");
    dispatcher.dispatch("echo Division with remainder works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Division with remainder works"), "10 / 3 should be ~3.33");
  }

  @Test
  void divisionNegative() {
    dispatcher.dispatch("if -20 / 4 == -5");
    dispatcher.dispatch("echo Negative division works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Negative division works"), "-20 / 4 should equal -5");
  }

  @Test
  void divisionByZeroError() {
    // Division by zero should produce an error
    dispatcher.dispatch("if 10 / 0 == 0");
    dispatcher.dispatch("echo Should not print");
    dispatcher.dispatch("endif");

    String error = io.getError();
    // Should contain division by zero error
    assertTrue(
        error.length() > 0 || !io.getOutput().contains("Should not print"),
        "Division by zero should error or not execute");
  }

  // ==================== Operator Precedence ====================

  @Test
  void precedenceMultiplicationBeforeAddition() {
    dispatcher.dispatch("if 2 + 3 * 4 == 14");
    dispatcher.dispatch("echo Precedence works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Precedence works"), "2 + 3*4 should be 2 + 12 = 14");
  }

  @Test
  void precedenceDivisionBeforeSubtraction() {
    dispatcher.dispatch("if 20 - 10 / 2 == 15");
    dispatcher.dispatch("echo Division precedence works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Division precedence works"), "20 - 10/2 should be 20 - 5 = 15");
  }

  @Test
  void precedenceLeftToRightSamePrecedence() {
    dispatcher.dispatch("if 10 - 3 - 2 == 5");
    dispatcher.dispatch("echo Left-to-right works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Left-to-right works"), "10 - 3 - 2 should be (10-3)-2 = 5");
  }

  // ==================== Parentheses ====================

  @Test
  void parenthesesOverridePrecedence() {
    dispatcher.dispatch("if (2 + 3) * 4 == 20");
    dispatcher.dispatch("echo Parentheses work");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Parentheses work"), "(2+3)*4 should be 5*4 = 20");
  }

  @Test
  void nestedParentheses() {
    dispatcher.dispatch("if ((2 + 3) * (4 + 1)) == 25");
    dispatcher.dispatch("echo Nested parentheses work");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Nested parentheses work"), "((2+3)*(4+1)) should be 5*5 = 25");
  }

  @Test
  void complexNestedParentheses() {
    dispatcher.dispatch("if (10 - (3 + 2)) * 2 == 10");
    dispatcher.dispatch("echo Complex nesting works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Complex nesting works"), "(10-(3+2))*2 should be 5*2 = 10");
  }

  // ==================== Unary Minus ====================

  @Test
  void unaryMinusBasic() {
    dispatcher.dispatch("if -5 + 10 == 5");
    dispatcher.dispatch("echo Unary minus works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Unary minus works"), "-5 + 10 should equal 5");
  }

  @Test
  void unaryMinusDouble() {
    dispatcher.dispatch("if --5 == 5");
    dispatcher.dispatch("echo Double negation works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Double negation works"), "--5 should equal 5");
  }

  @Test
  void unaryMinusInExpression() {
    dispatcher.dispatch("if -(3 + 2) == -5");
    dispatcher.dispatch("echo Unary minus on expression works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Unary minus on expression works"), "-(3+2) should equal -5");
  }

  // ==================== Mixed Operations ====================

  @Test
  void mixedAllOperations() {
    dispatcher.dispatch("if 10 + 5 * 2 - 8 / 2 == 16");
    dispatcher.dispatch("echo Mixed operations work");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Mixed operations work"), "10 + 5*2 - 8/2 should be 10+10-4 = 16");
  }

  @Test
  void complexMixedExpression() {
    dispatcher.dispatch("if (10 + 5) * 2 - (8 / 2 + 1) == 25");
    dispatcher.dispatch("echo Complex mixed works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(
        output.contains("Complex mixed works"), "(10+5)*2 - (8/2+1) should be 30 - 5 = 25");
  }

  // ==================== Arithmetic with Variables ====================

  @Test
  void arithmeticWithVariables() {
    dispatcher.dispatch("set a = 10");
    dispatcher.dispatch("set b = 5");
    dispatcher.dispatch("if ${a} + ${b} == 15");
    dispatcher.dispatch("echo Variable arithmetic works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Variable arithmetic works"), "10 + 5 should equal 15");
  }

  @Test
  void arithmeticWithVariablesComplex() {
    dispatcher.dispatch("set x = 3");
    dispatcher.dispatch("set y = 4");
    dispatcher.dispatch("if ${x} * ${x} + ${y} * ${y} == 25");
    dispatcher.dispatch("echo Pythagorean works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Pythagorean works"), "3*3 + 4*4 should equal 25");
  }

  // ==================== Comparison Operators with Arithmetic ====================

  @Test
  void arithmeticLessThan() {
    dispatcher.dispatch("if 3 + 2 < 10");
    dispatcher.dispatch("echo Less than works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Less than works"), "3 + 2 < 10 should be true");
  }

  @Test
  void arithmeticGreaterThan() {
    dispatcher.dispatch("if 5 * 3 > 10");
    dispatcher.dispatch("echo Greater than works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Greater than works"), "5 * 3 > 10 should be true");
  }

  @Test
  void arithmeticLessOrEqual() {
    dispatcher.dispatch("if 10 / 2 <= 5");
    dispatcher.dispatch("echo Less or equal works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Less or equal works"), "10 / 2 <= 5 should be true");
  }

  @Test
  void arithmeticGreaterOrEqual() {
    dispatcher.dispatch("if 3 * 4 >= 12");
    dispatcher.dispatch("echo Greater or equal works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Greater or equal works"), "3 * 4 >= 12 should be true");
  }

  @Test
  void arithmeticNotEqual() {
    dispatcher.dispatch("if 5 + 3 != 9");
    dispatcher.dispatch("echo Not equal works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Not equal works"), "5 + 3 != 9 should be true");
  }

  // ==================== Arithmetic in Both Sides of Comparison ====================

  @Test
  void arithmeticBothSides() {
    dispatcher.dispatch("if 2 + 3 == 10 - 5");
    dispatcher.dispatch("echo Both sides arithmetic works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Both sides arithmetic works"), "2 + 3 should equal 10 - 5");
  }

  @Test
  void complexBothSides() {
    dispatcher.dispatch("if (3 * 4) - 2 == (10 / 2) + 5");
    dispatcher.dispatch("echo Complex both sides works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Complex both sides works"), "(3*4)-2 should equal (10/2)+5");
  }

  // ==================== Arithmetic with Logical Operators ====================

  @Test
  void arithmeticWithAnd() {
    dispatcher.dispatch("if 2 + 2 == 4 and 3 * 3 == 9");
    dispatcher.dispatch("echo Arithmetic with AND works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Arithmetic with AND works"), "Both conditions should be true");
  }

  @Test
  void arithmeticWithOr() {
    dispatcher.dispatch("if 2 + 2 == 5 or 3 * 3 == 9");
    dispatcher.dispatch("echo Arithmetic with OR works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Arithmetic with OR works"), "Second condition should be true");
  }

  @Test
  void arithmeticWithNot() {
    dispatcher.dispatch("if not 2 + 2 == 5");
    dispatcher.dispatch("echo Arithmetic with NOT works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Arithmetic with NOT works"), "2 + 2 != 5 should be true");
  }

  // ==================== Floating Point Arithmetic ====================

  @Test
  void floatingPointDivision() {
    dispatcher.dispatch("if 10 / 4 > 2 and 10 / 4 < 3");
    dispatcher.dispatch("echo Floating point works");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Floating point works"), "10 / 4 should be 2.5");
  }

  // ==================== Edge Cases ====================

  @Test
  void largeNumbers() {
    dispatcher.dispatch("if 1000000 + 1000000 == 2000000");
    dispatcher.dispatch("echo Large numbers work");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Large numbers work"), "Large number addition should work");
  }

  @Test
  void zeroOperations() {
    dispatcher.dispatch("if 0 + 0 == 0 and 0 * 5 == 0");
    dispatcher.dispatch("echo Zero operations work");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Zero operations work"), "Operations with zero should work");
  }

  @Test
  void complexExpressionWithSpaces() {
    dispatcher.dispatch("if ( 10 + 5 ) * 2 == 30");
    dispatcher.dispatch("echo Spaces handled correctly");
    dispatcher.dispatch("endif");

    String output = io.getOutput();
    assertTrue(output.contains("Spaces handled correctly"), "Should handle spaces in expression");
  }
}

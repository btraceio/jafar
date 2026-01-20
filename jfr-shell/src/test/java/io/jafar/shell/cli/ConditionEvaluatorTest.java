package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.shell.core.VariableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for ConditionEvaluator, focusing on keyword operators (or, and, not, contains) and error
 * handling.
 */
class ConditionEvaluatorTest {

  private ConditionEvaluator evaluator;
  private VariableStore sessionStore;
  private VariableStore globalStore;

  @BeforeEach
  void setUp() {
    sessionStore = new VariableStore();
    globalStore = new VariableStore();
    evaluator = new ConditionEvaluator(sessionStore, globalStore);
  }

  // ==================== OR Keyword Tests ====================

  @Test
  void testOrKeyword_TrueOrFalse() throws Exception {
    assertTrue(evaluator.evaluate("1 == 1 or 2 == 3"));
  }

  @Test
  void testOrKeyword_FalseOrTrue() throws Exception {
    assertTrue(evaluator.evaluate("1 == 2 or 2 == 2"));
  }

  @Test
  void testOrKeyword_BothTrue() throws Exception {
    assertTrue(evaluator.evaluate("1 == 1 or 2 == 2"));
  }

  @Test
  void testOrKeyword_BothFalse() throws Exception {
    assertFalse(evaluator.evaluate("1 == 2 or 2 == 3"));
  }

  @Test
  void testOrKeyword_MultipleOrs() throws Exception {
    assertTrue(evaluator.evaluate("1 == 2 or 2 == 3 or 3 == 3"));
    assertFalse(evaluator.evaluate("1 == 2 or 2 == 3 or 3 == 4"));
  }

  @Test
  void testOrKeyword_CaseInsensitive() throws Exception {
    assertTrue(evaluator.evaluate("1 == 1 OR 2 == 3"));
    assertTrue(evaluator.evaluate("1 == 1 Or 2 == 3"));
    assertTrue(evaluator.evaluate("1 == 1 oR 2 == 3"));
  }

  @Test
  void testOrKeyword_WithStrings() throws Exception {
    assertTrue(evaluator.evaluate("\"test\" == \"test\" or \"foo\" == \"bar\""));
    assertTrue(evaluator.evaluate("\"test\" == \"other\" or \"foo\" == \"foo\""));
    assertFalse(evaluator.evaluate("\"test\" == \"other\" or \"foo\" == \"bar\""));
  }

  @Test
  void testOrKeyword_MixedWithSymbolic() throws Exception {
    assertTrue(evaluator.evaluate("1 == 1 || 2 == 3 or 3 == 4"));
    assertTrue(evaluator.evaluate("1 == 2 or 2 == 3 || 3 == 3"));
  }

  // ==================== AND Keyword Tests ====================

  @Test
  void testAndKeyword_BothTrue() throws Exception {
    assertTrue(evaluator.evaluate("1 == 1 and 2 == 2"));
  }

  @Test
  void testAndKeyword_TrueAndFalse() throws Exception {
    assertFalse(evaluator.evaluate("1 == 1 and 2 == 3"));
  }

  @Test
  void testAndKeyword_FalseAndTrue() throws Exception {
    assertFalse(evaluator.evaluate("1 == 2 and 2 == 2"));
  }

  @Test
  void testAndKeyword_BothFalse() throws Exception {
    assertFalse(evaluator.evaluate("1 == 2 and 2 == 3"));
  }

  @Test
  void testAndKeyword_MultipleAnds() throws Exception {
    assertTrue(evaluator.evaluate("1 == 1 and 2 == 2 and 3 == 3"));
    assertFalse(evaluator.evaluate("1 == 1 and 2 == 2 and 3 == 4"));
  }

  @Test
  void testAndKeyword_CaseInsensitive() throws Exception {
    assertTrue(evaluator.evaluate("1 == 1 AND 2 == 2"));
    assertTrue(evaluator.evaluate("1 == 1 And 2 == 2"));
    assertTrue(evaluator.evaluate("1 == 1 aNd 2 == 2"));
  }

  @Test
  void testAndKeyword_MixedWithSymbolic() throws Exception {
    assertTrue(evaluator.evaluate("1 == 1 && 2 == 2 and 3 == 3"));
    assertFalse(evaluator.evaluate("1 == 1 and 2 == 2 && 3 == 4"));
  }

  // ==================== NOT Keyword Tests ====================

  @Test
  void testNotKeyword_NotTrue() throws Exception {
    assertFalse(evaluator.evaluate("not 1 == 1"));
  }

  @Test
  void testNotKeyword_NotFalse() throws Exception {
    assertTrue(evaluator.evaluate("not 1 == 2"));
  }

  @Test
  void testNotKeyword_DoubleNegative() throws Exception {
    assertTrue(evaluator.evaluate("not not 1 == 1"));
  }

  @Test
  void testNotKeyword_CaseInsensitive() throws Exception {
    assertTrue(evaluator.evaluate("NOT 1 == 2"));
    assertTrue(evaluator.evaluate("Not 1 == 2"));
    assertTrue(evaluator.evaluate("nOt 1 == 2"));
  }

  @Test
  void testNotKeyword_MixedWithSymbolic() throws Exception {
    assertFalse(evaluator.evaluate("!not 1 == 2"));
    assertTrue(evaluator.evaluate("not !1 == 1"));
  }

  // ==================== CONTAINS Operator Tests ====================

  @Test
  void testContains_StringContainsSubstring() throws Exception {
    assertTrue(evaluator.evaluate("\"foo,bar,baz\" contains \"bar\""));
  }

  @Test
  void testContains_StringDoesNotContainSubstring() throws Exception {
    assertFalse(evaluator.evaluate("\"foo,bar,baz\" contains \"qux\""));
  }

  @Test
  void testContains_EmptyString() throws Exception {
    assertTrue(evaluator.evaluate("\"test\" contains \"\""));
  }

  @Test
  void testContains_ContainsSelf() throws Exception {
    assertTrue(evaluator.evaluate("\"test\" contains \"test\""));
  }

  @Test
  void testContains_CaseSensitive() throws Exception {
    assertFalse(evaluator.evaluate("\"FooBar\" contains \"foo\""));
    assertTrue(evaluator.evaluate("\"FooBar\" contains \"Foo\""));
  }

  @Test
  void testContains_CaseInsensitiveKeyword() throws Exception {
    assertTrue(evaluator.evaluate("\"test\" CONTAINS \"es\""));
    assertTrue(evaluator.evaluate("\"test\" Contains \"es\""));
    assertTrue(evaluator.evaluate("\"test\" cOntAins \"es\""));
  }

  @Test
  void testContains_WithVariables() throws Exception {
    // This would need variable substitution to be done before evaluation
    // Testing with literal values for now
    assertTrue(evaluator.evaluate("\"ddprof_only\" contains \"only\""));
    assertTrue(evaluator.evaluate("\"ddprof_with_tracer\" contains \"tracer\""));
  }

  @Test
  void testContains_NumbersAsStrings() throws Exception {
    assertTrue(evaluator.evaluate("\"12345\" contains \"234\""));
    assertFalse(evaluator.evaluate("\"12345\" contains \"987\""));
  }

  // ==================== Combined Operators Tests ====================

  @Test
  void testCombined_OrAndAnd() throws Exception {
    // (1 == 1 and 2 == 2) or 3 == 4
    assertTrue(evaluator.evaluate("1 == 1 and 2 == 2 or 3 == 4"));
  }

  @Test
  void testCombined_AndOrOr() throws Exception {
    // Parsed as: (1 == 2 and 2 == 3) or 3 == 3 due to AND having higher precedence
    // (false and false) or true = true
    assertTrue(evaluator.evaluate("1 == 2 and 2 == 3 or 3 == 3"));
  }

  @Test
  void testCombined_NotAndOr() throws Exception {
    assertTrue(evaluator.evaluate("not 1 == 2 and 2 == 2 or 3 == 4"));
    assertTrue(evaluator.evaluate("not (1 == 1 and 2 == 3)"));
  }

  @Test
  void testCombined_ContainsAndOr() throws Exception {
    assertTrue(evaluator.evaluate("\"test\" contains \"es\" and 1 == 1"));
    assertTrue(evaluator.evaluate("\"test\" contains \"xx\" or 1 == 1"));
    assertFalse(evaluator.evaluate("\"test\" contains \"xx\" and 1 == 1"));
  }

  @Test
  void testCombined_AllKeywords() throws Exception {
    // not ("foo" contains "xx") and ("bar" contains "ar" or 1 == 2)
    assertTrue(
        evaluator.evaluate("not \"foo\" contains \"xx\" and \"bar\" contains \"ar\" or 1 == 2"));
  }

  // ==================== Word Boundary Tests ====================

  @Test
  void testWordBoundary_OrInString() throws Exception {
    // "fork" contains "or" but when used in string, or should work as keyword
    assertTrue(evaluator.evaluate("\"fork\" == \"fork\" or 2 == 2"));
  }

  @Test
  void testWordBoundary_AndInString() throws Exception {
    // "band" contains "and" but when used in string, and should work as keyword
    assertTrue(evaluator.evaluate("\"band\" == \"band\" and 2 == 2"));
  }

  // ==================== Original Bug Reproduction Tests ====================

  @Test
  void testOriginalBug1_OrOperatorWithStrings() throws Exception {
    // Reproduces: if "${scenario}" == "ddprof_with_tracer" or "${scenario}" == "ddprof_only"
    // When ${scenario} = "ddprof_only"
    String condition =
        "\"ddprof_only\" == \"ddprof_with_tracer\" or \"ddprof_only\" == \"ddprof_only\"";
    assertTrue(evaluator.evaluate(condition));
  }

  @Test
  void testOriginalBug2_ContainsOperator() throws Exception {
    // Reproduces: if "${test_string}" contains "bar"
    // When ${test_string} = "foo,bar,baz"
    String condition = "\"foo,bar,baz\" contains \"bar\"";
    assertTrue(evaluator.evaluate(condition));
  }

  @Test
  void testOriginalBug2_ContainsOperatorFalse() throws Exception {
    // Should return false when substring not found
    String condition = "\"foo,bar,baz\" contains \"notfound\"";
    assertFalse(evaluator.evaluate(condition));
  }

  // ==================== Edge Cases ====================

  @Test
  void testEdgeCase_EmptyCondition() {
    Exception exception = assertThrows(Exception.class, () -> evaluator.evaluate(""));
    assertNotNull(exception);
  }

  @Test
  void testEdgeCase_OnlyWhitespace() {
    Exception exception = assertThrows(Exception.class, () -> evaluator.evaluate("   "));
    assertNotNull(exception);
  }

  @Test
  void testEdgeCase_TrailingOperator() {
    Exception exception = assertThrows(Exception.class, () -> evaluator.evaluate("1 == 1 or"));
    assertNotNull(exception);
  }

  @Test
  void testEdgeCase_LeadingOperator() {
    Exception exception = assertThrows(Exception.class, () -> evaluator.evaluate("or 1 == 1"));
    assertNotNull(exception);
  }

  @Test
  void testEdgeCase_ConsecutiveOperators() {
    Exception exception =
        assertThrows(Exception.class, () -> evaluator.evaluate("1 == 1 or and 2 == 2"));
    assertNotNull(exception);
  }

  // ==================== Parentheses Tests ====================

  @Test
  void testParentheses_OrPrecedence() throws Exception {
    // 1 == 2 or (2 == 2 and 3 == 3)
    assertTrue(evaluator.evaluate("1 == 2 or (2 == 2 and 3 == 3)"));
  }

  @Test
  void testParentheses_AndPrecedence() throws Exception {
    // (1 == 1 or 2 == 2) and 3 == 3
    assertTrue(evaluator.evaluate("(1 == 1 or 2 == 2) and 3 == 3"));
  }

  @Test
  void testParentheses_NotWithParens() throws Exception {
    assertTrue(evaluator.evaluate("not (1 == 2)"));
    assertFalse(evaluator.evaluate("not (1 == 1)"));
  }

  @Test
  void testParentheses_ContainsWithParens() throws Exception {
    assertTrue(evaluator.evaluate("(\"test\" contains \"es\")"));
  }
}

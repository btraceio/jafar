package io.jafar.shell.cli.completion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive tests for JfrPathTokenizer.
 *
 * <p>Tests cover:
 *
 * <ul>
 *   <li>Basic tokenization of all token types
 *   <li>String literals with quotes and escapes
 *   <li>Number literals (integers, decimals, negatives)
 *   <li>Multi-character operators
 *   <li>Edge cases (empty line, trailing whitespace, unclosed strings)
 *   <li>Position mapping accuracy
 *   <li>Cursor tracking
 *   <li>Real JfrPath expression examples
 * </ul>
 */
class JfrPathTokenizerTest {

  private JfrPathTokenizer tokenizer;

  @BeforeEach
  void setup() {
    tokenizer = new JfrPathTokenizer();
  }

  // Basic Tokenization Tests

  @Test
  void tokenizesEmptyLine() {
    List<Token> tokens = tokenizer.tokenize("");

    assertEquals(1, tokens.size());
    assertEquals(TokenType.EOF, tokens.get(0).type());
  }

  @Test
  void tokenizesNullLine() {
    List<Token> tokens = tokenizer.tokenize(null);

    assertEquals(1, tokens.size());
    assertEquals(TokenType.EOF, tokens.get(0).type());
  }

  @Test
  void tokenizesSimpleIdentifier() {
    List<Token> tokens = tokenizer.tokenize("events");

    assertEquals(2, tokens.size());
    assertEquals(TokenType.IDENTIFIER, tokens.get(0).type());
    assertEquals("events", tokens.get(0).value());
    assertEquals(0, tokens.get(0).start());
    assertEquals(6, tokens.get(0).end());
    assertEquals(TokenType.EOF, tokens.get(1).type());
  }

  @Test
  void tokenizesBasicPath() {
    List<Token> tokens = tokenizer.tokenize("events/jdk.ExecutionSample");

    assertEquals(4, tokens.size());
    assertEquals(TokenType.IDENTIFIER, tokens.get(0).type());
    assertEquals("events", tokens.get(0).value());
    assertEquals(TokenType.SLASH, tokens.get(1).type());
    assertEquals("/", tokens.get(1).value());
    assertEquals(TokenType.IDENTIFIER, tokens.get(2).type());
    assertEquals("jdk.ExecutionSample", tokens.get(2).value());
    assertEquals(TokenType.EOF, tokens.get(3).type());
  }

  @Test
  void tokenizesPathWithField() {
    List<Token> tokens = tokenizer.tokenize("events/jdk.FileRead/path");

    assertEquals(6, tokens.size());
    assertEquals(TokenType.IDENTIFIER, tokens.get(0).type());
    assertEquals("events", tokens.get(0).value());
    assertEquals(TokenType.SLASH, tokens.get(1).type());
    assertEquals(TokenType.IDENTIFIER, tokens.get(2).type());
    assertEquals("jdk.FileRead", tokens.get(2).value());
    assertEquals(TokenType.SLASH, tokens.get(3).type());
    assertEquals(TokenType.IDENTIFIER, tokens.get(4).type());
    assertEquals("path", tokens.get(4).value());
    assertEquals(TokenType.EOF, tokens.get(5).type());
  }

  @Test
  void tokenizesWhitespace() {
    List<Token> tokens = tokenizer.tokenize("show  events");

    assertEquals(4, tokens.size());
    assertEquals(TokenType.IDENTIFIER, tokens.get(0).type());
    assertEquals("show", tokens.get(0).value());
    assertEquals(TokenType.WHITESPACE, tokens.get(1).type());
    assertEquals("  ", tokens.get(1).value());
    assertEquals(TokenType.IDENTIFIER, tokens.get(2).type());
    assertEquals("events", tokens.get(2).value());
  }

  // Structure Token Tests

  @Test
  void tokenizesAllStructureTokens() {
    List<Token> tokens = tokenizer.tokenize("/|[](),");

    assertEquals(8, tokens.size());
    assertEquals(TokenType.SLASH, tokens.get(0).type());
    assertEquals(TokenType.PIPE, tokens.get(1).type());
    assertEquals(TokenType.BRACKET_OPEN, tokens.get(2).type());
    assertEquals(TokenType.BRACKET_CLOSE, tokens.get(3).type());
    assertEquals(TokenType.PAREN_OPEN, tokens.get(4).type());
    assertEquals(TokenType.PAREN_CLOSE, tokens.get(5).type());
    assertEquals(TokenType.COMMA, tokens.get(6).type());
    assertEquals(TokenType.EOF, tokens.get(7).type());
  }

  // Operator Token Tests

  @Test
  void tokenizesSingleCharOperators() {
    List<Token> tokens = tokenizer.tokenize("= > < ~ !");

    assertEquals(10, tokens.size()); // 5 operators + 4 whitespace + EOF
    assertEquals(TokenType.EQUALS, tokens.get(0).type());
    assertEquals(TokenType.GT, tokens.get(2).type());
    assertEquals(TokenType.LT, tokens.get(4).type());
    assertEquals(TokenType.TILDE, tokens.get(6).type());
    assertEquals(TokenType.NOT, tokens.get(8).type());
  }

  @Test
  void tokenizesTwoCharOperators() {
    List<Token> tokens = tokenizer.tokenize("== != >= <= && ||");

    assertEquals(12, tokens.size()); // 6 operators + 5 whitespace + EOF
    assertEquals(TokenType.DOUBLE_EQUALS, tokens.get(0).type());
    assertEquals("==", tokens.get(0).value());
    assertEquals(TokenType.NOT_EQUALS, tokens.get(2).type());
    assertEquals("!=", tokens.get(2).value());
    assertEquals(TokenType.GTE, tokens.get(4).type());
    assertEquals(">=", tokens.get(4).value());
    assertEquals(TokenType.LTE, tokens.get(6).type());
    assertEquals("<=", tokens.get(6).value());
    assertEquals(TokenType.AND, tokens.get(8).type());
    assertEquals("&&", tokens.get(8).value());
    assertEquals(TokenType.OR, tokens.get(10).type());
    assertEquals("||", tokens.get(10).value());
  }

  @Test
  void distinguishesBetweenEqualsAndDoubleEquals() {
    List<Token> tokens = tokenizer.tokenize("a=b==c");

    assertEquals(6, tokens.size());
    assertEquals(TokenType.IDENTIFIER, tokens.get(0).type());
    assertEquals("a", tokens.get(0).value());
    assertEquals(TokenType.EQUALS, tokens.get(1).type());
    assertEquals("=", tokens.get(1).value());
    assertEquals(TokenType.IDENTIFIER, tokens.get(2).type());
    assertEquals("b", tokens.get(2).value());
    assertEquals(TokenType.DOUBLE_EQUALS, tokens.get(3).type());
    assertEquals("==", tokens.get(3).value());
    assertEquals(TokenType.IDENTIFIER, tokens.get(4).type());
    assertEquals("c", tokens.get(4).value());
  }

  @Test
  void distinguishesBetweenGTAndGTE() {
    List<Token> tokens = tokenizer.tokenize("a>b>=c");

    assertEquals(6, tokens.size());
    assertEquals(TokenType.GT, tokens.get(1).type());
    assertEquals(">", tokens.get(1).value());
    assertEquals(TokenType.GTE, tokens.get(3).type());
    assertEquals(">=", tokens.get(3).value());
  }

  // String Literal Tests

  @Test
  void tokenizesDoubleQuotedString() {
    List<Token> tokens = tokenizer.tokenize("\"hello world\"");

    assertEquals(2, tokens.size());
    assertEquals(TokenType.STRING_LITERAL, tokens.get(0).type());
    assertEquals("\"hello world\"", tokens.get(0).value());
    assertEquals(0, tokens.get(0).start());
    assertEquals(13, tokens.get(0).end());
  }

  @Test
  void tokenizesSingleQuotedString() {
    List<Token> tokens = tokenizer.tokenize("'hello world'");

    assertEquals(2, tokens.size());
    assertEquals(TokenType.STRING_LITERAL, tokens.get(0).type());
    assertEquals("'hello world'", tokens.get(0).value());
  }

  @Test
  void tokenizesStringWithEscapedQuote() {
    List<Token> tokens = tokenizer.tokenize("\"hello \\\" world\"");

    assertEquals(2, tokens.size());
    assertEquals(TokenType.STRING_LITERAL, tokens.get(0).type());
    assertEquals("\"hello \\\" world\"", tokens.get(0).value());
  }

  @Test
  void tokenizesStringWithEscapedBackslash() {
    List<Token> tokens = tokenizer.tokenize("\"path\\\\to\\\\file\"");

    assertEquals(2, tokens.size());
    assertEquals(TokenType.STRING_LITERAL, tokens.get(0).type());
    assertEquals("\"path\\\\to\\\\file\"", tokens.get(0).value());
  }

  @Test
  void tokenizesUnclosedString() {
    // Unclosed strings should consume to end of line
    List<Token> tokens = tokenizer.tokenize("\"hello world");

    assertEquals(2, tokens.size());
    assertEquals(TokenType.STRING_LITERAL, tokens.get(0).type());
    assertEquals("\"hello world", tokens.get(0).value());
  }

  @Test
  void tokenizesEmptyString() {
    List<Token> tokens = tokenizer.tokenize("\"\"");

    assertEquals(2, tokens.size());
    assertEquals(TokenType.STRING_LITERAL, tokens.get(0).type());
    assertEquals("\"\"", tokens.get(0).value());
  }

  // Number Literal Tests

  @Test
  void tokenizesPositiveInteger() {
    List<Token> tokens = tokenizer.tokenize("123");

    assertEquals(2, tokens.size());
    assertEquals(TokenType.NUMBER, tokens.get(0).type());
    assertEquals("123", tokens.get(0).value());
  }

  @Test
  void tokenizesNegativeInteger() {
    List<Token> tokens = tokenizer.tokenize("-456");

    assertEquals(2, tokens.size());
    assertEquals(TokenType.NUMBER, tokens.get(0).type());
    assertEquals("-456", tokens.get(0).value());
  }

  @Test
  void tokenizesDecimalNumber() {
    List<Token> tokens = tokenizer.tokenize("123.456");

    assertEquals(2, tokens.size());
    assertEquals(TokenType.NUMBER, tokens.get(0).type());
    assertEquals("123.456", tokens.get(0).value());
  }

  @Test
  void tokenizesNegativeDecimal() {
    List<Token> tokens = tokenizer.tokenize("-123.456");

    assertEquals(2, tokens.size());
    assertEquals(TokenType.NUMBER, tokens.get(0).type());
    assertEquals("-123.456", tokens.get(0).value());
  }

  @Test
  void distinguishesMinusFromNegativeNumber() {
    // When '-' is followed by a digit, it's tokenized as negative number
    List<Token> tokens = tokenizer.tokenize("a-123");

    assertEquals(3, tokens.size());
    assertEquals(TokenType.IDENTIFIER, tokens.get(0).type());
    assertEquals("a", tokens.get(0).value());
    assertEquals(TokenType.NUMBER, tokens.get(1).type());
    assertEquals("-123", tokens.get(1).value());
    assertEquals(TokenType.EOF, tokens.get(2).type());
  }

  // Option Tests

  @Test
  void tokenizesOption() {
    List<Token> tokens = tokenizer.tokenize("--format");

    assertEquals(2, tokens.size());
    assertEquals(TokenType.OPTION, tokens.get(0).type());
    assertEquals("--format", tokens.get(0).value());
  }

  @Test
  void tokenizesOptionWithHyphens() {
    List<Token> tokens = tokenizer.tokenize("--some-long-option");

    assertEquals(2, tokens.size());
    assertEquals(TokenType.OPTION, tokens.get(0).type());
    assertEquals("--some-long-option", tokens.get(0).value());
  }

  @Test
  void distinguishesSingleHyphenFromOption() {
    List<Token> tokens = tokenizer.tokenize("-123");

    assertEquals(2, tokens.size());
    assertEquals(TokenType.NUMBER, tokens.get(0).type());
    assertEquals("-123", tokens.get(0).value());
  }

  // Identifier Tests

  @Test
  void tokenizesIdentifierWithDots() {
    List<Token> tokens = tokenizer.tokenize("jdk.ExecutionSample");

    assertEquals(2, tokens.size());
    assertEquals(TokenType.IDENTIFIER, tokens.get(0).type());
    assertEquals("jdk.ExecutionSample", tokens.get(0).value());
  }

  @Test
  void tokenizesIdentifierWithDollarSign() {
    List<Token> tokens = tokenizer.tokenize("$decorator.field");

    assertEquals(2, tokens.size());
    assertEquals(TokenType.IDENTIFIER, tokens.get(0).type());
    assertEquals("$decorator.field", tokens.get(0).value());
  }

  @Test
  void tokenizesIdentifierWithUnderscore() {
    List<Token> tokens = tokenizer.tokenize("my_variable");

    assertEquals(2, tokens.size());
    assertEquals(TokenType.IDENTIFIER, tokens.get(0).type());
    assertEquals("my_variable", tokens.get(0).value());
  }

  // Position Mapping Tests

  @Test
  void maintainsCorrectPositions() {
    List<Token> tokens = tokenizer.tokenize("abc / def");

    assertEquals(6, tokens.size());
    // "abc"
    assertEquals(0, tokens.get(0).start());
    assertEquals(3, tokens.get(0).end());
    // " "
    assertEquals(3, tokens.get(1).start());
    assertEquals(4, tokens.get(1).end());
    // "/"
    assertEquals(4, tokens.get(2).start());
    assertEquals(5, tokens.get(2).end());
    // " "
    assertEquals(5, tokens.get(3).start());
    assertEquals(6, tokens.get(3).end());
    // "def"
    assertEquals(6, tokens.get(4).start());
    assertEquals(9, tokens.get(4).end());
    // EOF
    assertEquals(9, tokens.get(5).start());
    assertEquals(9, tokens.get(5).end());
  }

  @Test
  void tokenPositionsNeverOverlap() {
    List<Token> tokens = tokenizer.tokenize("show events/jdk.FileRead[path~\".*\"]|count()");

    for (int i = 0; i < tokens.size() - 1; i++) {
      Token current = tokens.get(i);
      Token next = tokens.get(i + 1);
      assertTrue(current.end() <= next.start(), "Token " + current + " overlaps with " + next);
    }
  }

  @Test
  void tokensCoverEntireLine() {
    String line = "show events/jdk.FileRead";
    List<Token> tokens = tokenizer.tokenize(line);

    // Remove EOF token
    tokens = tokens.subList(0, tokens.size() - 1);

    // Check that tokens cover entire line
    int coveredChars = 0;
    for (Token token : tokens) {
      assertEquals(coveredChars, token.start(), "Gap in token coverage");
      coveredChars = token.end();
    }
    assertEquals(line.length(), coveredChars, "Tokens don't cover entire line");
  }

  // Cursor Tracking Tests

  @Test
  void findsTokenAtCursorStart() {
    List<Token> tokens = tokenizer.tokenize("abc def");
    Token token = tokenizer.tokenAtCursor(tokens, 0);

    assertNotNull(token);
    assertEquals(TokenType.IDENTIFIER, token.type());
    assertEquals("abc", token.value());
  }

  @Test
  void findsTokenAtCursorMiddle() {
    List<Token> tokens = tokenizer.tokenize("abc def");
    Token token = tokenizer.tokenAtCursor(tokens, 5);

    assertNotNull(token);
    assertEquals(TokenType.IDENTIFIER, token.type());
    assertEquals("def", token.value());
  }

  @Test
  void findsTokenAtCursorEnd() {
    List<Token> tokens = tokenizer.tokenize("abc def");
    Token token = tokenizer.tokenAtCursor(tokens, 7);

    assertNotNull(token);
    assertEquals(TokenType.IDENTIFIER, token.type());
    assertEquals("def", token.value());
  }

  @Test
  void findsTokenBeforeCursorInWhitespace() {
    List<Token> tokens = tokenizer.tokenize("abc  def");
    Token token = tokenizer.tokenAtCursor(tokens, 4);

    assertNotNull(token);
    assertEquals(TokenType.WHITESPACE, token.type());
  }

  @Test
  void findsPreviousToken() {
    List<Token> tokens = tokenizer.tokenize("abc def ghi");
    Token middle = tokens.get(2); // "def"
    Token previous = tokenizer.previousToken(tokens, middle);

    assertNotNull(previous);
    assertEquals(TokenType.WHITESPACE, previous.type());
  }

  @Test
  void returnsNullForFirstToken() {
    List<Token> tokens = tokenizer.tokenize("abc def");
    Token first = tokens.get(0);
    Token previous = tokenizer.previousToken(tokens, first);

    assertNull(previous);
  }

  @Test
  void findsNextNonWhitespace() {
    List<Token> tokens = tokenizer.tokenize("abc  def");
    Token next = tokenizer.nextNonWhitespace(tokens, 1);

    assertNotNull(next);
    assertEquals(TokenType.IDENTIFIER, next.type());
    assertEquals("def", next.value());
  }

  @Test
  void returnsNullWhenNoNonWhitespace() {
    List<Token> tokens = tokenizer.tokenize("abc  ");
    Token next = tokenizer.nextNonWhitespace(tokens, 1);

    assertNull(next);
  }

  // Real JfrPath Expression Tests

  @Test
  void tokenizesSimpleQuery() {
    List<Token> tokens = tokenizer.tokenize("show events");

    assertEquals(4, tokens.size());
    assertEquals(TokenType.IDENTIFIER, tokens.get(0).type());
    assertEquals("show", tokens.get(0).value());
    assertEquals(TokenType.WHITESPACE, tokens.get(1).type());
    assertEquals(TokenType.IDENTIFIER, tokens.get(2).type());
    assertEquals("events", tokens.get(2).value());
  }

  @Test
  void tokenizesQueryWithFilter() {
    List<Token> tokens = tokenizer.tokenize("events/jdk.FileRead[duration>1000]");

    assertEquals(9, tokens.size());
    assertEquals(TokenType.IDENTIFIER, tokens.get(0).type());
    assertEquals("events", tokens.get(0).value());
    assertEquals(TokenType.SLASH, tokens.get(1).type());
    assertEquals(TokenType.IDENTIFIER, tokens.get(2).type());
    assertEquals("jdk.FileRead", tokens.get(2).value());
    assertEquals(TokenType.BRACKET_OPEN, tokens.get(3).type());
    assertEquals(TokenType.IDENTIFIER, tokens.get(4).type());
    assertEquals("duration", tokens.get(4).value());
    assertEquals(TokenType.GT, tokens.get(5).type());
    assertEquals(TokenType.NUMBER, tokens.get(6).type());
    assertEquals("1000", tokens.get(6).value());
    assertEquals(TokenType.BRACKET_CLOSE, tokens.get(7).type());
    assertEquals(TokenType.EOF, tokens.get(8).type());
  }

  @Test
  void tokenizesQueryWithPipeline() {
    List<Token> tokens = tokenizer.tokenize("events/jdk.FileRead|count()");

    assertEquals(8, tokens.size());
    assertEquals(TokenType.IDENTIFIER, tokens.get(0).type());
    assertEquals("events", tokens.get(0).value());
    assertEquals(TokenType.SLASH, tokens.get(1).type());
    assertEquals(TokenType.IDENTIFIER, tokens.get(2).type());
    assertEquals(TokenType.PIPE, tokens.get(3).type());
    assertEquals(TokenType.IDENTIFIER, tokens.get(4).type());
    assertEquals("count", tokens.get(4).value());
    assertEquals(TokenType.PAREN_OPEN, tokens.get(5).type());
    assertEquals(TokenType.PAREN_CLOSE, tokens.get(6).type());
    assertEquals(TokenType.EOF, tokens.get(7).type());
  }

  @Test
  void tokenizesComplexQuery() {
    String query = "events/jdk.FileRead[duration>1000 && path~\".*log.*\"]|groupBy(path)|count()";
    List<Token> tokens = tokenizer.tokenize(query);

    // Verify structure without checking every token
    assertTrue(tokens.size() > 20);
    assertTrue(tokens.stream().anyMatch(t -> t.type() == TokenType.BRACKET_OPEN));
    assertTrue(tokens.stream().anyMatch(t -> t.type() == TokenType.AND));
    assertTrue(tokens.stream().anyMatch(t -> t.type() == TokenType.TILDE));
    assertTrue(tokens.stream().anyMatch(t -> t.type() == TokenType.STRING_LITERAL));
    assertTrue(tokens.stream().anyMatch(t -> t.type() == TokenType.PIPE));
  }

  @Test
  void tokenizesDecoratorExpression() {
    List<Token> tokens = tokenizer.tokenize("decorateByTime(jdk.ObjectAllocationSample)");

    assertEquals(5, tokens.size());
    assertEquals(TokenType.IDENTIFIER, tokens.get(0).type());
    assertEquals("decorateByTime", tokens.get(0).value());
    assertEquals(TokenType.PAREN_OPEN, tokens.get(1).type());
    assertEquals(TokenType.IDENTIFIER, tokens.get(2).type());
    assertEquals("jdk.ObjectAllocationSample", tokens.get(2).value());
    assertEquals(TokenType.PAREN_CLOSE, tokens.get(3).type());
    assertEquals(TokenType.EOF, tokens.get(4).type());
  }

  @Test
  void tokenizesFieldPathWithDecorator() {
    List<Token> tokens = tokenizer.tokenize("$decorator.eventType");

    assertEquals(2, tokens.size());
    assertEquals(TokenType.IDENTIFIER, tokens.get(0).type());
    assertEquals("$decorator.eventType", tokens.get(0).value());
  }

  // Edge Case Tests

  @Test
  void handlesTrailingWhitespace() {
    List<Token> tokens = tokenizer.tokenize("events   ");

    assertEquals(3, tokens.size());
    assertEquals(TokenType.IDENTIFIER, tokens.get(0).type());
    assertEquals(TokenType.WHITESPACE, tokens.get(1).type());
    assertEquals(TokenType.EOF, tokens.get(2).type());
  }

  @Test
  void handlesLeadingWhitespace() {
    List<Token> tokens = tokenizer.tokenize("   events");

    assertEquals(3, tokens.size());
    assertEquals(TokenType.WHITESPACE, tokens.get(0).type());
    assertEquals(TokenType.IDENTIFIER, tokens.get(1).type());
  }

  @Test
  void handlesOnlyWhitespace() {
    List<Token> tokens = tokenizer.tokenize("     ");

    assertEquals(2, tokens.size());
    assertEquals(TokenType.WHITESPACE, tokens.get(0).type());
    assertEquals(TokenType.EOF, tokens.get(1).type());
  }

  @Test
  void handlesUnknownCharacters() {
    List<Token> tokens = tokenizer.tokenize("a@b");

    assertEquals(4, tokens.size());
    assertEquals(TokenType.IDENTIFIER, tokens.get(0).type());
    assertEquals("a", tokens.get(0).value());
    assertEquals(TokenType.UNKNOWN, tokens.get(1).type());
    assertEquals("@", tokens.get(1).value());
    assertEquals(TokenType.IDENTIFIER, tokens.get(2).type());
    assertEquals("b", tokens.get(2).value());
  }

  @Test
  void alwaysEndsWithEOF() {
    List<Token> tokens = tokenizer.tokenize("any input");

    assertTrue(tokens.size() > 0);
    assertEquals(TokenType.EOF, tokens.get(tokens.size() - 1).type());
  }

  // Property-Based Tests

  @Test
  void allTokensHaveValidPositions() {
    String[] testLines = {
      "events/jdk.FileRead",
      "show events/jdk.FileRead[duration>1000]",
      "events/Type|count()",
      "decorateByTime(jdk.ExecutionSample, fields=\"stackTrace\")",
      "a == b && c != d || e > f",
      "\"string with spaces\" and 'another string'",
      "--option value --another-option"
    };

    for (String line : testLines) {
      List<Token> tokens = tokenizer.tokenize(line);
      for (Token token : tokens) {
        assertTrue(token.start() >= 0, "Token has negative start: " + token);
        assertTrue(token.end() >= token.start(), "Token end before start: " + token);
        assertTrue(token.end() <= line.length(), "Token end beyond line: " + token);
        assertEquals(
            token.end() - token.start(), token.length(), "Token length mismatch: " + token);
      }
    }
  }

  @Test
  void cursorAlwaysMapsToToken() {
    String line = "show events/jdk.FileRead";
    List<Token> tokens = tokenizer.tokenize(line);

    for (int cursor = 0; cursor <= line.length(); cursor++) {
      Token token = tokenizer.tokenAtCursor(tokens, cursor);
      assertNotNull(token, "Cursor " + cursor + " doesn't map to any token");
    }
  }
}

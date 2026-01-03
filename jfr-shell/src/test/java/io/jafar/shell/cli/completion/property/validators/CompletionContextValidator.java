package io.jafar.shell.cli.completion.property.validators;

import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextType;
import io.jafar.shell.cli.completion.JfrPathTokenizer;
import io.jafar.shell.cli.completion.Token;
import io.jafar.shell.cli.completion.TokenType;
import io.jafar.shell.cli.completion.property.models.ExpectedCompletion;
import io.jafar.shell.cli.completion.property.models.GeneratedQuery;
import io.jafar.shell.cli.completion.property.models.ValidationResult;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates that completion contexts match expected values based on query structure.
 *
 * <p>This validator analyzes generated queries and determines what completion context should be
 * produced, then compares against the actual context returned by CompletionContextAnalyzer.
 *
 * <p>The validation logic mirrors the analyzer's token-based parsing to independently determine the
 * expected context type.
 */
public class CompletionContextValidator {

  private final JfrPathTokenizer tokenizer;

  public CompletionContextValidator() {
    this.tokenizer = new JfrPathTokenizer();
  }

  /**
   * Validates that the actual context matches the expected context type.
   *
   * @param actual the actual completion context
   * @param expected the expected context type
   * @param message error message if validation fails
   * @throws AssertionError if context types don't match
   */
  public void assertContextType(
      CompletionContext actual, CompletionContextType expected, String message) {
    if (actual.type() != expected) {
      throw new AssertionError(
          message
              + ": expected "
              + expected
              + " but got "
              + actual.type()
              + " for query: "
              + actual);
    }
  }

  /**
   * Performs comprehensive validation of a completion context.
   *
   * @param query the generated query
   * @param actual the actual context returned by analyzer
   * @return validation result with any errors or warnings
   */
  public ValidationResult validateContext(GeneratedQuery query, CompletionContext actual) {
    ValidationResult result = new ValidationResult();
    ExpectedCompletion expected = determineExpectedContext(query);

    // Validate context type
    if (actual.type() != expected.contextType()) {
      result.addError(
          "Context type mismatch: expected " + expected.contextType() + ", got " + actual.type());
    }

    // Validate event type (if applicable)
    if (expected.eventType() != null) {
      if (actual.eventType() == null) {
        result.addError("Expected event type '" + expected.eventType() + "' but got null");
      } else if (!expected.eventType().equals(actual.eventType())) {
        result.addError(
            "Event type mismatch: expected '"
                + expected.eventType()
                + "', got '"
                + actual.eventType()
                + "'");
      }
    }

    // Validate field path (if applicable)
    if (!expected.fieldPath().isEmpty()) {
      if (!expected.fieldPath().equals(actual.fieldPath())) {
        result.addError(
            "Field path mismatch: expected "
                + expected.fieldPath()
                + ", got "
                + actual.fieldPath());
      }
    }

    // Validate function name (if applicable)
    if (expected.functionName() != null) {
      String actualFunction = (String) actual.extras().get("functionName");
      if (actualFunction == null) {
        result.addWarning("Expected function name '" + expected.functionName() + "' but got null");
      } else if (!expected.functionName().equals(actualFunction)) {
        result.addWarning(
            "Function name mismatch: expected '"
                + expected.functionName()
                + "', got '"
                + actualFunction
                + "'");
      }
    }

    return result;
  }

  /**
   * Determines the expected completion context for a query.
   *
   * <p>This method analyzes the query structure and cursor position to infer what context type
   * should be detected. It uses the same token-based approach as CompletionContextAnalyzer.
   *
   * @param query the generated query with cursor position
   * @return the expected completion context
   */
  public ExpectedCompletion determineExpectedContext(GeneratedQuery query) {
    String fullLine = query.getFullLine();
    int cursor = query.getCursorInFullLine();

    // Tokenize the line
    List<Token> tokens = tokenizer.tokenize(fullLine);

    // Handle empty or command-only cases
    if (tokens.isEmpty() || cursor <= 4) {
      return ExpectedCompletion.builder(CompletionContextType.COMMAND).build();
    }

    // Find token at cursor
    Token cursorToken = tokenizer.tokenAtCursor(tokens, cursor);

    // Check for command options (-- prefix)
    if (hasDoubleDash(tokens, cursor)) {
      return ExpectedCompletion.builder(CompletionContextType.COMMAND_OPTION).build();
    }

    // Analyze based on token structure
    return analyzeTokenStructure(tokens, cursorToken, cursor);
  }

  /**
   * Analyzes token structure to determine expected context.
   *
   * @param tokens all tokens in the line
   * @param cursorToken the token at cursor position
   * @param cursor the cursor position
   * @return the expected completion context
   */
  private ExpectedCompletion analyzeTokenStructure(
      List<Token> tokens, Token cursorToken, int cursor) {

    // Check for filter context (inside [...])
    if (isInsideFilter(tokens, cursor)) {
      return analyzeFilterContext(tokens, cursor);
    }

    // Check for pipeline context (after |)
    if (isAfterPipe(tokens, cursor)) {
      return analyzePipelineContext(tokens, cursor);
    }

    // Check for function parameter context (after opening paren)
    if (isInsideFunctionCall(tokens, cursor)) {
      return analyzeFunctionContext(tokens, cursor);
    }

    // Otherwise, analyze path context (root, event type, or field path)
    return analyzePathContext(tokens, cursor);
  }

  // ==================== Context Detection Helpers ====================

  /** Checks if cursor is inside a filter (between [ and ]). */
  private boolean isInsideFilter(List<Token> tokens, int cursor) {
    int bracketDepth = 0;
    for (Token token : tokens) {
      if (token.start() >= cursor) {
        break;
      }
      if (token.type() == TokenType.BRACKET_OPEN) {
        bracketDepth++;
      } else if (token.type() == TokenType.BRACKET_CLOSE) {
        bracketDepth--;
      }
    }
    return bracketDepth > 0;
  }

  /** Checks if cursor is after a pipe operator. */
  private boolean isAfterPipe(List<Token> tokens, int cursor) {
    // Find the previous non-whitespace token
    Token prevToken = null;
    for (int i = tokens.size() - 1; i >= 0; i--) {
      Token token = tokens.get(i);
      if (token.start() >= cursor) {
        continue;
      }
      if (token.type() != TokenType.WHITESPACE && token.type() != TokenType.EOF) {
        prevToken = token;
        break;
      }
    }
    return prevToken != null && prevToken.type() == TokenType.PIPE;
  }

  /** Checks if cursor is inside a function call (between ( and )). */
  private boolean isInsideFunctionCall(List<Token> tokens, int cursor) {
    int parenDepth = 0;
    for (Token token : tokens) {
      if (token.start() >= cursor) {
        break;
      }
      if (token.type() == TokenType.PAREN_OPEN) {
        parenDepth++;
      } else if (token.type() == TokenType.PAREN_CLOSE) {
        parenDepth--;
      }
    }
    return parenDepth > 0;
  }

  /** Checks if there's a double dash (--) before cursor. */
  private boolean hasDoubleDash(List<Token> tokens, int cursor) {
    for (Token token : tokens) {
      if (token.start() >= cursor) {
        break;
      }
      if (token.type() == TokenType.IDENTIFIER && token.value().startsWith("--")) {
        return true;
      }
    }
    return false;
  }

  // ==================== Context Analyzers ====================

  /** Analyzes filter context to determine field/operator/logical. */
  private ExpectedCompletion analyzeFilterContext(List<Token> tokens, int cursor) {
    // Extract event type before the [
    String eventType = extractEventTypeBeforeFilter(tokens, cursor);

    // Find the filter content
    String filterContent = extractFilterContent(tokens, cursor);

    // Check if we're after a logical operator
    if (filterContent.contains("&&") || filterContent.contains("||")) {
      String afterLogical = filterContent.substring(filterContent.lastIndexOf("&&") + 2);
      afterLogical = afterLogical.substring(Math.max(0, afterLogical.lastIndexOf("||") + 2)).trim();

      if (afterLogical.isEmpty() || afterLogical.split("\\s+").length < 2) {
        // After logical operator, expecting field
        return ExpectedCompletion.builder(CompletionContextType.FILTER_FIELD)
            .eventType(eventType)
            .build();
      }
    }

    // Check if we're after a field name (expecting operator)
    String[] parts = filterContent.trim().split("\\s+");
    if (parts.length == 1 && !parts[0].isEmpty()) {
      // Just field name, expecting operator
      return ExpectedCompletion.builder(CompletionContextType.FILTER_OPERATOR)
          .eventType(eventType)
          .build();
    }

    // Check if we have complete condition (expecting logical)
    if (parts.length >= 3) {
      return ExpectedCompletion.builder(CompletionContextType.FILTER_LOGICAL)
          .eventType(eventType)
          .build();
    }

    // Default to field
    return ExpectedCompletion.builder(CompletionContextType.FILTER_FIELD)
        .eventType(eventType)
        .build();
  }

  /** Analyzes pipeline context. */
  private ExpectedCompletion analyzePipelineContext(List<Token> tokens, int cursor) {
    return ExpectedCompletion.builder(CompletionContextType.PIPELINE_OPERATOR).build();
  }

  /** Analyzes function parameter context. */
  private ExpectedCompletion analyzeFunctionContext(List<Token> tokens, int cursor) {
    String functionName = extractFunctionName(tokens, cursor);
    String eventType = extractEventType(tokens, cursor);

    return ExpectedCompletion.builder(CompletionContextType.FUNCTION_PARAM)
        .eventType(eventType)
        .functionName(functionName)
        .build();
  }

  /** Analyzes path context (root, event type, field path). */
  private ExpectedCompletion analyzePathContext(List<Token> tokens, int cursor) {
    // Count slashes before cursor
    int slashCount = 0;
    String rootType = null;
    String eventType = null;
    List<String> fieldPath = new ArrayList<>();

    for (Token token : tokens) {
      if (token.start() >= cursor) {
        break;
      }

      if (token.type() == TokenType.SLASH) {
        slashCount++;
      } else if (token.type() == TokenType.IDENTIFIER && slashCount == 0) {
        rootType = token.value();
      } else if (token.type() == TokenType.IDENTIFIER && slashCount == 1) {
        eventType = token.value();
      } else if (token.type() == TokenType.IDENTIFIER && slashCount > 1) {
        fieldPath.add(token.value());
      }
    }

    // Determine context based on slash count
    if (slashCount == 0 || rootType == null) {
      return ExpectedCompletion.builder(CompletionContextType.ROOT).build();
    }

    if (slashCount == 1 || eventType == null) {
      // Check for special root types
      if ("chunks".equals(rootType)) {
        return ExpectedCompletion.builder(CompletionContextType.CHUNK_ID).build();
      } else if ("metadata".equals(rootType) || "cp".equals(rootType)) {
        return ExpectedCompletion.builder(CompletionContextType.EVENT_TYPE)
            .eventType(eventType)
            .build();
      }
      return ExpectedCompletion.builder(CompletionContextType.EVENT_TYPE).build();
    }

    // Field path context
    return ExpectedCompletion.builder(CompletionContextType.FIELD_PATH)
        .eventType(eventType)
        .fieldPath(fieldPath)
        .build();
  }

  // ==================== Extraction Helpers ====================

  /** Extracts event type before a filter bracket. */
  private String extractEventTypeBeforeFilter(List<Token> tokens, int cursor) {
    int slashCount = 0;
    String eventType = null;

    for (Token token : tokens) {
      if (token.type() == TokenType.BRACKET_OPEN) {
        break;
      }
      if (token.type() == TokenType.SLASH) {
        slashCount++;
      } else if (token.type() == TokenType.IDENTIFIER && slashCount == 1) {
        eventType = token.value();
      }
    }

    return eventType;
  }

  /** Extracts filter content between [ and cursor. */
  private String extractFilterContent(List<Token> tokens, int cursor) {
    StringBuilder content = new StringBuilder();
    boolean insideFilter = false;

    for (Token token : tokens) {
      if (token.start() >= cursor) {
        break;
      }
      if (token.type() == TokenType.BRACKET_OPEN) {
        insideFilter = true;
        continue;
      }
      if (insideFilter && token.type() != TokenType.BRACKET_CLOSE) {
        content.append(token.value());
        if (token.type() != TokenType.WHITESPACE) {
          content.append(" ");
        }
      }
    }

    return content.toString();
  }

  /** Extracts function name before opening paren. */
  private String extractFunctionName(List<Token> tokens, int cursor) {
    Token prev = null;
    for (Token token : tokens) {
      if (token.start() >= cursor) {
        break;
      }
      if (token.type() == TokenType.PAREN_OPEN && prev != null) {
        return prev.value();
      }
      if (token.type() == TokenType.IDENTIFIER) {
        prev = token;
      }
    }
    return null;
  }

  /** Extracts event type from tokens before cursor. */
  private String extractEventType(List<Token> tokens, int cursor) {
    int slashCount = 0;
    String eventType = null;

    for (Token token : tokens) {
      if (token.start() >= cursor) {
        break;
      }
      if (token.type() == TokenType.SLASH) {
        slashCount++;
      } else if (token.type() == TokenType.IDENTIFIER && slashCount == 1) {
        eventType = token.value();
        break;
      }
    }

    return eventType;
  }
}

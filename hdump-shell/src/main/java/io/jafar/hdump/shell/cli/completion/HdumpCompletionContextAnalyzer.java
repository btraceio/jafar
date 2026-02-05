package io.jafar.hdump.shell.cli.completion;

import io.jafar.shell.core.completion.CompletionContext;
import io.jafar.shell.core.completion.CompletionContextType;
import java.util.List;
import java.util.Locale;
import org.jline.reader.ParsedLine;

/**
 * Analyzes the input line and cursor position to determine the completion context for hdump
 * queries.
 *
 * <p>Determines whether the user is completing a root type, class name, field name, operator, etc.
 */
public final class HdumpCompletionContextAnalyzer {

  /** Analyze the input line to determine completion context. */
  public CompletionContext analyze(ParsedLine line) {
    String fullLine = line.line();
    int cursor = line.cursor();
    List<String> words = line.words();
    int wordIndex = line.wordIndex();
    String currentWord = line.word();

    // Empty line or first word -> COMMAND
    if (wordIndex == 0) {
      return CompletionContext.builder()
          .type(CompletionContextType.COMMAND)
          .partialInput(currentWord)
          .fullLine(fullLine)
          .cursor(cursor)
          .build();
    }

    String command = words.get(0).toLowerCase(Locale.ROOT);

    // Check for option typing (--something)
    if (currentWord.startsWith("--")) {
      return CompletionContext.builder()
          .type(CompletionContextType.COMMAND_OPTION)
          .command(command)
          .partialInput(currentWord)
          .fullLine(fullLine)
          .cursor(cursor)
          .build();
    }

    // For 'show' command - analyze the query expression
    if ("show".equals(command)) {
      return analyzeShowContext(line);
    }

    // Default: treat as command option context
    return CompletionContext.builder()
        .type(CompletionContextType.COMMAND_OPTION)
        .command(command)
        .partialInput(currentWord)
        .fullLine(fullLine)
        .cursor(cursor)
        .build();
  }

  /** Analyze context specifically for 'show' command. */
  private CompletionContext analyzeShowContext(ParsedLine line) {
    String fullLine = line.line();
    int cursor = line.cursor();
    String currentWord = line.word();

    // Find the expression part (everything after 'show ')
    String expression = extractExpression(fullLine);
    if (expression.isEmpty()) {
      // Just "show " with no expression - suggest roots
      return CompletionContext.builder()
          .type(CompletionContextType.ROOT)
          .command("show")
          .partialInput(currentWord)
          .fullLine(fullLine)
          .cursor(cursor)
          .build();
    }

    // Check if inside variable reference ${...
    int varStart = findVariableStart(fullLine, cursor);
    if (varStart >= 0) {
      String partial = fullLine.substring(varStart + 2, cursor);
      return CompletionContext.builder()
          .type(CompletionContextType.VARIABLE_REFERENCE)
          .command("show")
          .partialInput(partial)
          .fullLine(fullLine)
          .cursor(cursor)
          .build();
    }

    // Check if inside filter predicate [...]
    FilterContext filterCtx = findFilterContext(fullLine, cursor);
    if (filterCtx != null) {
      return analyzeFilterContext(line, filterCtx);
    }

    // Check if inside function call (...)
    FunctionContext funcCtx = findFunctionContext(fullLine, cursor);
    if (funcCtx != null) {
      return analyzeFunctionContext(line, funcCtx);
    }

    // Check if after pipe |
    int pipePos = findLastPipe(fullLine, cursor);
    if (pipePos >= 0) {
      String afterPipe = fullLine.substring(pipePos + 1, cursor).trim();
      // If there's a function call after pipe, we're completing parameters
      if (afterPipe.contains("(")) {
        funcCtx = findFunctionContext(fullLine, cursor);
        if (funcCtx != null) {
          return analyzeFunctionContext(line, funcCtx);
        }
      }
      // Otherwise we're completing pipeline operator
      String rootType = extractRootType(expression);
      return CompletionContext.builder()
          .type(CompletionContextType.PIPELINE_OPERATOR)
          .command("show")
          .rootType(rootType)
          .partialInput(afterPipe)
          .fullLine(fullLine)
          .cursor(cursor)
          .build();
    }

    // Analyze path expression (root/type/field...)
    return analyzePathContext(line, expression);
  }

  /** Analyze path-based context (objects/className, classes/pattern, etc.) */
  private CompletionContext analyzePathContext(ParsedLine line, String expression) {
    String fullLine = line.line();
    int cursor = line.cursor();
    String currentWord = line.word();

    // Extract root type and remaining path
    int slashPos = expression.indexOf('/');
    if (slashPos < 0) {
      // No slash yet - completing root type
      return CompletionContext.builder()
          .type(CompletionContextType.ROOT)
          .command("show")
          .partialInput(expression)
          .fullLine(fullLine)
          .cursor(cursor)
          .build();
    }

    String rootType = expression.substring(0, slashPos);
    String afterRoot = expression.substring(slashPos + 1);

    // Check for filter at end (but cursor before filter)
    int bracketPos = afterRoot.indexOf('[');
    if (bracketPos >= 0) {
      afterRoot = afterRoot.substring(0, bracketPos);
    }

    // Remove any pipe and stuff after
    int pipePos = afterRoot.indexOf('|');
    if (pipePos >= 0) {
      afterRoot = afterRoot.substring(0, pipePos);
    }

    // Completing type pattern (class name)
    return CompletionContext.builder()
        .type(CompletionContextType.TYPE_PATTERN)
        .command("show")
        .rootType(rootType)
        .partialInput(afterRoot.trim())
        .fullLine(fullLine)
        .cursor(cursor)
        .build();
  }

  /** Analyze filter context - field, operator, value, or logical. */
  private CompletionContext analyzeFilterContext(ParsedLine line, FilterContext filterCtx) {
    String fullLine = line.line();
    int cursor = line.cursor();
    String content = filterCtx.content;
    String rootType = filterCtx.rootType;

    // Empty filter or just opened - suggest fields
    if (content.isEmpty()) {
      return CompletionContext.builder()
          .type(CompletionContextType.FILTER_FIELD)
          .command("show")
          .rootType(rootType)
          .partialInput("")
          .fullLine(fullLine)
          .cursor(cursor)
          .build();
    }

    // Check if after a complete predicate (field op value) - suggest logical operators
    if (hasCompletePredicate(content)) {
      String partial = getPartialAfterPredicate(content);
      // Check if already typing a logical operator
      if (partial.isEmpty()
          || partial.equals("&")
          || partial.equals("|")
          || partial.startsWith("&&")
          || partial.startsWith("||")) {
        return CompletionContext.builder()
            .type(CompletionContextType.FILTER_LOGICAL)
            .command("show")
            .rootType(rootType)
            .partialInput(partial)
            .fullLine(fullLine)
            .cursor(cursor)
            .build();
      }
      // After logical operator - suggest field
      return CompletionContext.builder()
          .type(CompletionContextType.FILTER_FIELD)
          .command("show")
          .rootType(rootType)
          .partialInput(partial)
          .fullLine(fullLine)
          .cursor(cursor)
          .build();
    }

    // Check if we have a field and are typing operator
    String lastField = extractLastField(content);
    if (lastField != null && !content.endsWith(lastField)) {
      // After field - check if typing operator
      String afterField = content.substring(content.lastIndexOf(lastField) + lastField.length());
      if (isTypingOperator(afterField)) {
        return CompletionContext.builder()
            .type(CompletionContextType.FILTER_OPERATOR)
            .command("show")
            .rootType(rootType)
            .partialInput(afterField.trim())
            .fullLine(fullLine)
            .cursor(cursor)
            .build();
      }
      // After operator - suggest value (no completion for values)
      return CompletionContext.builder()
          .type(CompletionContextType.FILTER_VALUE)
          .command("show")
          .rootType(rootType)
          .partialInput(afterField.trim())
          .fullLine(fullLine)
          .cursor(cursor)
          .build();
    }

    // Typing field name
    return CompletionContext.builder()
        .type(CompletionContextType.FILTER_FIELD)
        .command("show")
        .rootType(rootType)
        .partialInput(content.trim())
        .fullLine(fullLine)
        .cursor(cursor)
        .build();
  }

  /** Analyze function context - suggest field names as parameters. */
  private CompletionContext analyzeFunctionContext(ParsedLine line, FunctionContext funcCtx) {
    String fullLine = line.line();
    int cursor = line.cursor();

    return CompletionContext.builder()
        .type(CompletionContextType.FUNCTION_PARAM)
        .command("show")
        .rootType(funcCtx.rootType)
        .functionName(funcCtx.functionName)
        .parameterIndex(funcCtx.paramIndex)
        .partialInput(funcCtx.currentParam)
        .fullLine(fullLine)
        .cursor(cursor)
        .build();
  }

  // === Helper methods ===

  private String extractExpression(String line) {
    // Find 'show ' and return everything after
    int showIdx = line.toLowerCase().indexOf("show ");
    if (showIdx < 0) return "";
    return line.substring(showIdx + 5).trim();
  }

  private String extractRootType(String expression) {
    int slash = expression.indexOf('/');
    if (slash < 0) return null;
    return expression.substring(0, slash);
  }

  private int findVariableStart(String line, int cursor) {
    // Look backwards from cursor for ${
    // Clamp start index to valid range to avoid StringIndexOutOfBoundsException
    int start = Math.min(cursor - 1, line.length() - 1);
    for (int i = start; i >= 1; i--) {
      if (line.charAt(i) == '{' && line.charAt(i - 1) == '$') {
        // Check if there's a closing } between here and cursor
        int closeIdx = line.indexOf('}', i);
        if (closeIdx < 0 || closeIdx >= cursor) {
          return i - 1;
        }
      }
    }
    return -1;
  }

  private FilterContext findFilterContext(String line, int cursor) {
    // Find [ before cursor with no matching ] between
    int bracketDepth = 0;
    int lastOpenBracket = -1;

    for (int i = 0; i < cursor; i++) {
      char c = line.charAt(i);
      if (c == '[') {
        bracketDepth++;
        lastOpenBracket = i;
      } else if (c == ']') {
        bracketDepth--;
        lastOpenBracket = -1;
      }
    }

    if (bracketDepth > 0 && lastOpenBracket >= 0) {
      String content = line.substring(lastOpenBracket + 1, cursor);
      String beforeBracket = line.substring(0, lastOpenBracket);
      String rootType = extractRootType(extractExpression(beforeBracket));
      return new FilterContext(content, rootType);
    }
    return null;
  }

  private FunctionContext findFunctionContext(String line, int cursor) {
    // Find ( before cursor with no matching ) between
    int parenDepth = 0;
    int lastOpenParen = -1;

    for (int i = 0; i < cursor; i++) {
      char c = line.charAt(i);
      if (c == '(') {
        parenDepth++;
        lastOpenParen = i;
      } else if (c == ')') {
        parenDepth--;
        if (parenDepth <= 0) {
          lastOpenParen = -1;
        }
      }
    }

    if (parenDepth > 0 && lastOpenParen >= 0) {
      // Extract function name
      String beforeParen = line.substring(0, lastOpenParen).trim();
      int funcStart = Math.max(beforeParen.lastIndexOf(' '), beforeParen.lastIndexOf('|')) + 1;
      String funcName = beforeParen.substring(funcStart);

      // Extract current parameter
      String paramsStr = line.substring(lastOpenParen + 1, cursor);
      int paramIndex = countCommas(paramsStr);
      int lastComma = paramsStr.lastIndexOf(',');
      String currentParam = lastComma >= 0 ? paramsStr.substring(lastComma + 1).trim() : paramsStr;

      // Get root type from expression
      String expression = extractExpression(line);
      String rootType = extractRootType(expression);

      return new FunctionContext(funcName, currentParam, paramIndex, rootType);
    }
    return null;
  }

  private int findLastPipe(String line, int cursor) {
    // Find last | before cursor that's not inside [] or ()
    int bracketDepth = 0;
    int parenDepth = 0;
    int lastPipe = -1;

    for (int i = 0; i < cursor; i++) {
      char c = line.charAt(i);
      switch (c) {
        case '[' -> bracketDepth++;
        case ']' -> bracketDepth--;
        case '(' -> parenDepth++;
        case ')' -> parenDepth--;
        case '|' -> {
          if (bracketDepth == 0 && parenDepth == 0) {
            lastPipe = i;
          }
        }
      }
    }
    return lastPipe;
  }

  private boolean hasCompletePredicate(String content) {
    // Check if content has field operator value pattern
    // Simple heuristic: has an operator followed by something
    return content.matches(".*[=!<>~]+\\s*[^=!<>~&|]+.*");
  }

  private String getPartialAfterPredicate(String content) {
    // Get partial input after the last complete value
    // Look for && or || or end of string
    int lastAnd = content.lastIndexOf("&&");
    int lastOr = content.lastIndexOf("||");
    int lastLogical = Math.max(lastAnd, lastOr);

    if (lastLogical >= 0) {
      return content.substring(lastLogical + 2).trim();
    }
    // Check if ending with partial & or |
    if (content.endsWith("&") || content.endsWith("|")) {
      return content.substring(content.length() - 1);
    }
    return "";
  }

  private String extractLastField(String content) {
    // Extract the last field name being typed
    // Remove any leading logical operators
    String trimmed = content.replaceAll("^.*?(&&|\\|\\|)\\s*", "");

    // Find the field name (alphanumeric start)
    int start = 0;
    while (start < trimmed.length() && !Character.isLetterOrDigit(trimmed.charAt(start))) {
      start++;
    }
    int end = start;
    while (end < trimmed.length()
        && (Character.isLetterOrDigit(trimmed.charAt(end)) || trimmed.charAt(end) == '_')) {
      end++;
    }
    if (end > start) {
      return trimmed.substring(start, end);
    }
    return null;
  }

  private boolean isTypingOperator(String str) {
    String trimmed = str.trim();
    return trimmed.isEmpty()
        || trimmed.equals("=")
        || trimmed.equals("!")
        || trimmed.equals("<")
        || trimmed.equals(">")
        || trimmed.equals("~");
  }

  private int countCommas(String str) {
    int count = 0;
    for (char c : str.toCharArray()) {
      if (c == ',') count++;
    }
    return count;
  }

  // Helper records
  private record FilterContext(String content, String rootType) {}

  private record FunctionContext(
      String functionName, String currentParam, int paramIndex, String rootType) {}
}

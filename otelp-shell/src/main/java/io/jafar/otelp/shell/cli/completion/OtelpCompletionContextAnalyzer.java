package io.jafar.otelp.shell.cli.completion;

import io.jafar.shell.core.completion.CompletionContext;
import io.jafar.shell.core.completion.CompletionContextType;
import java.util.Locale;
import org.jline.reader.ParsedLine;

/**
 * Analyzes the input line and cursor position to determine the completion context for OTLP
 * profiling queries.
 *
 * <p>otelp has a single root type ({@code samples}), so there is no type-pattern sub-path. The
 * recognized contexts are: COMMAND, ROOT, FILTER_FIELD, FILTER_OPERATOR, FILTER_LOGICAL,
 * PIPELINE_OPERATOR, and FUNCTION_PARAM.
 */
public final class OtelpCompletionContextAnalyzer {

  public CompletionContext analyze(ParsedLine line) {
    String fullLine = line.line();
    int cursor = line.cursor();
    int wordIndex = line.wordIndex();
    String currentWord = line.word();

    // Empty line or first word -> COMMAND context
    if (wordIndex == 0) {
      return CompletionContext.builder()
          .type(CompletionContextType.COMMAND)
          .partialInput(currentWord)
          .fullLine(fullLine)
          .cursor(cursor)
          .build();
    }

    String command = line.words().get(0).toLowerCase(Locale.ROOT);

    // 'show' command or bare 'samples' query -> analyze expression
    if ("show".equals(command) || "samples".equals(command)) {
      return analyzeShowContext(line);
    }

    // Default: no further completion
    return CompletionContext.builder()
        .type(CompletionContextType.COMMAND_OPTION)
        .command(command)
        .partialInput(currentWord)
        .fullLine(fullLine)
        .cursor(cursor)
        .build();
  }

  private CompletionContext analyzeShowContext(ParsedLine line) {
    String fullLine = line.line();
    int cursor = line.cursor();
    String currentWord = line.word();

    String expression = extractExpression(fullLine);
    if (expression.isEmpty()) {
      return CompletionContext.builder()
          .type(CompletionContextType.ROOT)
          .command("show")
          .partialInput(currentWord)
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
      if (afterPipe.contains("(")) {
        funcCtx = findFunctionContext(fullLine, cursor);
        if (funcCtx != null) {
          return analyzeFunctionContext(line, funcCtx);
        }
      }
      return CompletionContext.builder()
          .type(CompletionContextType.PIPELINE_OPERATOR)
          .command("show")
          .rootType("samples")
          .partialInput(afterPipe)
          .fullLine(fullLine)
          .cursor(cursor)
          .build();
    }

    // Completing the root itself
    String partial = expression.trim();
    int end = partial.length();
    for (int i = 0; i < partial.length(); i++) {
      char c = partial.charAt(i);
      if (c == ' ' || c == '[' || c == '|') {
        end = i;
        break;
      }
    }
    return CompletionContext.builder()
        .type(CompletionContextType.ROOT)
        .command("show")
        .partialInput(partial.substring(0, end))
        .fullLine(fullLine)
        .cursor(cursor)
        .build();
  }

  private CompletionContext analyzeFilterContext(ParsedLine line, FilterContext filterCtx) {
    String fullLine = line.line();
    int cursor = line.cursor();
    String content = filterCtx.content;

    if (content.isEmpty()) {
      return CompletionContext.builder()
          .type(CompletionContextType.FILTER_FIELD)
          .command("show")
          .rootType("samples")
          .partialInput("")
          .fullLine(fullLine)
          .cursor(cursor)
          .build();
    }

    if (hasCompletePredicate(content)) {
      String partial = getPartialAfterPredicate(content);
      if (partial.isEmpty()
          || partial.equals("&")
          || partial.equals("|")
          || partial.startsWith("&&")
          || partial.startsWith("||")) {
        return CompletionContext.builder()
            .type(CompletionContextType.FILTER_LOGICAL)
            .command("show")
            .rootType("samples")
            .partialInput(partial)
            .fullLine(fullLine)
            .cursor(cursor)
            .build();
      }
      return CompletionContext.builder()
          .type(CompletionContextType.FILTER_FIELD)
          .command("show")
          .rootType("samples")
          .partialInput(partial)
          .fullLine(fullLine)
          .cursor(cursor)
          .build();
    }

    String lastField = extractLastField(content);
    if (lastField != null && !content.endsWith(lastField)) {
      String afterField = content.substring(content.lastIndexOf(lastField) + lastField.length());
      if (isTypingOperator(afterField)) {
        return CompletionContext.builder()
            .type(CompletionContextType.FILTER_OPERATOR)
            .command("show")
            .rootType("samples")
            .partialInput(afterField.trim())
            .fullLine(fullLine)
            .cursor(cursor)
            .build();
      }
      return CompletionContext.builder()
          .type(CompletionContextType.FILTER_VALUE)
          .command("show")
          .rootType("samples")
          .partialInput(afterField.trim())
          .fullLine(fullLine)
          .cursor(cursor)
          .build();
    }

    return CompletionContext.builder()
        .type(CompletionContextType.FILTER_FIELD)
        .command("show")
        .rootType("samples")
        .partialInput(content.trim())
        .fullLine(fullLine)
        .cursor(cursor)
        .build();
  }

  private CompletionContext analyzeFunctionContext(ParsedLine line, FunctionContext funcCtx) {
    return CompletionContext.builder()
        .type(CompletionContextType.FUNCTION_PARAM)
        .command("show")
        .rootType("samples")
        .functionName(funcCtx.functionName)
        .parameterIndex(funcCtx.paramIndex)
        .partialInput(funcCtx.currentParam)
        .fullLine(line.line())
        .cursor(line.cursor())
        .build();
  }

  // === Helpers (copied verbatim from HdumpCompletionContextAnalyzer) ===

  private String extractExpression(String line) {
    int showIdx = line.toLowerCase(Locale.ROOT).indexOf("show ");
    if (showIdx >= 0) {
      return line.substring(showIdx + 5).trim();
    }
    String trimmed = line.trim();
    if (trimmed.toLowerCase(Locale.ROOT).startsWith("samples")) {
      return trimmed;
    }
    return "";
  }

  private FilterContext findFilterContext(String line, int cursor) {
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
      return new FilterContext(content);
    }
    return null;
  }

  private FunctionContext findFunctionContext(String line, int cursor) {
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
      String beforeParen = line.substring(0, lastOpenParen).trim();
      int funcStart = Math.max(beforeParen.lastIndexOf(' '), beforeParen.lastIndexOf('|')) + 1;
      String funcName = beforeParen.substring(funcStart);

      String paramsStr = line.substring(lastOpenParen + 1, cursor);
      int paramIndex = countCommas(paramsStr);
      int lastComma = paramsStr.lastIndexOf(',');
      String currentParam =
          lastComma >= 0 ? paramsStr.substring(lastComma + 1).trim() : paramsStr.trim();

      return new FunctionContext(funcName, currentParam, paramIndex);
    }
    return null;
  }

  private int findLastPipe(String line, int cursor) {
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
    return content.matches(".*[=!<>~]+\\s*[^=!<>~&|]+.*");
  }

  private String getPartialAfterPredicate(String content) {
    int lastAnd = content.lastIndexOf("&&");
    int lastOr = content.lastIndexOf("||");
    int lastLogical = Math.max(lastAnd, lastOr);

    if (lastLogical >= 0) {
      return content.substring(lastLogical + 2).trim();
    }
    if (content.endsWith("&") || content.endsWith("|")) {
      return content.substring(content.length() - 1);
    }
    return "";
  }

  private String extractLastField(String content) {
    String trimmed = content.replaceAll("^.*?(&&|\\|\\|)\\s*", "");

    int start = 0;
    while (start < trimmed.length() && !Character.isLetterOrDigit(trimmed.charAt(start))) {
      start++;
    }
    int end = start;
    while (end < trimmed.length()
        && (Character.isLetterOrDigit(trimmed.charAt(end))
            || trimmed.charAt(end) == '_'
            || trimmed.charAt(end) == '/')) {
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

  private record FilterContext(String content) {}

  private record FunctionContext(String functionName, String currentParam, int paramIndex) {}
}

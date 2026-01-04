package io.jafar.shell.cli.completion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jline.reader.ParsedLine;

/**
 * Analyzes the input line and cursor position to determine the completion context. This is the
 * single source of truth for context detection - all logic is isolated here.
 *
 * <p>Uses token-based parsing to accurately identify completion contexts independent of JLine3's
 * word splitting behavior.
 */
public class CompletionContextAnalyzer {

  // Functions that take field parameters (including decorator functions with special handling)
  private static final String[] FIELD_FUNCTIONS = {
    "sum", "groupBy", "select", "top", "decorateByTime", "decorateByKey"
  };

  // Pattern to match function calls: funcName(
  private static final Pattern FUNCTION_PATTERN =
      Pattern.compile(
          "(sum|groupBy|select|top|decorateByTime|decorateByKey)\\s*\\(", Pattern.CASE_INSENSITIVE);

  // Tokenizer for JfrPath expressions
  private final JfrPathTokenizer tokenizer = new JfrPathTokenizer();

  /** Analyze the input line to determine completion context. */
  public CompletionContext analyze(ParsedLine line) {
    String fullLine = line.line();
    int cursor = line.cursor();
    List<String> words = line.words();
    int wordIndex = line.wordIndex();
    String currentWord = line.word();

    // Special case: empty line or first word -> COMMAND
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

    // Check for option value completion (after specific options)
    if (words.size() >= 2) {
      String prevWord = words.get(wordIndex - 1);
      if (isOptionExpectingValue(prevWord)) {
        return CompletionContext.builder()
            .type(CompletionContextType.OPTION_VALUE)
            .command(command)
            .partialInput(currentWord)
            .fullLine(fullLine)
            .cursor(cursor)
            .extras(Collections.singletonMap("option", prevWord))
            .build();
      }
    }

    // For 'show' command - most complex case
    // Use token-based analysis for accurate detection
    if ("show".equals(command)) {
      return analyzeShowContextWithTokens(line);
    }

    // For 'metadata' command
    if ("metadata".equals(command)) {
      return analyzeMetadataContext(line);
    }

    // For 'cp' command
    if ("cp".equals(command)) {
      return analyzeCpContext(line);
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

    // Priority 1: Check if inside filter predicate [...]
    FilterPosition filterPos = findFilterPosition(fullLine, cursor);
    if (filterPos != null) {
      return analyzeFilterContext(line, filterPos);
    }

    // Priority 2: Check if inside function call
    FunctionPosition funcPos = findFunctionPosition(fullLine, cursor);
    if (funcPos != null) {
      return analyzeFunctionContext(line, funcPos);
    }

    // Priority 3: Check if after pipe |
    if (isAfterPipe(line)) {
      return analyzePipelineContext(line);
    }

    // Priority 4: Check if typing a path
    String expressionToken = findExpressionToken(line.words());
    if (expressionToken != null
        || currentWord.startsWith("events/")
        || currentWord.startsWith("metadata/")
        || currentWord.startsWith("cp/")
        || currentWord.startsWith("chunks")) {

      String pathWord =
          currentWord.startsWith("events/")
                  || currentWord.startsWith("metadata/")
                  || currentWord.startsWith("cp/")
                  || currentWord.startsWith("chunks")
              ? currentWord
              : expressionToken;

      if (pathWord != null) {
        return analyzePathContext(line, pathWord);
      }
    }

    // Priority 5: Check for root completion after 'show '
    if (line.wordIndex() == 1 && !currentWord.contains("/")) {
      return CompletionContext.builder()
          .type(CompletionContextType.ROOT)
          .command("show")
          .partialInput(currentWord)
          .fullLine(fullLine)
          .cursor(cursor)
          .build();
    }

    // Default: option completion
    return CompletionContext.builder()
        .type(CompletionContextType.COMMAND_OPTION)
        .command("show")
        .partialInput(currentWord)
        .fullLine(fullLine)
        .cursor(cursor)
        .build();
  }

  /**
   * Analyze context for 'show' command using token-based parsing.
   *
   * <p>This method tokenizes the line and uses token patterns to determine context, independent of
   * JLine3's word splitting behavior.
   */
  private CompletionContext analyzeShowContextWithTokens(ParsedLine line) {
    String fullLine = line.line();
    int cursor = line.cursor();

    // Tokenize the entire line
    List<Token> tokens = tokenizer.tokenize(fullLine);
    Token cursorToken = tokenizer.tokenAtCursor(tokens, cursor);

    if (cursorToken == null) {
      // Fallback to option completion
      return CompletionContext.builder()
          .type(CompletionContextType.COMMAND_OPTION)
          .command("show")
          .partialInput(line.word())
          .fullLine(fullLine)
          .cursor(cursor)
          .build();
    }

    // Find if cursor is inside brackets (filter context)
    if (isInsideBrackets(tokens, cursor)) {
      return analyzeFilterContextFromTokens(tokens, cursor, fullLine);
    }

    // Find if cursor is inside parentheses (function context)
    if (isInsideParentheses(tokens, cursor)) {
      return analyzeFunctionContextFromTokens(tokens, cursor, fullLine);
    }

    // Check if after pipe operator
    if (isAfterPipeToken(tokens, cursorToken)) {
      return analyzePipelineContextFromTokens(tokens, cursor, fullLine);
    }

    // Check if typing a path (events/, metadata/, etc.)
    PathInfo pathInfo = analyzePathFromTokens(tokens, cursor);
    if (pathInfo != null) {
      return buildPathContext(pathInfo, fullLine, cursor);
    }

    // Default: ROOT or COMMAND_OPTION
    // If there are no slashes and we're just after "show", it's ROOT
    // This handles both "show " and "show eve" cases
    boolean hasSlash = tokens.stream().anyMatch(t -> t.type() == TokenType.SLASH);
    if (!hasSlash) {
      return CompletionContext.builder()
          .type(CompletionContextType.ROOT)
          .command("show")
          .partialInput(extractPartialInput(cursorToken, cursor))
          .fullLine(fullLine)
          .cursor(cursor)
          .build();
    }

    return CompletionContext.builder()
        .type(CompletionContextType.COMMAND_OPTION)
        .command("show")
        .partialInput(line.word())
        .fullLine(fullLine)
        .cursor(cursor)
        .build();
  }

  /** Analyze context for 'metadata' command. */
  private CompletionContext analyzeMetadataContext(ParsedLine line) {
    List<String> words = line.words();
    int wordIndex = line.wordIndex();
    String currentWord = line.word();

    // After 'metadata class <type>'
    if (words.size() >= 2 && "class".equalsIgnoreCase(words.get(1))) {
      if (wordIndex == 2) {
        return CompletionContext.builder()
            .type(CompletionContextType.EVENT_TYPE)
            .command("metadata")
            .rootType("metadata")
            .partialInput(currentWord)
            .fullLine(line.line())
            .cursor(line.cursor())
            .build();
      }
    }

    return CompletionContext.builder()
        .type(CompletionContextType.COMMAND_OPTION)
        .command("metadata")
        .partialInput(currentWord)
        .fullLine(line.line())
        .cursor(line.cursor())
        .build();
  }

  /** Analyze context for 'cp' command. */
  private CompletionContext analyzeCpContext(ParsedLine line) {
    return CompletionContext.builder()
        .type(CompletionContextType.EVENT_TYPE)
        .command("cp")
        .rootType("cp")
        .partialInput(line.word())
        .fullLine(line.line())
        .cursor(line.cursor())
        .build();
  }

  /** Analyze context when inside a filter predicate [...]. */
  private CompletionContext analyzeFilterContext(ParsedLine line, FilterPosition filterPos) {
    String fullLine = line.line();
    int cursor = line.cursor();
    String inside = filterPos.content;

    // Extract event type and nested path from the path before the filter
    String[] typeAndPath = extractEventTypeAndNestedPath(fullLine, filterPos.openBracket);
    String eventType = typeAndPath[0];
    String nestedPath = typeAndPath[1];

    // Determine filter sub-context based on what's inside
    CompletionContextType filterType = determineFilterSubContext(inside);

    // Extract partial input based on sub-context
    String partial = extractFilterPartialInput(inside, filterType);

    // Build extras with both filterContent and nestedPath
    java.util.Map<String, String> extras = new java.util.HashMap<>();
    extras.put("filterContent", inside);
    if (nestedPath != null) {
      extras.put("nestedPath", nestedPath);
    }

    return CompletionContext.builder()
        .type(filterType)
        .command("show")
        .eventType(eventType)
        .partialInput(partial)
        .fullLine(fullLine)
        .cursor(cursor)
        .extras(extras)
        .build();
  }

  /** Determine the specific filter sub-context. */
  private CompletionContextType determineFilterSubContext(String inside) {
    // Strip list prefix (any:, all:, none:)
    String content = inside;
    if (content.startsWith("any:") || content.startsWith("all:") || content.startsWith("none:")) {
      int colonIdx = content.indexOf(':');
      content = content.substring(colonIdx + 1);
    }

    // Check if there's a logical operator at the end - next part needs field
    if (content.endsWith("&& ") || content.endsWith("|| ")) {
      return CompletionContextType.FILTER_FIELD;
    }

    // Check for complete condition (field op value) followed by space
    // Simple heuristic: if we have an operator and something after, and ends with space
    if (hasCompleteCondition(content) && content.endsWith(" ")) {
      return CompletionContextType.FILTER_LOGICAL;
    }

    // Check if there's an operator
    if (hasOperator(content)) {
      // Check if operator is followed by complete value
      String afterOp = getContentAfterOperator(content);
      if (afterOp != null && !afterOp.trim().isEmpty()) {
        // We have a value, could be typing more or need logical
        return CompletionContextType.FILTER_LOGICAL;
      }
      // Operator present but no value yet
      return CompletionContextType.FILTER_VALUE;
    }

    // Check if we're typing a field name (with or without partial)
    String trimmed = content.trim();
    if (trimmed.isEmpty()) {
      return CompletionContextType.FILTER_FIELD;
    }

    // If content ends with space and trimmed has no spaces, it's a field followed by space
    // -> suggest operators
    if (content.endsWith(" ") && !trimmed.contains(" ")) {
      return CompletionContextType.FILTER_OPERATOR;
    }

    // If trimmed has no space, user is typing a field path
    // Return FILTER_FIELD - the completer will also suggest operators
    if (!trimmed.contains(" ")) {
      return CompletionContextType.FILTER_FIELD;
    }

    // After a field name, suggest operator
    return CompletionContextType.FILTER_OPERATOR;
  }

  /** Extract partial input based on filter context type. */
  private String extractFilterPartialInput(String inside, CompletionContextType type) {
    // Strip list prefix
    String content = inside;
    if (content.startsWith("any:") || content.startsWith("all:") || content.startsWith("none:")) {
      int colonIdx = content.indexOf(':');
      content = content.substring(colonIdx + 1);
    }

    return switch (type) {
      case FILTER_FIELD -> {
        // After && or ||, get what's after
        int lastAnd = content.lastIndexOf("&&");
        int lastOr = content.lastIndexOf("||");
        int lastLogical = Math.max(lastAnd, lastOr);
        if (lastLogical >= 0) {
          yield content.substring(lastLogical + 2).trim();
        }
        yield content.trim();
      }
      case FILTER_OPERATOR -> {
        // Get what's after the last space (the field name part)
        int lastSpace = content.lastIndexOf(' ');
        yield lastSpace >= 0 ? content.substring(lastSpace + 1) : content;
      }
      case FILTER_VALUE, FILTER_LOGICAL -> "";
      default -> content.trim();
    };
  }

  /** Analyze context when inside a function call. */
  private CompletionContext analyzeFunctionContext(ParsedLine line, FunctionPosition funcPos) {
    String fullLine = line.line();
    int cursor = line.cursor();
    String funcName = funcPos.functionName;
    String params = funcPos.parameters;

    // Count commas to determine parameter index
    int paramIndex = countCommas(params);

    // For top(), first param is a number - only complete second param
    if ("top".equalsIgnoreCase(funcName) && paramIndex == 0) {
      return CompletionContext.builder()
          .type(CompletionContextType.UNKNOWN) // Don't complete first param of top
          .command("show")
          .functionName(funcName)
          .parameterIndex(paramIndex)
          .partialInput("")
          .fullLine(fullLine)
          .cursor(cursor)
          .build();
    }

    // Extract event type from earlier in the line
    String eventType = extractEventTypeFromLine(fullLine);

    // Get partial input (after last comma or opening paren)
    String partial = extractParameterPartial(params);

    return CompletionContext.builder()
        .type(CompletionContextType.FUNCTION_PARAM)
        .command("show")
        .eventType(eventType)
        .functionName(funcName)
        .parameterIndex(paramIndex)
        .partialInput(partial)
        .fullLine(fullLine)
        .cursor(cursor)
        .extras(Map.of("functionParams", params))
        .build();
  }

  /** Analyze context when after a pipe. */
  private CompletionContext analyzePipelineContext(ParsedLine line) {
    String currentWord = line.word();
    String fullLine = line.line();

    // Get what's being typed after the pipe
    String partial = "";
    if (currentWord.equals("|")) {
      partial = "";
    } else if (currentWord.startsWith("|")) {
      partial = currentWord.substring(1).trim();
    } else if (currentWord.endsWith("|")) {
      partial = "";
    } else {
      // Previous word was |, current word is the partial
      partial = currentWord;
    }

    String eventType = extractEventTypeFromLine(fullLine);

    return CompletionContext.builder()
        .type(CompletionContextType.PIPELINE_OPERATOR)
        .command("show")
        .eventType(eventType)
        .partialInput(partial)
        .fullLine(fullLine)
        .cursor(line.cursor())
        .build();
  }

  /** Analyze context when typing a path (events/, metadata/, etc.) */
  private CompletionContext analyzePathContext(ParsedLine line, String pathWord) {
    String fullLine = line.line();
    int cursor = line.cursor();

    // Determine root type
    String rootType = null;
    if (pathWord.startsWith("events/")) {
      rootType = "events";
    } else if (pathWord.startsWith("metadata/")) {
      rootType = "metadata";
    } else if (pathWord.startsWith("cp/")) {
      rootType = "cp";
    } else if (pathWord.startsWith("chunks/")) {
      rootType = "chunks";
    } else if (pathWord.startsWith("chunks")) {
      rootType = "chunks";
    }

    if (rootType == null) {
      return CompletionContext.builder()
          .type(CompletionContextType.ROOT)
          .command("show")
          .partialInput(pathWord)
          .fullLine(fullLine)
          .cursor(cursor)
          .build();
    }

    // Special handling for chunks/
    if ("chunks".equals(rootType)) {
      String rest = pathWord.length() > 7 ? pathWord.substring(7) : ""; // after "chunks/"
      return CompletionContext.builder()
          .type(CompletionContextType.CHUNK_ID)
          .command("show")
          .rootType(rootType)
          .partialInput(rest)
          .fullLine(fullLine)
          .cursor(cursor)
          .build();
    }

    // Parse path segments
    String rest = pathWord.substring(rootType.length() + 1); // after "events/"

    // Check if typing event type or field path
    int slashIdx = rest.indexOf('/');
    int bracketIdx = rest.indexOf('[');

    if (slashIdx < 0 && bracketIdx < 0) {
      // Still typing event type
      return CompletionContext.builder()
          .type(CompletionContextType.EVENT_TYPE)
          .command("show")
          .rootType(rootType)
          .partialInput(rest)
          .fullLine(fullLine)
          .cursor(cursor)
          .build();
    }

    // Has event type, might be typing field path
    int typeEnd =
        slashIdx >= 0 && bracketIdx >= 0
            ? Math.min(slashIdx, bracketIdx)
            : slashIdx >= 0 ? slashIdx : bracketIdx;

    String eventType = rest.substring(0, typeEnd);

    // Extract field path segments
    String afterType = slashIdx >= 0 && slashIdx == typeEnd ? rest.substring(slashIdx + 1) : "";

    // Strip any filter portion
    int filterStart = afterType.indexOf('[');
    if (filterStart >= 0) {
      afterType = afterType.substring(0, filterStart);
    }

    List<String> fieldPath = parseFieldPath(afterType);

    // Check if path ends with / (user wants to complete nested fields)
    // Use the original pathWord to check since afterType might be empty
    boolean endsWithSlash = pathWord.endsWith("/");

    // Special handling for metadata/Type/ - suggest subprops like "fields"
    if ("metadata".equals(rootType) && endsWithSlash && fieldPath.isEmpty()) {
      return CompletionContext.builder()
          .type(CompletionContextType.METADATA_SUBPROP)
          .command("show")
          .rootType(rootType)
          .eventType(eventType)
          .partialInput("")
          .fullLine(fullLine)
          .cursor(cursor)
          .build();
    }

    // Determine partial and complete path
    String partial;
    List<String> completePath;

    if (endsWithSlash || fieldPath.isEmpty()) {
      // Ends with / or empty - all segments are complete, partial is empty
      completePath = fieldPath;
      partial = "";
    } else {
      // Last segment is being typed
      partial = fieldPath.get(fieldPath.size() - 1);
      completePath = new ArrayList<>(fieldPath.subList(0, fieldPath.size() - 1));
    }

    return CompletionContext.builder()
        .type(CompletionContextType.FIELD_PATH)
        .command("show")
        .rootType(rootType)
        .eventType(eventType)
        .fieldPath(completePath)
        .partialInput(partial)
        .fullLine(fullLine)
        .cursor(cursor)
        .build();
  }

  // ========== Helper methods for context detection ==========

  /** Find filter position in the line (inside [...]). */
  FilterPosition findFilterPosition(String line, int cursor) {
    int openBracket = line.lastIndexOf('[', cursor - 1);
    if (openBracket < 0) {
      return null;
    }

    // Find close bracket - could be before or after cursor
    int closeBracketBefore = line.indexOf(']', openBracket);

    // If there's a close bracket between [ and cursor, we're outside the filter
    if (closeBracketBefore >= 0 && closeBracketBefore < cursor) {
      return null;
    }

    // Find close bracket after cursor (if any)
    int closeBracket = line.indexOf(']', cursor);

    // We're inside the filter
    String content =
        line.substring(
            openBracket + 1, closeBracket >= 0 ? Math.min(cursor, closeBracket) : cursor);
    return new FilterPosition(openBracket, closeBracket, content);
  }

  /** Find function call position (inside funcName(...)). */
  FunctionPosition findFunctionPosition(String line, int cursor) {
    String beforeCursor = line.substring(0, cursor);

    for (String funcName : FIELD_FUNCTIONS) {
      String pattern = funcName.toLowerCase(Locale.ROOT) + "(";
      int funcIdx = beforeCursor.toLowerCase(Locale.ROOT).lastIndexOf(pattern);

      if (funcIdx >= 0) {
        int openParen = funcIdx + funcName.length();
        String afterParen = line.substring(openParen + 1);
        int closeParen = afterParen.indexOf(')');

        // Check if cursor is inside the function call
        boolean inside =
            cursor > openParen && (closeParen < 0 || cursor <= openParen + 1 + closeParen);

        if (inside) {
          String params =
              closeParen >= 0
                  ? afterParen.substring(0, Math.min(cursor - openParen - 1, closeParen))
                  : afterParen.substring(0, cursor - openParen - 1);
          return new FunctionPosition(funcName, openParen, closeParen, params);
        }
      }
    }
    return null;
  }

  /** Check if cursor is after a pipe operator. */
  boolean isAfterPipe(ParsedLine line) {
    List<String> words = line.words();
    int wordIndex = line.wordIndex();
    String currentWord = line.word();

    // Current word is exactly "|"
    if ("|".equals(currentWord)) {
      return true;
    }

    // Current word starts or ends with |
    if (currentWord.startsWith("|") || currentWord.endsWith("|")) {
      return true;
    }

    // Previous word was |
    if (wordIndex > 0 && "|".equals(words.get(wordIndex - 1))) {
      return true;
    }

    return false;
  }

  /** Check if an option expects a value. */
  boolean isOptionExpectingValue(String option) {
    return switch (option) {
      case "--list-match", "--format", "--limit", "--depth", "--alias" -> true;
      default -> false;
    };
  }

  /** Find the expression token (events/..., metadata/..., etc.) in the words. */
  String findExpressionToken(List<String> words) {
    for (String word : words) {
      if (word.startsWith("events/")
          || word.startsWith("metadata/")
          || word.startsWith("cp/")
          || word.startsWith("chunks")) {
        return word;
      }
    }
    return null;
  }

  /**
   * Extract event type and nested path info from a path string. Returns an array: [eventType,
   * nestedPath] where nestedPath may be null.
   */
  String[] extractEventTypeAndNestedPath(String line, int beforePos) {
    String before = line.substring(0, beforePos);

    // Try events/, cp/, metadata/ patterns
    String[] rootPrefixes = {"events/", "cp/", "metadata/"};
    for (String prefix : rootPrefixes) {
      int idx = before.lastIndexOf(prefix);
      if (idx >= 0) {
        String afterRoot = before.substring(idx + prefix.length());
        int firstSlashIdx = afterRoot.indexOf('/');
        int bracketIdx = afterRoot.indexOf('[');

        // Find the end of the event type name
        int typeEnd = afterRoot.length();
        if (firstSlashIdx >= 0) typeEnd = Math.min(typeEnd, firstSlashIdx);
        if (bracketIdx >= 0) typeEnd = Math.min(typeEnd, bracketIdx);

        String eventType = afterRoot.substring(0, typeEnd);

        // Extract nested path if there's a / after the event type
        String nestedPath = null;
        if (firstSlashIdx >= 0 && firstSlashIdx < afterRoot.length() - 1) {
          // There's something after the first slash
          String afterType = afterRoot.substring(firstSlashIdx + 1);
          // Trim any trailing [ if present
          if (bracketIdx > firstSlashIdx) {
            int relBracketIdx = afterType.indexOf('[');
            if (relBracketIdx >= 0) {
              afterType = afterType.substring(0, relBracketIdx);
            }
          }
          if (!afterType.isEmpty()) {
            nestedPath = afterType;
          }
        }

        return new String[] {eventType, nestedPath};
      }
    }

    return new String[] {null, null};
  }

  /** Extract event type from a path string (convenience wrapper). */
  String extractEventTypeFromPath(String line, int beforePos) {
    return extractEventTypeAndNestedPath(line, beforePos)[0];
  }

  /** Extract event type from anywhere in the line. */
  String extractEventTypeFromLine(String line) {
    // Look for events/TYPE pattern
    int eventsIdx = line.indexOf("events/");
    if (eventsIdx >= 0) {
      String afterEvents = line.substring(eventsIdx + 7);
      int slashIdx = afterEvents.indexOf('/');
      int bracketIdx = afterEvents.indexOf('[');
      int pipeIdx = afterEvents.indexOf('|');
      int spaceIdx = afterEvents.indexOf(' ');

      int end = afterEvents.length();
      if (slashIdx >= 0) end = Math.min(end, slashIdx);
      if (bracketIdx >= 0) end = Math.min(end, bracketIdx);
      if (pipeIdx >= 0) end = Math.min(end, pipeIdx);
      if (spaceIdx >= 0) end = Math.min(end, spaceIdx);

      return afterEvents.substring(0, end);
    }

    return null;
  }

  /** Check if filter content has an operator. */
  boolean hasOperator(String content) {
    return content.contains("==")
        || content.contains("!=")
        || content.contains(">=")
        || content.contains("<=")
        || content.contains(">")
        || content.contains("<")
        || content.contains("~")
        || content.toLowerCase(Locale.ROOT).contains(" contains ")
        || content.toLowerCase(Locale.ROOT).contains(" startswith ")
        || content.toLowerCase(Locale.ROOT).contains(" endswith ")
        || content.toLowerCase(Locale.ROOT).contains(" matches ");
  }

  /** Check if filter has a complete condition (field op value). */
  boolean hasCompleteCondition(String content) {
    // Very simple check: has operator and something after it
    String[] operators = {"==", "!=", ">=", "<=", ">", "<", "~"};
    for (String op : operators) {
      int idx = content.indexOf(op);
      if (idx >= 0) {
        String after = content.substring(idx + op.length()).trim();
        // Has something after operator that looks like a value
        if (!after.isEmpty()
            && !after.equals("&&")
            && !after.equals("||")
            && !after.endsWith("&&")
            && !after.endsWith("||")) {
          return true;
        }
      }
    }
    return false;
  }

  /** Get content after the operator in a filter. */
  String getContentAfterOperator(String content) {
    String[] operators = {"==", "!=", ">=", "<=", ">", "<", "~"};
    for (String op : operators) {
      int idx = content.indexOf(op);
      if (idx >= 0) {
        return content.substring(idx + op.length());
      }
    }
    return null;
  }

  /** Count commas in parameter string. */
  int countCommas(String params) {
    int count = 0;
    for (char c : params.toCharArray()) {
      if (c == ',') count++;
    }
    return count;
  }

  /** Extract partial input from function parameters (after last comma). */
  String extractParameterPartial(String params) {
    int lastComma = params.lastIndexOf(',');
    if (lastComma >= 0) {
      return params.substring(lastComma + 1).trim();
    }
    return params.trim();
  }

  /** Parse a field path into segments. */
  List<String> parseFieldPath(String path) {
    if (path == null || path.isEmpty()) {
      return Collections.emptyList();
    }
    List<String> segments = new ArrayList<>();
    for (String seg : path.split("/")) {
      if (!seg.isEmpty()) {
        segments.add(seg);
      }
    }
    return segments;
  }

  // ========== Token-based helper methods ==========

  /** Check if cursor is inside square brackets [...]. */
  private boolean isInsideBrackets(List<Token> tokens, int cursor) {
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

  /** Check if cursor is inside parentheses (...). */
  private boolean isInsideParentheses(List<Token> tokens, int cursor) {
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

  /** Check if cursor is after a pipe token. */
  private boolean isAfterPipeToken(List<Token> tokens, Token cursorToken) {
    if (cursorToken.type() == TokenType.PIPE) {
      return true;
    }

    // Find previous non-whitespace token
    int cursorIdx = tokens.indexOf(cursorToken);
    for (int i = cursorIdx - 1; i >= 0; i--) {
      Token prev = tokens.get(i);
      if (prev.type() == TokenType.WHITESPACE) {
        continue;
      }
      return prev.type() == TokenType.PIPE;
    }
    return false;
  }

  /** Check if tokens only contain command and whitespace. */
  private boolean hasOnlyCommandAndWhitespace(List<Token> tokens) {
    boolean foundCommand = false;
    for (Token token : tokens) {
      if (token.type() == TokenType.EOF) {
        break;
      }
      if (token.type() == TokenType.WHITESPACE) {
        continue;
      }
      if (!foundCommand && token.type() == TokenType.IDENTIFIER) {
        foundCommand = true;
        continue;
      }
      // Found something other than command and whitespace
      return false;
    }
    return foundCommand;
  }

  /** Extract partial input from cursor token. */
  private String extractPartialInput(Token cursorToken, int cursor) {
    if (cursorToken == null || cursorToken.type() == TokenType.EOF) {
      return "";
    }

    // Structural tokens indicate we're at the start of a new segment
    if (cursorToken.type() == TokenType.SLASH
        || cursorToken.type() == TokenType.PIPE
        || cursorToken.type() == TokenType.BRACKET_OPEN
        || cursorToken.type() == TokenType.PAREN_OPEN
        || cursorToken.type() == TokenType.WHITESPACE) {
      return "";
    }

    if (cursorToken.type() == TokenType.IDENTIFIER) {
      // Return portion up to cursor
      int relativePos = cursor - cursorToken.start();
      if (relativePos > 0 && relativePos <= cursorToken.value().length()) {
        return cursorToken.value().substring(0, relativePos);
      }
    }
    return cursorToken.value();
  }

  /** Analyze filter context from tokens. */
  private CompletionContext analyzeFilterContextFromTokens(
      List<Token> tokens, int cursor, String fullLine) {
    Token cursorToken = tokenizer.tokenAtCursor(tokens, cursor);
    String partial = extractPartialInput(cursorToken, cursor);

    // Extract rootType, eventType, and field path from the path before the bracket
    // Example: "show events/jdk.ExecutionSample/stackTrace["
    //   rootType = "events"
    //   eventType = "jdk.ExecutionSample"
    //   fieldPath = ["stackTrace"]
    String rootType = null;
    String eventType = null;
    List<String> fieldPath = new ArrayList<>();

    // Find bracket position
    int bracketIndex = -1;
    for (int i = 0; i < tokens.size(); i++) {
      if (tokens.get(i).type() == TokenType.BRACKET_OPEN && tokens.get(i).start() < cursor) {
        bracketIndex = i;
        break;
      }
    }

    if (bracketIndex >= 0) {
      // Parse path backwards from bracket, collecting all identifiers
      List<String> identifiers = new ArrayList<>();
      for (int i = bracketIndex - 1; i >= 0; i--) {
        Token t = tokens.get(i);

        if (t.type() == TokenType.IDENTIFIER) {
          String value = t.value();
          // Check if this is a root type
          if (value.equals("events") || value.equals("metadata") || value.equals("cp")) {
            rootType = value;
            break; // Found root, stop parsing
          }
          // Collect identifier (we're going backwards, so they're in reverse order)
          identifiers.add(value);
        }
      }

      // Now assign identifiers correctly:
      // When going backwards, we collected: ["stackTrace", "jdk.ExecutionSample"]
      // The LAST item is the event type (closest to root)
      // The REST are the field path in reverse order
      if (!identifiers.isEmpty()) {
        eventType = identifiers.get(identifiers.size() - 1);
        for (int i = identifiers.size() - 2; i >= 0; i--) {
          fieldPath.add(identifiers.get(i));
        }
      }
    }

    // Build extras map with nestedPath and filterContent
    Map<String, String> extras = new HashMap<>();

    // Add nestedPath if we have a field path
    if (!fieldPath.isEmpty()) {
      // FilterFieldCompleter expects "nestedPath" in format "field1/field2/" with trailing slash
      StringBuilder nestedPath = new StringBuilder();
      for (String field : fieldPath) {
        nestedPath.append(field).append("/");
      }
      extras.put("nestedPath", nestedPath.toString());
    }

    // Add filterContent (everything between '[' and cursor)
    if (bracketIndex >= 0) {
      Token bracketToken = tokens.get(bracketIndex);
      String filterContent = fullLine.substring(bracketToken.end(), cursor);
      extras.put("filterContent", filterContent);
    }

    // Determine filter context type by looking at tokens before cursor
    // FILTER_FIELD: after '[' or after '&&' or '||'
    // FILTER_OPERATOR: after identifier (field name) + whitespace
    // FILTER_LOGICAL: after complete condition (field operator value)

    Token lastNonWhitespace = null;
    Token secondLastNonWhitespace = null;
    for (Token t : tokens) {
      if (t.start() >= cursor) break;
      if (t.type() != TokenType.WHITESPACE && t.type() != TokenType.EOF) {
        secondLastNonWhitespace = lastNonWhitespace;
        lastNonWhitespace = t;
      }
    }

    if (lastNonWhitespace == null) {
      // Empty filter
      return CompletionContext.builder()
          .type(CompletionContextType.FILTER_FIELD)
          .command("show")
          .rootType(rootType)
          .eventType(eventType)
          .fieldPath(fieldPath)
          .partialInput(partial)
          .fullLine(fullLine)
          .cursor(cursor)
          .extras(extras)
          .build();
    }

    // After '[', '&&', '||' -> FILTER_FIELD
    if (lastNonWhitespace.type() == TokenType.BRACKET_OPEN
        || lastNonWhitespace.type() == TokenType.AND
        || lastNonWhitespace.type() == TokenType.OR) {
      return CompletionContext.builder()
          .type(CompletionContextType.FILTER_FIELD)
          .command("show")
          .rootType(rootType)
          .eventType(eventType)
          .fieldPath(fieldPath)
          .partialInput(partial)
          .fullLine(fullLine)
          .cursor(cursor)
          .extras(extras)
          .build();
    }

    // After identifier with whitespace -> could be FILTER_OPERATOR
    if (cursorToken != null
        && cursorToken.type() == TokenType.WHITESPACE
        && lastNonWhitespace.type() == TokenType.IDENTIFIER) {
      return CompletionContext.builder()
          .type(CompletionContextType.FILTER_OPERATOR)
          .command("show")
          .rootType(rootType)
          .eventType(eventType)
          .fieldPath(fieldPath)
          .partialInput(partial)
          .fullLine(fullLine)
          .cursor(cursor)
          .extras(extras)
          .build();
    }

    // After a value (number, string, identifier) following an operator -> FILTER_LOGICAL
    boolean hasOperator = false;
    for (Token t : tokens) {
      if (t.start() >= cursor) break;
      if (t.type() == TokenType.GT
          || t.type() == TokenType.LT
          || t.type() == TokenType.EQUALS
          || t.type() == TokenType.DOUBLE_EQUALS
          || t.type() == TokenType.NOT_EQUALS
          || t.type() == TokenType.GTE
          || t.type() == TokenType.LTE
          || t.type() == TokenType.TILDE) {
        hasOperator = true;
      }
    }

    if (hasOperator
        && (lastNonWhitespace.type() == TokenType.NUMBER
            || lastNonWhitespace.type() == TokenType.STRING_LITERAL
            || lastNonWhitespace.type() == TokenType.IDENTIFIER)) {
      return CompletionContext.builder()
          .type(CompletionContextType.FILTER_LOGICAL)
          .command("show")
          .rootType(rootType)
          .eventType(eventType)
          .fieldPath(fieldPath)
          .partialInput(partial)
          .fullLine(fullLine)
          .cursor(cursor)
          .extras(extras)
          .build();
    }

    // Default to FILTER_FIELD if typing identifier
    if (cursorToken != null && cursorToken.type() == TokenType.IDENTIFIER) {
      return CompletionContext.builder()
          .type(CompletionContextType.FILTER_FIELD)
          .command("show")
          .rootType(rootType)
          .eventType(eventType)
          .fieldPath(fieldPath)
          .partialInput(partial)
          .fullLine(fullLine)
          .cursor(cursor)
          .extras(extras)
          .build();
    }

    // Fallback
    return CompletionContext.builder()
        .type(CompletionContextType.FILTER_FIELD)
        .command("show")
        .rootType(rootType)
        .eventType(eventType)
        .fieldPath(fieldPath)
        .partialInput(partial)
        .fullLine(fullLine)
        .cursor(cursor)
        .extras(extras)
        .build();
  }

  /** Analyze function context from tokens. */
  private CompletionContext analyzeFunctionContextFromTokens(
      List<Token> tokens, int cursor, String fullLine) {
    Token cursorToken = tokenizer.tokenAtCursor(tokens, cursor);
    String partial = extractPartialInput(cursorToken, cursor);

    // Find function name (identifier before opening paren)
    String functionName = null;
    int parenIndex = -1;
    for (int i = 0; i < tokens.size(); i++) {
      if (tokens.get(i).type() == TokenType.PAREN_OPEN && tokens.get(i).start() < cursor) {
        parenIndex = i;
        // Look backward for function name
        for (int j = i - 1; j >= 0; j--) {
          if (tokens.get(j).type() == TokenType.IDENTIFIER) {
            functionName = tokens.get(j).value();
            break;
          }
        }
      }
    }

    // Extract eventType from the path before the pipe
    String eventType = null;
    for (int i = 0; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      if (t.type() == TokenType.PIPE && t.start() < cursor) {
        // Look backward for event type (identifier after slash, before pipe)
        for (int j = i - 1; j >= 0; j--) {
          Token prev = tokens.get(j);
          if (prev.type() == TokenType.IDENTIFIER && j > 0) {
            Token beforePrev = tokens.get(j - 1);
            if (beforePrev.type() == TokenType.SLASH) {
              eventType = prev.value();
              break;
            }
          }
        }
        break;
      }
    }

    // Count commas between paren and cursor to determine parameter index
    int paramIndex = 0;
    if (parenIndex >= 0) {
      for (int i = parenIndex + 1; i < tokens.size(); i++) {
        if (tokens.get(i).start() >= cursor) break;
        if (tokens.get(i).type() == TokenType.COMMA) {
          paramIndex++;
        }
      }
    }

    // Special case: top() function's first parameter is a number, not a field
    // Return UNKNOWN to avoid suggesting fields
    if ("top".equals(functionName) && paramIndex == 0) {
      return CompletionContext.builder()
          .type(CompletionContextType.UNKNOWN)
          .command("show")
          .partialInput(partial)
          .fullLine(fullLine)
          .cursor(cursor)
          .build();
    }

    return CompletionContext.builder()
        .type(CompletionContextType.FUNCTION_PARAM)
        .command("show")
        .eventType(eventType)
        .functionName(functionName)
        .parameterIndex(paramIndex)
        .partialInput(partial)
        .fullLine(fullLine)
        .cursor(cursor)
        .build();
  }

  /** Analyze pipeline context from tokens. */
  private CompletionContext analyzePipelineContextFromTokens(
      List<Token> tokens, int cursor, String fullLine) {
    Token cursorToken = tokenizer.tokenAtCursor(tokens, cursor);
    String partial = extractPartialInput(cursorToken, cursor);

    // Extract eventType from the path before the pipe
    String eventType = null;
    for (int i = 0; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      if (t.type() == TokenType.PIPE && t.start() < cursor) {
        // Look backward for event type (identifier after slash, before pipe)
        for (int j = i - 1; j >= 0; j--) {
          Token prev = tokens.get(j);
          if (prev.type() == TokenType.IDENTIFIER && j > 0) {
            Token beforePrev = tokens.get(j - 1);
            if (beforePrev.type() == TokenType.SLASH) {
              eventType = prev.value();
              break;
            }
          }
        }
        break;
      }
    }

    return CompletionContext.builder()
        .type(CompletionContextType.PIPELINE_OPERATOR)
        .command("show")
        .eventType(eventType)
        .partialInput(partial)
        .fullLine(fullLine)
        .cursor(cursor)
        .build();
  }

  /** Analyze path from tokens (events/, metadata/, etc.). */
  private PathInfo analyzePathFromTokens(List<Token> tokens, int cursor) {
    // Walk tokens to find root (events, metadata, cp) and path segments
    // Don't process IDENTIFIER tokens where cursor is at or inside them (still typing)
    // DO process structural tokens (SLASH, etc.) even if cursor is at their end
    String rootType = null;
    String eventType = null;
    List<String> fieldPath = new ArrayList<>();
    boolean afterSlash = false;

    for (Token token : tokens) {
      // For IDENTIFIER tokens, skip if cursor is inside or at the end (still typing)
      // For other tokens (SLASH, WHITESPACE, etc.), process even if cursor is at end
      if (token.type() == TokenType.IDENTIFIER && token.containsCursor(cursor)) {
        break;
      }
      if (token.start() >= cursor) {
        break;
      }

      switch (token.type()) {
        case IDENTIFIER -> {
          if (rootType == null
              && (token.value().equals("events")
                  || token.value().equals("metadata")
                  || token.value().equals("cp")
                  || token.value().equals("chunks"))) {
            rootType = token.value();
          } else if (rootType != null && eventType == null && afterSlash) {
            eventType = token.value();
            afterSlash = false;
          } else if (eventType != null && afterSlash) {
            fieldPath.add(token.value());
            afterSlash = false;
          }
        }
        case SLASH -> afterSlash = true;
        default -> {}
      }
    }

    if (rootType != null) {
      return new PathInfo(rootType, eventType, fieldPath);
    }
    return null;
  }

  /** Build context from path info. */
  private CompletionContext buildPathContext(PathInfo pathInfo, String fullLine, int cursor) {
    List<Token> tokens = tokenizer.tokenize(fullLine);
    Token cursorToken = tokenizer.tokenAtCursor(tokens, cursor);
    String partial = extractPartialInput(cursorToken, cursor);

    if (pathInfo.eventType == null) {
      // Special handling for chunks - after "chunks/", we complete chunk IDs
      if ("chunks".equals(pathInfo.rootType)) {
        return CompletionContext.builder()
            .type(CompletionContextType.CHUNK_ID)
            .command("show")
            .rootType(pathInfo.rootType)
            .partialInput(partial)
            .fullLine(fullLine)
            .cursor(cursor)
            .build();
      }

      // Still typing event type
      return CompletionContext.builder()
          .type(CompletionContextType.EVENT_TYPE)
          .command("show")
          .rootType(pathInfo.rootType)
          .partialInput(partial)
          .fullLine(fullLine)
          .cursor(cursor)
          .build();
    }

    // Typing field path
    return CompletionContext.builder()
        .type(CompletionContextType.FIELD_PATH)
        .command("show")
        .rootType(pathInfo.rootType)
        .eventType(pathInfo.eventType)
        .fieldPath(pathInfo.fieldPath)
        .partialInput(partial)
        .fullLine(fullLine)
        .cursor(cursor)
        .build();
  }

  /** Path information extracted from tokens. */
  private record PathInfo(String rootType, String eventType, List<String> fieldPath) {}

  // ========== Internal position classes ==========

  record FilterPosition(int openBracket, int closeBracket, String content) {}

  record FunctionPosition(String functionName, int openParen, int closeParen, String parameters) {}
}

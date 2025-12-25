package io.jafar.shell.cli.completion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jline.reader.ParsedLine;

/**
 * Analyzes the input line and cursor position to determine the completion context. This is the
 * single source of truth for context detection - all logic is isolated here.
 */
public class CompletionContextAnalyzer {

  // Functions that take field parameters
  private static final String[] FIELD_FUNCTIONS = {"sum", "groupBy", "select", "top"};

  // Pattern to match function calls: funcName(
  private static final Pattern FUNCTION_PATTERN =
      Pattern.compile(
          "(sum|groupBy|select|top|decorateByTime|decorateByKey)\\s*\\(", Pattern.CASE_INSENSITIVE);

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
    if ("show".equals(command)) {
      return analyzeShowContext(line);
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

  // ========== Internal position classes ==========

  record FilterPosition(int openBracket, int closeBracket, String content) {}

  record FunctionPosition(String functionName, int openParen, int closeParen, String parameters) {}
}

package io.jafar.shell.cli.completion.completers;

import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextType;
import io.jafar.shell.cli.completion.ContextCompleter;
import io.jafar.shell.cli.completion.MetadataService;
import java.util.List;
import org.jline.reader.Candidate;

/**
 * Completer for function parameters inside sum(), groupBy(), select(), top(). Suggests field names
 * from the event type.
 */
public class FunctionParamCompleter implements ContextCompleter {
  private static final boolean TRACE = Boolean.getBoolean("jfr.shell.completion.trace");

  // Keyword parameters for groupBy: agg=count|sum|avg|min|max, value=path
  private static final String[] GROUPBY_KEYWORDS = {"agg=", "value="};
  private static final String[] GROUPBY_AGG_VALUES = {"count", "sum", "avg", "min", "max"};

  // Keyword parameters for top: by=path, asc=true|false
  private static final String[] TOP_KEYWORDS = {"by=", "asc="};
  private static final String[] TOP_ASC_VALUES = {"true", "false"};

  @Override
  public boolean canHandle(CompletionContext ctx) {
    // Handle both regular function params and select expressions (which get field completion)
    return ctx.type() == CompletionContextType.FUNCTION_PARAM
        || ctx.type() == CompletionContextType.SELECT_EXPRESSION;
  }

  /**
   * Handle keyword parameters for functions like groupBy and top. Returns a KeywordResult
   * indicating how to proceed.
   */
  private KeywordResult handleKeywordParameters(
      CompletionContext ctx,
      String functionName,
      int paramIndex,
      String partial,
      List<Candidate> candidates) {

    // groupBy: first param is field, subsequent are agg= or value=
    if ("groupBy".equals(functionName) && paramIndex > 0) {
      return completeGroupByKeywords(ctx, partial, candidates);
    }

    // top: first param is number (handled elsewhere as UNKNOWN), subsequent are by= or asc=
    if ("top".equals(functionName) && paramIndex > 0) {
      return completeTopKeywords(ctx, partial, candidates);
    }

    return KeywordResult.NOT_KEYWORD; // Not a keyword context, continue with field completion
  }

  /** Result of keyword parameter handling */
  private enum KeywordResult {
    HANDLED, // Completion was handled, stop processing
    NOT_KEYWORD, // Not a keyword context, continue with normal field completion
    FIELD_AFTER_KEYWORD // After keyword= that takes a field path
  }

  /** Info for completing field names after a keyword like value= or by= */
  private record FieldAfterKeyword(String keywordPrefix, String fieldPartial) {}

  /** Check if partial starts with a keyword that takes a field path */
  private FieldAfterKeyword extractFieldAfterKeyword(String partial) {
    String lowerPartial = partial.toLowerCase();
    if (lowerPartial.startsWith("value=")) {
      return new FieldAfterKeyword("value=", partial.substring(6));
    }
    if (lowerPartial.startsWith("by=")) {
      return new FieldAfterKeyword("by=", partial.substring(3));
    }
    return null;
  }

  private KeywordResult completeGroupByKeywords(
      CompletionContext ctx, String partial, List<Candidate> candidates) {
    String jlineWord = ctx.jlineWord();

    // Check if we're completing after "agg=" (suggest aggregation functions)
    if (partial.toLowerCase().startsWith("agg=")) {
      String afterEq = partial.substring(4);
      String prefix = calculateJlinePrefix(jlineWord, partial);
      for (String aggValue : GROUPBY_AGG_VALUES) {
        if (aggValue.startsWith(afterEq.toLowerCase())) {
          candidates.add(noSpace(prefix + "agg=" + aggValue));
        }
      }
      return KeywordResult.HANDLED;
    }

    // Check if we're completing after "value=" (suggest field names)
    if (partial.toLowerCase().startsWith("value=")) {
      return KeywordResult.FIELD_AFTER_KEYWORD;
    }

    // Otherwise suggest keyword names (agg=, value=)
    String lowerPartial = partial.toLowerCase();
    String prefix = calculateJlinePrefix(jlineWord, partial);
    for (String keyword : GROUPBY_KEYWORDS) {
      if (keyword.toLowerCase().startsWith(lowerPartial)) {
        candidates.add(noSpace(prefix + keyword));
      }
    }
    return KeywordResult.HANDLED;
  }

  private KeywordResult completeTopKeywords(
      CompletionContext ctx, String partial, List<Candidate> candidates) {
    String jlineWord = ctx.jlineWord();

    // Check if we're completing after "asc=" (suggest true/false)
    if (partial.toLowerCase().startsWith("asc=")) {
      String afterEq = partial.substring(4);
      String prefix = calculateJlinePrefix(jlineWord, partial);
      for (String val : TOP_ASC_VALUES) {
        if (val.startsWith(afterEq.toLowerCase())) {
          candidates.add(noSpace(prefix + "asc=" + val));
        }
      }
      return KeywordResult.HANDLED;
    }

    // Check if we're completing after "by=" (suggest field names)
    if (partial.toLowerCase().startsWith("by=")) {
      return KeywordResult.FIELD_AFTER_KEYWORD;
    }

    // Otherwise suggest keyword names (by=, asc=)
    String lowerPartial = partial.toLowerCase();
    String prefix = calculateJlinePrefix(jlineWord, partial);
    for (String keyword : TOP_KEYWORDS) {
      if (keyword.toLowerCase().startsWith(lowerPartial)) {
        candidates.add(noSpace(prefix + keyword));
      }
    }
    return KeywordResult.HANDLED;
  }

  private String calculateJlinePrefix(String jlineWord, String partial) {
    if (jlineWord == null || jlineWord.isEmpty()) {
      return "";
    }
    if (partial.isEmpty()) {
      return jlineWord;
    }
    if (jlineWord.length() > partial.length()) {
      return jlineWord.substring(0, jlineWord.length() - partial.length());
    }
    return "";
  }

  @Override
  public void complete(
      CompletionContext ctx, MetadataService metadata, List<Candidate> candidates) {
    String eventType = ctx.eventType();
    String partial = ctx.partialInput();
    String functionName = ctx.functionName();
    int paramIndex = ctx.parameterIndex();

    if (TRACE) {
      System.err.println(
          "[TRACE] FunctionParamCompleter: eventType="
              + eventType
              + " partial='"
              + partial
              + "' func="
              + functionName
              + " paramIndex="
              + paramIndex);
    }

    // Handle function-specific keyword parameters (not field names)
    KeywordResult keywordResult =
        handleKeywordParameters(ctx, functionName, paramIndex, partial, candidates);
    if (keywordResult == KeywordResult.HANDLED) {
      return;
    }

    // If we're completing field after a keyword (value=, by=), adjust the partial
    String keywordPrefix = "";
    if (keywordResult == KeywordResult.FIELD_AFTER_KEYWORD) {
      FieldAfterKeyword fak = extractFieldAfterKeyword(partial);
      if (fak != null) {
        keywordPrefix = fak.keywordPrefix();
        partial = fak.fieldPartial();
        if (TRACE) {
          System.err.println(
              "[TRACE] FunctionParamCompleter: field after keyword '"
                  + keywordPrefix
                  + "', adjusted partial='"
                  + partial
                  + "'");
        }
      }
    }

    if (eventType == null || eventType.isEmpty()) {
      if (TRACE) System.err.println("[TRACE] FunctionParamCompleter: early return - no eventType");
      return;
    }

    // Check if partial contains a path separator (nested field navigation)
    // Support both slash (/) and dot (.) notation
    List<String> pathSegments = new java.util.ArrayList<>();
    String lastSegment = partial;

    // Parse the path: "name/value" or "name.value" -> ["name"], partial="value"
    if (partial.contains("/") || partial.contains(".")) {
      // Split on both separators
      String[] parts = partial.split("[/.]");
      if (parts.length > 0) {
        // All but the last are complete path segments
        for (int i = 0; i < parts.length - 1; i++) {
          if (!parts[i].isEmpty()) {
            pathSegments.add(parts[i]);
          }
        }
        // Last part is what we're completing (could be empty after trailing separator)
        lastSegment = parts[parts.length - 1];

        // If partial ends with separator, the last segment is complete too
        if (partial.endsWith("/") || partial.endsWith(".")) {
          if (!lastSegment.isEmpty()) {
            pathSegments.add(lastSegment);
          }
          lastSegment = "";
        }
      }
    }

    // Get field names at the appropriate nesting level
    List<String> fieldNames =
        pathSegments.isEmpty()
            ? metadata.getFieldNames(eventType)
            : metadata.getNestedFieldNames(eventType, pathSegments);

    if (TRACE) {
      System.err.println(
          "[TRACE] FunctionParamCompleter: fieldNames.size()="
              + fieldNames.size()
              + " lastSegment='"
              + lastSegment
              + "'");
    }

    // Build completion prefix from the path segments (for nested fields)
    String nestedPrefix = "";
    if (!pathSegments.isEmpty()) {
      // Use the same separator that was used in the original input
      char separator = partial.contains("/") ? '/' : '.';
      nestedPrefix = String.join(String.valueOf(separator), pathSegments) + separator;
    }

    // Calculate the JLine word prefix to prepend to candidates
    // JLine filters candidates that don't start with line.word()
    // Formula: jlineWordPrefix = jlineWord with partial removed from end
    // Note: Use ORIGINAL partial from context when calculating, not adjusted partial
    String jlineWord = ctx.jlineWord();
    String jlineWordPrefix = "";
    if (jlineWord != null && !jlineWord.isEmpty()) {
      String originalPartial = ctx.partialInput();
      if (originalPartial.isEmpty()) {
        jlineWordPrefix = jlineWord;
      } else if (jlineWord.length() > originalPartial.length()) {
        jlineWordPrefix = jlineWord.substring(0, jlineWord.length() - originalPartial.length());
      }
      // When we have a keywordPrefix, it's already accounted for in jlineWord,
      // so we set jlineWordPrefix to not include it (just the leading part before keyword)
    }

    if (TRACE) {
      System.err.println(
          "[TRACE] FunctionParamCompleter: jlineWord='"
              + jlineWord
              + "' jlineWordPrefix='"
              + jlineWordPrefix
              + "'");
    }

    String lowerLastSegment = lastSegment.toLowerCase();
    int added = 0;
    for (String fieldName : fieldNames) {
      if (fieldName.toLowerCase().startsWith(lowerLastSegment)) {
        // Use noSpace to not add trailing space after field name
        // This allows typing comma for multiple fields in select()
        // Include keywordPrefix (like "value=") if completing after a keyword
        String value = jlineWordPrefix + keywordPrefix + nestedPrefix + fieldName;
        candidates.add(noSpace(value));
        added++;
      }
    }
    if (TRACE) {
      System.err.println("[TRACE] FunctionParamCompleter: candidates added=" + added);
    }
  }
}

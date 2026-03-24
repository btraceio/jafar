package io.jafar.shell.cli.completion.completers;

import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextType;
import io.jafar.shell.cli.completion.ContextCompleter;
import io.jafar.shell.cli.completion.FunctionRegistry;
import io.jafar.shell.cli.completion.FunctionSpec;
import io.jafar.shell.cli.completion.MetadataService;
import io.jafar.shell.cli.completion.ParamSpec;
import java.util.List;
import org.jline.reader.Candidate;

/**
 * Completer for function parameters inside pipeline operators such as sum(), groupBy(), select(),
 * top(), flamegraph(), stackprofile(), etc.
 *
 * <p>Keyword completion (e.g. {@code agg=}, {@code direction=}) is driven entirely by the {@link
 * FunctionSpec} and {@link ParamSpec} registered in {@link FunctionRegistry} — no per-function
 * hardcoding. Positional parameters fall through to field-name completion as before.
 */
public final class FunctionParamCompleter implements ContextCompleter {
  private static final boolean TRACE = Boolean.getBoolean("jfr.shell.completion.trace");

  private static final String[] BOOLEAN_VALUES = {"true", "false"};

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.FUNCTION_PARAM
        || ctx.type() == CompletionContextType.SELECT_EXPRESSION;
  }

  /** Result of keyword parameter handling */
  private enum KeywordResult {
    HANDLED, // Completion was handled, stop processing
    NOT_KEYWORD, // Not a keyword context, continue with normal field completion
    FIELD_AFTER_KEYWORD // After keyword= that takes a field path
  }

  /** Info for completing field names after a keyword like value= or by= */
  private record FieldAfterKeyword(String keywordPrefix, String fieldPartial) {}

  /**
   * Determines whether the current parameter position should be completed as a keyword rather than
   * a positional field. Uses the registered {@link FunctionSpec} to decide.
   */
  private KeywordResult handleKeywordParameters(
      CompletionContext ctx,
      String functionName,
      int paramIndex,
      String partial,
      List<Candidate> candidates) {

    if (functionName == null) return KeywordResult.NOT_KEYWORD;

    FunctionSpec spec = FunctionRegistry.getPipelineOperator(functionName);
    if (spec == null || spec.keywordParams().isEmpty()) return KeywordResult.NOT_KEYWORD;

    // If there is a positional parameter defined at this index and the partial doesn't already
    // look like a keyword assignment, let field completion handle it.
    if (spec.getPositionalParam(paramIndex) != null && !looksLikeKeyword(spec, partial)) {
      return KeywordResult.NOT_KEYWORD;
    }

    return completeKeywordsFromSpec(ctx, spec, partial, candidates);
  }

  /** Returns true if partial begins with a known keyword name followed by '='. */
  private boolean looksLikeKeyword(FunctionSpec spec, String partial) {
    String lowerPartial = partial.toLowerCase();
    return spec.keywordParams().stream()
        .anyMatch(kw -> lowerPartial.startsWith(kw.name().toLowerCase() + "="));
  }

  /**
   * Generic keyword completion driven by {@link FunctionSpec} metadata. Handles ENUM, BOOLEAN,
   * FIELD_PATH, NUMBER, STRING, and EVENT_TYPE keyword params uniformly.
   */
  private KeywordResult completeKeywordsFromSpec(
      CompletionContext ctx, FunctionSpec spec, String partial, List<Candidate> candidates) {

    String lowerPartial = partial.toLowerCase();
    String prefix = calculateJlinePrefix(ctx.jlineWord(), partial);

    // Check if partial starts with a known keyword prefix (e.g. "direction=")
    for (ParamSpec kw : spec.keywordParams()) {
      String kwPrefix = kw.name() + "=";
      if (lowerPartial.startsWith(kwPrefix.toLowerCase())) {
        String afterEq = partial.substring(kwPrefix.length());
        switch (kw.type()) {
          case ENUM -> {
            for (String val : kw.enumValues()) {
              if (val.startsWith(afterEq.toLowerCase())) {
                candidates.add(noSpace(prefix + kwPrefix + val));
              }
            }
            return KeywordResult.HANDLED;
          }
          case BOOLEAN -> {
            for (String val : BOOLEAN_VALUES) {
              if (val.startsWith(afterEq.toLowerCase())) {
                candidates.add(noSpace(prefix + kwPrefix + val));
              }
            }
            return KeywordResult.HANDLED;
          }
          case FIELD_PATH -> {
            return KeywordResult.FIELD_AFTER_KEYWORD;
          }
          default -> {
            // NUMBER, STRING, EVENT_TYPE, EXPRESSION — no value completion
            return KeywordResult.HANDLED;
          }
        }
      }
    }

    // Partial doesn't match any keyword= prefix → suggest matching keyword names
    for (ParamSpec kw : spec.keywordParams()) {
      String kwName = kw.name() + "=";
      if (kwName.toLowerCase().startsWith(lowerPartial)) {
        candidates.add(noSpace(prefix + kwName));
      }
    }
    return KeywordResult.HANDLED;
  }

  /**
   * Extracts the keyword prefix and field partial from a partial like {@code "value=someField"}.
   * Checks all FIELD_PATH keyword params from the function spec so no per-function hardcoding is
   * needed.
   */
  private FieldAfterKeyword extractFieldAfterKeyword(String partial, String functionName) {
    if (functionName == null) return null;
    FunctionSpec spec = FunctionRegistry.getPipelineOperator(functionName);
    if (spec == null) return null;
    for (ParamSpec kw : spec.keywordParams()) {
      if (kw.type() == ParamSpec.ParamType.FIELD_PATH) {
        String kwPrefix = kw.name() + "=";
        if (partial.toLowerCase().startsWith(kwPrefix.toLowerCase())) {
          return new FieldAfterKeyword(
              partial.substring(0, kwPrefix.length()), partial.substring(kwPrefix.length()));
        }
      }
    }
    return null;
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

    // Handle keyword parameters generically via FunctionSpec
    KeywordResult keywordResult =
        handleKeywordParameters(ctx, functionName, paramIndex, partial, candidates);
    if (keywordResult == KeywordResult.HANDLED) {
      return;
    }

    // If completing a field path after a keyword (e.g. value=), adjust the partial
    String keywordPrefix = "";
    if (keywordResult == KeywordResult.FIELD_AFTER_KEYWORD) {
      FieldAfterKeyword fak = extractFieldAfterKeyword(partial, functionName);
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

    if (partial.contains("/") || partial.contains(".")) {
      String[] parts = partial.split("[/.]");
      if (parts.length > 0) {
        for (int i = 0; i < parts.length - 1; i++) {
          if (!parts[i].isEmpty()) {
            pathSegments.add(parts[i]);
          }
        }
        lastSegment = parts[parts.length - 1];
        if (partial.endsWith("/") || partial.endsWith(".")) {
          if (!lastSegment.isEmpty()) {
            pathSegments.add(lastSegment);
          }
          lastSegment = "";
        }
      }
    }

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

    String nestedPrefix = "";
    if (!pathSegments.isEmpty()) {
      char separator = partial.contains("/") ? '/' : '.';
      nestedPrefix = String.join(String.valueOf(separator), pathSegments) + separator;
    }

    String jlineWord = ctx.jlineWord();
    String jlineWordPrefix = "";
    if (jlineWord != null && !jlineWord.isEmpty()) {
      String originalPartial = ctx.partialInput();
      if (originalPartial.isEmpty()) {
        jlineWordPrefix = jlineWord;
      } else if (jlineWord.length() > originalPartial.length()) {
        jlineWordPrefix = jlineWord.substring(0, jlineWord.length() - originalPartial.length());
      }
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

    if (ctx.type() == CompletionContextType.SELECT_EXPRESSION && pathSegments.isEmpty()) {
      for (var spec : FunctionRegistry.getSelectFunctions()) {
        if (spec.name().toLowerCase().startsWith(lowerLastSegment)) {
          candidates.add(noSpace(jlineWordPrefix + keywordPrefix + spec.name() + "("));
          added++;
        }
      }
    }

    for (String fieldName : fieldNames) {
      if (fieldName.toLowerCase().startsWith(lowerLastSegment)) {
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

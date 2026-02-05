package io.jafar.hdump.shell.cli.completion.completers;

import io.jafar.hdump.shell.cli.completion.HdumpMetadataService;
import io.jafar.shell.core.completion.CompletionContext;
import io.jafar.shell.core.completion.CompletionContextType;
import io.jafar.shell.core.completion.ContextCompleter;
import java.util.List;
import java.util.Set;
import org.jline.reader.Candidate;

/**
 * Completer for function parameters. Suggests field names inside function calls like select(),
 * top(), groupBy(), etc.
 */
public final class HdumpFunctionParamCompleter implements ContextCompleter<HdumpMetadataService> {

  private static final boolean DEBUG = Boolean.getBoolean("hdump.shell.completion.debug");
  private static final Set<String> AGGREGATE_FUNCTIONS = Set.of("sum", "avg", "count", "min", "max");

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.FUNCTION_PARAM;
  }

  @Override
  public void complete(
      CompletionContext ctx, HdumpMetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput();
    String rootType = ctx.rootType();
    String functionName = ctx.functionName();
    String jlineWord = ctx.jlineWord();

    // Use the shared method from ContextCompleter to calculate the JLine prefix
    // But if jlineWord is just whitespace and partial is empty, don't use it as prefix
    String prefix;
    if (partial.isEmpty() && !jlineWord.isEmpty() && jlineWord.isBlank()) {
      // jlineWord is whitespace-only, don't use it as prefix
      prefix = "";
    } else {
      prefix = calculateJlinePrefix(jlineWord, partial);
    }

    if (DEBUG) {
      System.err.println("[FunctionParamCompleter] rootType=" + rootType + ", func=" + functionName + ", partial='" + partial + "', jlineWord='" + jlineWord + "', prefix='" + prefix + "'");
    }

    // Get fields for the current root type
    List<String> fields = metadata.getFieldsForRootType(rootType);

    if (DEBUG) {
      System.err.println("[FunctionParamCompleter] fields for " + rootType + ": " + fields);
    }

    // For checkLeaks, handle named parameters
    if ("checkLeaks".equals(functionName)) {
      // Complete detector= values
      if (partial.startsWith("detector=")) {
        String valuePartial = partial.substring(9);
        // Remove quotes if present
        if (valuePartial.startsWith("\"")) {
          valuePartial = valuePartial.substring(1);
        }

        for (String detectorName : io.jafar.hdump.shell.leaks.LeakDetectorRegistry.getDetectorNames()) {
          if (detectorName.startsWith(valuePartial)) {
            candidates.add(candidateNoSpace("detector=\"" + detectorName + "\"", detectorName,
                io.jafar.hdump.shell.leaks.LeakDetectorRegistry.getDetector(detectorName).getDescription()));
          }
        }
        return;
      }

      // Complete filter= values (variable references)
      if (partial.startsWith("filter=")) {
        String valuePartial = partial.substring(7);
        // Remove quotes if present
        if (valuePartial.startsWith("\"")) {
          valuePartial = valuePartial.substring(1);
        }

        // Suggest variable references starting with $
        if (valuePartial.isEmpty() || "$".startsWith(valuePartial)) {
          candidates.add(candidateNoSpace("filter=\"$", "$", "variable reference"));
        }
        return;
      }

      // Suggest named parameters
      if ("detector=".startsWith(partial) || partial.isEmpty()) {
        candidates.add(candidateNoSpace(prefix + "detector=", "detector=", "built-in detector name"));
      }
      if ("filter=".startsWith(partial) || partial.isEmpty()) {
        candidates.add(candidateNoSpace(prefix + "filter=", "filter=", "custom filter query reference"));
      }
      if ("threshold=".startsWith(partial) || partial.isEmpty()) {
        candidates.add(candidateNoSpace(prefix + "threshold=", "threshold=", "minimum count threshold"));
      }
      if ("minSize=".startsWith(partial) || partial.isEmpty()) {
        candidates.add(candidateNoSpace(prefix + "minSize=", "minSize=", "minimum size in bytes"));
      }
      return;
    }

    // For groupBy, check if we're completing a named parameter value
    if ("groupBy".equals(functionName) && ctx.parameterIndex() > 0) {
      // Check if completing sort= value
      // Use candidateNoSpace so user can type comma or closing paren without extra space
      if (partial.startsWith("sort=")) {
        String valuePartial = partial.substring(5);
        if ("key".startsWith(valuePartial)) {
          candidates.add(candidateNoSpace("sort=key", "key", "sort by group key"));
        }
        if ("value".startsWith(valuePartial)) {
          candidates.add(candidateNoSpace("sort=value", "value", "sort by aggregated value"));
        }
        return;
      }

      // Check if completing asc= value
      if (partial.startsWith("asc=")) {
        String valuePartial = partial.substring(4);
        if ("true".startsWith(valuePartial)) {
          candidates.add(candidateNoSpace("asc=true", "true", "ascending order"));
        }
        if ("false".startsWith(valuePartial)) {
          candidates.add(candidateNoSpace("asc=false", "false", "descending order"));
        }
        return;
      }

      // Check if completing agg= value
      if (partial.startsWith("agg=")) {
        String valuePartial = partial.substring(4);
        for (String agg : AGGREGATE_FUNCTIONS) {
          if (agg.startsWith(valuePartial)) {
            candidates.add(candidateNoSpace("agg=" + agg, agg, "aggregation"));
          }
        }
        return;
      }
    }

    // Check if we're inside a value expression context
    ValueExprContext valueCtx = detectValueExprContext(partial, functionName);
    if (valueCtx != null) {
      // We're inside a value expression - complete field names for the partial identifier
      completeValueExprFields(valueCtx, fields, jlineWord, candidates);
      return;
    }

    // For most functions, suggest field names
    for (String field : fields) {
      if (field.startsWith(partial)) {
        // Value includes prefix so JLine can match, display shows just the field name
        candidates.add(candidate(prefix + field, field, null));
      }
    }

    // For groupBy, suggest aggregation options
    if ("groupBy".equals(functionName) && ctx.parameterIndex() > 0) {
      // Suggest named parameters: agg=, value=, sort=, asc=
      // Use candidateNoSpace because user needs to continue typing the value
      if ("agg=".startsWith(partial) || partial.isEmpty()) {
        candidates.add(candidateNoSpace(prefix + "agg=", "agg=", "aggregation type (count/sum/avg/min/max)"));
      }
      if ("value=".startsWith(partial) || partial.isEmpty()) {
        candidates.add(candidateNoSpace(prefix + "value=", "value=", "value expression to aggregate"));
      }
      if ("sort=".startsWith(partial) || partial.isEmpty()) {
        candidates.add(candidateNoSpace(prefix + "sort=", "sort=", "sort by 'key' or 'value'"));
      }
      if ("asc=".startsWith(partial) || partial.isEmpty()) {
        candidates.add(candidateNoSpace(prefix + "asc=", "asc=", "sort ascending (true/false)"));
      }

      // Suggest aggregate names (for agg= values) and function call syntax
      for (String agg : AGGREGATE_FUNCTIONS) {
        if (agg.startsWith(partial)) {
          // Plain name (for use with agg=)
          candidates.add(candidate(prefix + agg, agg, "aggregation"));
          // Function call syntax: sum(expr) - no space since user continues typing
          candidates.add(candidateNoSpace(prefix + agg + "(", agg + "(", "aggregate expression"));
        }
      }
    }

    // For sortBy and top, suggest sort directions
    if (("sortBy".equals(functionName) || "top".equals(functionName))
        && ctx.parameterIndex() > 0) {
      if ("asc".startsWith(partial)) {
        candidates.add(candidate(prefix + "asc", "asc", "ascending order"));
      }
      if ("desc".startsWith(partial)) {
        candidates.add(candidate(prefix + "desc", "desc", "descending order"));
      }
    }
  }

  /**
   * Detects if we're inside a value expression context.
   * Returns the context with the expression being typed and the partial identifier.
   */
  private ValueExprContext detectValueExprContext(String partial, String functionName) {
    // Check for value= prefix
    if (partial.startsWith("value=")) {
      String expr = partial.substring(6); // after "value="
      return parseValueExprPartial(expr, "value=");
    }

    // Check if function is an aggregate function (we're inside sum(...), avg(...), etc.)
    if (AGGREGATE_FUNCTIONS.contains(functionName.toLowerCase())) {
      return parseValueExprPartial(partial, "");
    }

    return null;
  }

  /**
   * Parse a value expression to find the partial identifier being typed.
   * Handles expressions like "instanceSize * inst" -> partial="inst", exprPrefix="instanceSize * "
   */
  private ValueExprContext parseValueExprPartial(String expr, String contextPrefix) {
    if (expr.isEmpty()) {
      return new ValueExprContext(contextPrefix, "", "");
    }

    // Find the last position where an identifier could start
    // (after operators, parentheses, or at the beginning)
    int identStart = -1;
    for (int i = expr.length() - 1; i >= 0; i--) {
      char c = expr.charAt(i);
      if (isOperatorOrDelimiter(c)) {
        identStart = i + 1;
        break;
      }
    }

    if (identStart < 0) {
      // The whole expression is the identifier
      identStart = 0;
    }

    // Skip any whitespace after the operator
    while (identStart < expr.length() && Character.isWhitespace(expr.charAt(identStart))) {
      identStart++;
    }

    String exprPrefix = expr.substring(0, identStart);
    String identPartial = expr.substring(identStart);

    return new ValueExprContext(contextPrefix, exprPrefix, identPartial);
  }

  private boolean isOperatorOrDelimiter(char c) {
    return c == '+' || c == '-' || c == '*' || c == '/' || c == '(' || c == ')' || c == ' ';
  }

  /**
   * Complete field names inside a value expression.
   * The prefix calculation must account for what JLine considers the current "word".
   */
  private void completeValueExprFields(
      ValueExprContext valueCtx, List<String> fields, String jlineWord, List<Candidate> candidates) {
    String identPartial = valueCtx.identPartial;

    // JLine's word parsing might split on '=' or other characters.
    // We need to figure out what portion of the expression JLine considers its "word".
    // The completion value should replace that word with the appropriate content.
    //
    // Example: If user typed "value=inst" and jlineWord is "inst", completion should be "instanceSize"
    // Example: If user typed "value=" and jlineWord is "", completion should be "instanceSize"
    // Example: If user typed "value=" and jlineWord is "value=", completion should be "value=instanceSize"
    String exprWithIdent = valueCtx.exprPrefix + identPartial;

    // Check if jlineWord matches different portions of what we parsed
    String completionPrefix;
    if (jlineWord.isEmpty() || jlineWord.isBlank()) {
      // JLine word is empty or whitespace-only - just complete the field
      // Don't prepend context/expr prefix as that's already on the line
      completionPrefix = "";
    } else if (jlineWord.equals(identPartial)) {
      // JLine word is just the identifier partial - complete with just the field
      completionPrefix = "";
    } else if (jlineWord.equals(exprWithIdent)) {
      // JLine word is the expr + identifier - complete with exprPrefix + field
      completionPrefix = valueCtx.exprPrefix;
    } else if (jlineWord.equals(valueCtx.contextPrefix + exprWithIdent)) {
      // JLine word is the full thing - complete with everything
      completionPrefix = valueCtx.contextPrefix + valueCtx.exprPrefix;
    } else {
      // Fallback: use the standard prefix calculation
      String fullPartial = valueCtx.contextPrefix + valueCtx.exprPrefix + identPartial;
      String basePrefix = calculateJlinePrefix(jlineWord, fullPartial);
      // Don't use whitespace as prefix
      if (basePrefix.isBlank()) {
        completionPrefix = "";
      } else {
        completionPrefix = basePrefix + valueCtx.contextPrefix + valueCtx.exprPrefix;
      }
    }

    if (DEBUG) {
      System.err.println("[FunctionParamCompleter] ValueExpr context: identPartial='" + identPartial
          + "', jlineWord='" + jlineWord + "', completionPrefix='" + completionPrefix + "'");
    }

    for (String field : fields) {
      if (field.startsWith(identPartial)) {
        candidates.add(candidate(completionPrefix + field, field, null));
      }
    }
  }

  /**
   * Context for value expression completion.
   * @param contextPrefix The prefix before the expression (e.g., "value=")
   * @param exprPrefix The expression prefix before the current identifier (e.g., "instanceSize * ")
   * @param identPartial The partial identifier being typed (e.g., "inst")
   */
  private record ValueExprContext(String contextPrefix, String exprPrefix, String identPartial) {}
}

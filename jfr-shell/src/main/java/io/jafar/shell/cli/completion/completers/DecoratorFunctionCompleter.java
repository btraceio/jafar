package io.jafar.shell.cli.completion.completers;

import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextType;
import io.jafar.shell.cli.completion.ContextCompleter;
import io.jafar.shell.cli.completion.MetadataService;
import java.util.List;
import java.util.Set;
import org.jline.reader.Candidate;

/**
 * Completer for decorator functions: decorateByTime() and decorateByKey(). These functions have a
 * different parameter structure than aggregation functions:
 *
 * <ul>
 *   <li>First parameter: event type name (e.g., jdk.ExecutionSample)
 *   <li>Named parameters: fields=, key=, decoratorKey=, threadPath=, decoratorThreadPath=
 * </ul>
 */
public class DecoratorFunctionCompleter implements ContextCompleter {

  @Override
  public boolean canHandle(CompletionContext ctx) {
    if (ctx.type() != CompletionContextType.FUNCTION_PARAM) {
      return false;
    }
    String funcName = ctx.functionName();
    return funcName != null
        && (funcName.equalsIgnoreCase("decorateByTime")
            || funcName.equalsIgnoreCase("decorateByKey"));
  }

  @Override
  public void complete(
      CompletionContext ctx, MetadataService metadata, List<Candidate> candidates) {
    String funcName = ctx.functionName();
    String params = ctx.extras().getOrDefault("functionParams", "");
    String partial = ctx.partialInput().toLowerCase();

    // If no comma yet, we're completing the first parameter (event type)
    if (!params.contains(",")) {
      completeEventTypes(metadata, partial, candidates);
      return;
    }

    // After comma, we need to determine if we're:
    // 1. Starting a new named parameter (complete parameter names)
    // 2. Inside a named parameter value (complete based on parameter type)

    // Find the last parameter assignment
    int lastEquals = params.lastIndexOf('=');
    int lastComma = params.lastIndexOf(',');

    if (lastEquals < 0 || lastComma > lastEquals) {
      // No equals after last comma, so we're completing a named parameter name
      completeNamedParameters(funcName, partial, candidates);
    } else {
      // We're inside a named parameter value
      String paramName = extractParameterName(params, lastEquals);
      completeParameterValue(paramName, partial, metadata, ctx, candidates);
    }
  }

  /** Complete event type names for the first parameter. */
  private void completeEventTypes(
      MetadataService metadata, String partial, List<Candidate> candidates) {
    Set<String> eventTypes = metadata.getEventTypes();
    for (String type : eventTypes) {
      if (type.toLowerCase().startsWith(partial)) {
        candidates.add(noSpace(type));
      }
    }
  }

  /** Complete named parameter names based on function type. */
  private void completeNamedParameters(
      String funcName, String partial, List<Candidate> candidates) {
    if (funcName.equalsIgnoreCase("decorateByTime")) {
      addIfMatches("fields=", partial, "Fields to include from decorator events", candidates);
      addIfMatches(
          "threadPath=", partial, "Path to thread in primary events (default: thread)", candidates);
      addIfMatches(
          "decoratorThreadPath=",
          partial,
          "Path to thread in decorator events (default: sampledThread)",
          candidates);
    } else if (funcName.equalsIgnoreCase("decorateByKey")) {
      addIfMatches("key=", partial, "Key expression for primary events", candidates);
      addIfMatches("decoratorKey=", partial, "Key expression for decorator events", candidates);
      addIfMatches("fields=", partial, "Fields to include from decorator events", candidates);
    }
  }

  /** Complete values for named parameters based on parameter type. */
  private void completeParameterValue(
      String paramName,
      String partial,
      MetadataService metadata,
      CompletionContext ctx,
      List<Candidate> candidates) {
    if (paramName == null) {
      return;
    }

    // Extract decorator event type from function params
    String decoratorType =
        extractDecoratorEventType(ctx.extras().getOrDefault("functionParams", ""));

    if (paramName.equalsIgnoreCase("fields")) {
      // Complete field names from decorator event type
      if (decoratorType != null) {
        List<String> fieldNames = metadata.getFieldNames(decoratorType);
        for (String fieldName : fieldNames) {
          if (fieldName.toLowerCase().startsWith(partial)) {
            candidates.add(noSpace(fieldName));
          }
        }
      }
    } else if (paramName.equalsIgnoreCase("threadPath")
        || paramName.equalsIgnoreCase("decoratorThreadPath")) {
      // Complete field paths (could be thread, sampledThread, etc.)
      String eventType = paramName.equalsIgnoreCase("threadPath") ? ctx.eventType() : decoratorType;
      if (eventType != null) {
        List<String> fieldNames = metadata.getFieldNames(eventType);
        for (String fieldName : fieldNames) {
          if (fieldName.toLowerCase().startsWith(partial)
              && fieldName.toLowerCase().contains("thread")) {
            candidates.add(noSpace(fieldName));
          }
        }
      }
    } else if (paramName.equalsIgnoreCase("key") || paramName.equalsIgnoreCase("decoratorKey")) {
      // Complete field paths for key expressions
      String eventType = paramName.equalsIgnoreCase("key") ? ctx.eventType() : decoratorType;
      if (eventType != null) {
        List<String> fieldNames = metadata.getFieldNames(eventType);
        for (String fieldName : fieldNames) {
          if (fieldName.toLowerCase().startsWith(partial)) {
            candidates.add(noSpace(fieldName));
          }
        }
      }
    }
  }

  /** Extract the decorator event type from function parameters (first parameter). */
  private String extractDecoratorEventType(String params) {
    if (params == null || params.isEmpty()) {
      return null;
    }
    int commaIdx = params.indexOf(',');
    String firstParam = commaIdx > 0 ? params.substring(0, commaIdx) : params;
    return firstParam.trim();
  }

  /** Extract parameter name before the equals sign. */
  private String extractParameterName(String params, int equalsIndex) {
    // Find the start of the parameter name (after comma or at beginning)
    int start = params.lastIndexOf(',', equalsIndex);
    if (start < 0) {
      start = 0;
    } else {
      start++; // Skip the comma
    }

    String paramName = params.substring(start, equalsIndex).trim();
    return paramName.isEmpty() ? null : paramName;
  }

  /** Add candidate if it matches the partial input. */
  private void addIfMatches(
      String paramName, String partial, String description, List<Candidate> candidates) {
    if (paramName.toLowerCase().startsWith(partial)) {
      candidates.add(candidateNoSpace(paramName, paramName, description));
    }
  }
}

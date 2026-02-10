package io.jafar.shell.cli.completion.completers;

import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextType;
import io.jafar.shell.cli.completion.ContextCompleter;
import io.jafar.shell.cli.completion.MetadataService;
import java.util.List;
import org.jline.reader.Candidate;

/**
 * Completer for field names inside filter predicates [...]. Also suggests operators and function
 * templates when a field path is being typed.
 */
public final class FilterFieldCompleter implements ContextCompleter {

  private static final String[] OPERATORS = {"=", "!=", ">", ">=", "<", "<=", "~"};
  private static final String[] FILTER_FUNCTIONS = {
    "contains(", "exists(", "startsWith(", "endsWith("
  };

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.FILTER_FIELD;
  }

  @Override
  public void complete(
      CompletionContext ctx, MetadataService metadata, List<Candidate> candidates) {
    String fullLine = ctx.fullLine();
    int cursor = ctx.cursor();

    // Find the [ in the full line (not just current word)
    int bracketIdx = fullLine.lastIndexOf('[', cursor - 1);
    if (bracketIdx < 0) {
      // Not inside a filter - shouldn't happen but handle gracefully
      return;
    }

    // Check if we're after a logical operator (&& or ||) - in that case we complete standalone
    // field names
    String filterContent = ctx.extras() != null ? ctx.extras().get("filterContent") : null;
    boolean afterLogicalOp =
        filterContent != null && (filterContent.endsWith("&& ") || filterContent.endsWith("|| "));

    // Find the current word being typed
    String currentWord = extractCurrentWord(ctx);

    // If current word doesn't contain [, we might be after && or || inside a filter
    int wordBracketIdx = currentWord.lastIndexOf('[');
    if (wordBracketIdx < 0) {
      if (afterLogicalOp) {
        // After logical operator - suggest field names with proper prefix
        completeAfterLogicalOperator(ctx, metadata, candidates, fullLine, bracketIdx);
        return;
      }
      // Not inside a filter in current word and not after logical op
      return;
    }

    // Everything up to and including [ is the base prefix
    String basePrefix = currentWord.substring(0, wordBracketIdx + 1);
    // Content after [ is what's inside the filter
    filterContent = currentWord.substring(wordBracketIdx + 1);

    // Handle list prefixes (any:, all:, none:)
    String listPrefix = "";
    String fieldPath = filterContent;
    if (filterContent.startsWith("any:")) {
      listPrefix = "any:";
      fieldPath = filterContent.substring(4);
    } else if (filterContent.startsWith("all:")) {
      listPrefix = "all:";
      fieldPath = filterContent.substring(4);
    } else if (filterContent.startsWith("none:")) {
      listPrefix = "none:";
      fieldPath = filterContent.substring(5);
    }

    String fullPrefix = basePrefix + listPrefix;

    // Determine nested path and partial field name
    String nestedPath = "";
    String partial = fieldPath;
    int lastSlash = fieldPath.lastIndexOf('/');
    if (lastSlash >= 0) {
      nestedPath = fieldPath.substring(0, lastSlash + 1);
      partial = fieldPath.substring(lastSlash + 1);
    }

    // Get event type for field lookup
    String eventType = ctx.eventType();

    // Check if there's a nested path from the context (e.g., events/Type/field[)
    String contextNestedPath = ctx.extras() != null ? ctx.extras().get("nestedPath") : null;

    // If we have a nested path (either from inside filter or from context), get fields from the
    // nested type
    String lookupType = eventType;
    if (contextNestedPath != null && eventType != null) {
      // Nested path from context (e.g., events/jdk.ExecutionSample/stackTrace[)
      lookupType = resolveNestedType(metadata, eventType, contextNestedPath);
    } else if (!nestedPath.isEmpty() && eventType != null) {
      // Nested path from inside filter content
      lookupType = resolveNestedType(metadata, eventType, nestedPath);
    }

    // Suggest field names
    List<String> matchingFields = new java.util.ArrayList<>();
    if (lookupType != null && !lookupType.isEmpty()) {
      List<String> fieldNames = metadata.getFieldNames(lookupType);
      for (String fieldName : fieldNames) {
        if (fieldName.toLowerCase().startsWith(partial.toLowerCase())) {
          candidates.add(noSpace(fullPrefix + nestedPath + fieldName));
          matchingFields.add(fieldName);
        }
      }
    }

    // Suggest operators only if the partial text exactly matches a complete field name
    // (not just a prefix of multiple fields)
    boolean isCompleteFieldName =
        !partial.isEmpty() && matchingFields.size() == 1 && matchingFields.get(0).equals(partial);
    if (isCompleteFieldName) {
      for (String op : OPERATORS) {
        candidates.add(noSpace(currentWord + op));
      }
    }

    // Suggest filter functions
    for (String func : FILTER_FUNCTIONS) {
      if (func.toLowerCase().startsWith(partial.toLowerCase()) || partial.isEmpty()) {
        candidates.add(noSpace(fullPrefix + nestedPath + func));
      }
    }
  }

  /** Complete field names after a logical operator (&& or ||). */
  private void completeAfterLogicalOperator(
      CompletionContext ctx,
      MetadataService metadata,
      List<Candidate> candidates,
      String fullLine,
      int bracketIdx) {
    String eventType = ctx.eventType();

    // Check for nested path from context
    String contextNestedPath = ctx.extras() != null ? ctx.extras().get("nestedPath") : null;

    // Determine lookup type
    String lookupType = eventType;
    if (contextNestedPath != null && eventType != null) {
      lookupType = resolveNestedType(metadata, eventType, contextNestedPath);
    }

    // Get field names
    if (lookupType != null && !lookupType.isEmpty()) {
      List<String> fieldNames = metadata.getFieldNames(lookupType);
      for (String fieldName : fieldNames) {
        // Just suggest field name - JLine will add to current position
        candidates.add(new Candidate(fieldName, fieldName, null, null, null, null, true));
      }
    }

    // Suggest filter functions
    for (String func : FILTER_FUNCTIONS) {
      candidates.add(new Candidate(func, func, null, "filter function", null, null, false));
    }
  }

  private String resolveNestedType(MetadataService metadata, String eventType, String nestedPath) {
    // Navigate through the nested path to find the final type
    String currentType = eventType;
    String[] segments = nestedPath.split("/");
    for (String segment : segments) {
      if (segment.isEmpty()) continue;
      String fieldType = metadata.getFieldType(currentType, segment);
      if (fieldType == null) {
        return null;
      }
      currentType = fieldType;
    }
    return currentType;
  }

  private String extractCurrentWord(CompletionContext ctx) {
    String fullLine = ctx.fullLine();
    int cursor = ctx.cursor();

    // Find the start of the current word (last whitespace before cursor)
    int wordStart = 0;
    for (int i = cursor - 1; i >= 0; i--) {
      if (Character.isWhitespace(fullLine.charAt(i))) {
        wordStart = i + 1;
        break;
      }
    }

    return fullLine.substring(wordStart, cursor);
  }
}

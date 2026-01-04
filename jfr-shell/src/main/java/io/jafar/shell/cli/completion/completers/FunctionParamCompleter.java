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

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.FUNCTION_PARAM;
  }

  @Override
  public void complete(
      CompletionContext ctx, MetadataService metadata, List<Candidate> candidates) {
    String eventType = ctx.eventType();
    if (eventType == null || eventType.isEmpty()) {
      return;
    }

    String partial = ctx.partialInput();
    String functionName = ctx.functionName();

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

    // Build completion prefix from the path segments
    String prefix = "";
    if (!pathSegments.isEmpty()) {
      // Use the same separator that was used in the original input
      char separator = partial.contains("/") ? '/' : '.';
      prefix = String.join(String.valueOf(separator), pathSegments) + separator;
    }

    String lowerLastSegment = lastSegment.toLowerCase();
    for (String fieldName : fieldNames) {
      if (fieldName.toLowerCase().startsWith(lowerLastSegment)) {
        // Use noSpace to not add trailing space after field name
        // This allows typing comma for multiple fields in select()
        String value = prefix + fieldName;
        candidates.add(noSpace(value));
      }
    }
  }
}

package io.jafar.shell.cli.completion.completers;

import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextType;
import io.jafar.shell.cli.completion.ContextCompleter;
import io.jafar.shell.cli.completion.MetadataService;
import java.util.List;
import org.jline.reader.Candidate;

/**
 * Completer for filter function arguments like contains(field, "value").
 *
 * <p>Filter functions have specific parameter patterns:
 *
 * <ul>
 *   <li>contains(field, value) - first param is field, second is string
 *   <li>starts_with(field, prefix) - first param is field
 *   <li>ends_with(field, suffix) - first param is field
 *   <li>matches(field, regex) - first param is field
 *   <li>between(field, low, high) - first param is field
 *   <li>len(field) - single field param
 *   <li>exists(field) - single field param
 *   <li>empty(field) - single field param
 * </ul>
 *
 * <p>For the first parameter (index 0), this completer suggests field names from the event type.
 * Subsequent parameters are typically literal values and don't need completion.
 */
public class FilterFunctionArgCompleter implements ContextCompleter {

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.FILTER_FUNCTION_ARG;
  }

  @Override
  public void complete(
      CompletionContext ctx, MetadataService metadata, List<Candidate> candidates) {
    String eventType = ctx.eventType();
    String partial = ctx.partialInput();
    int paramIndex = ctx.parameterIndex();

    // Only complete field names for the first parameter (index 0)
    // Other parameters are typically literal values (strings, numbers)
    if (paramIndex != 0) {
      return;
    }

    if (eventType == null || eventType.isEmpty()) {
      return;
    }

    String lowerPartial = partial.toLowerCase();
    List<String> fieldNames = metadata.getFieldNames(eventType);

    for (String fieldName : fieldNames) {
      if (fieldName.toLowerCase().startsWith(lowerPartial)) {
        candidates.add(noSpace(fieldName));
      }
    }
  }
}

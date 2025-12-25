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

    String partial = ctx.partialInput().toLowerCase();
    String functionName = ctx.functionName();

    // Get field names for this event type
    List<String> fieldNames = metadata.getFieldNames(eventType);

    for (String fieldName : fieldNames) {
      if (fieldName.toLowerCase().startsWith(partial)) {
        // Use noSpace to not add trailing space after field name
        // This allows typing comma for multiple fields in select()
        candidates.add(noSpace(fieldName));
      }
    }
  }
}

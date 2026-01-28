package io.jafar.shell.cli.completion.completers;

import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextType;
import io.jafar.shell.cli.completion.ContextCompleter;
import io.jafar.shell.cli.completion.MetadataService;
import java.util.List;
import org.jline.reader.Candidate;

/**
 * Completer for decorator field access: $decorator.fieldName.
 *
 * <p>In decorator pipelines (decorateByTime, decorateByKey), the $decorator prefix provides access
 * to fields from the decorator event type. For example:
 *
 * <pre>
 * show events/jdk.MonitorEnter | decorateByTime(jdk.ExecutionSample) | select($decorator.stackTrace)
 * </pre>
 *
 * <p>This completer suggests field names from the decorator event type (jdk.ExecutionSample in the
 * example above).
 */
public class DecoratorFieldCompleter implements ContextCompleter {

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.DECORATOR_FIELD;
  }

  @Override
  public void complete(
      CompletionContext ctx, MetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput();
    String decoratorPrefix = ctx.extras().getOrDefault("decoratorPrefix", "$decorator.");
    String decoratorEventType = ctx.extras().get("decoratorEventType");

    if (decoratorEventType == null || decoratorEventType.isEmpty()) {
      // Can't complete without knowing the decorator event type
      return;
    }

    String lowerPartial = partial.toLowerCase();
    List<String> fieldNames = metadata.getFieldNames(decoratorEventType);

    for (String fieldName : fieldNames) {
      if (fieldName.toLowerCase().startsWith(lowerPartial)) {
        // Include the $decorator. prefix in the candidate value
        candidates.add(noSpace(decoratorPrefix + fieldName));
      }
    }
  }
}

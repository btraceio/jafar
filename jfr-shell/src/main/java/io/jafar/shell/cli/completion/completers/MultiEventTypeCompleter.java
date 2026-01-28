package io.jafar.shell.cli.completion.completers;

import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextType;
import io.jafar.shell.cli.completion.ContextCompleter;
import io.jafar.shell.cli.completion.MetadataService;
import java.util.List;
import java.util.Set;
import org.jline.reader.Candidate;

/**
 * Completer for multi-event type syntax: events/(Type1|Type2|Type3).
 *
 * <p>Suggests event type names when inside the parentheses of multi-event selection. The | pipe
 * character is used to separate multiple event types.
 */
public class MultiEventTypeCompleter implements ContextCompleter {

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.MULTI_EVENT_TYPE;
  }

  @Override
  public void complete(
      CompletionContext ctx, MetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput();
    String lowerPartial = partial.toLowerCase();

    Set<String> eventTypes = metadata.getEventTypes();
    for (String type : eventTypes) {
      if (type.toLowerCase().startsWith(lowerPartial)) {
        // Don't add trailing space - user may want to add | for more types or ) to close
        candidates.add(noSpace(type));
      }
    }
  }
}

package io.jafar.shell.cli.completion.completers;

import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextType;
import io.jafar.shell.cli.completion.ContextCompleter;
import io.jafar.shell.cli.completion.MetadataService;
import java.util.List;
import org.jline.reader.Candidate;

/**
 * Completer for field paths within event types. Handles nested field navigation (e.g.,
 * events/jdk.ExecutionSample/sampledThread/name).
 */
public class FieldPathCompleter implements ContextCompleter {

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.FIELD_PATH;
  }

  @Override
  public void complete(
      CompletionContext ctx, MetadataService metadata, List<Candidate> candidates) {
    String eventType = ctx.eventType();
    if (eventType == null || eventType.isEmpty()) {
      return;
    }

    String partial = ctx.partialInput();
    List<String> path = ctx.fieldPath();

    // Special handling for metadata root type
    if ("metadata".equals(ctx.rootType()) && path.isEmpty()) {
      // After "metadata/Type/", suggest "fields" as a special segment
      String prefix = buildPrefix(ctx);
      if ("fields".startsWith(partial)) {
        String value = prefix + "fields";
        candidates.add(new Candidate(value, "fields", null, null, null, null, true));
      }
      return;
    }

    // Get field names at the current path level
    List<String> fieldNames = metadata.getNestedFieldNames(eventType, path);

    // Build prefix for the completion value
    String prefix = buildPrefix(ctx);

    for (String fieldName : fieldNames) {
      if (fieldName.startsWith(partial)) {
        String value = prefix + fieldName;
        candidates.add(new Candidate(value, fieldName, null, null, null, null, true));
      }
    }
  }

  private String buildPrefix(CompletionContext ctx) {
    StringBuilder prefix = new StringBuilder();
    prefix.append(ctx.rootType()).append("/");
    prefix.append(ctx.eventType()).append("/");
    for (String segment : ctx.fieldPath()) {
      prefix.append(segment).append("/");
    }
    return prefix.toString();
  }
}

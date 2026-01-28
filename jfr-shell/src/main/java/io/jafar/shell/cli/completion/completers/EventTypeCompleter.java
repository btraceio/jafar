package io.jafar.shell.cli.completion.completers;

import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextType;
import io.jafar.shell.cli.completion.ContextCompleter;
import io.jafar.shell.cli.completion.MetadataService;
import java.util.List;
import java.util.Set;
import org.jline.reader.Candidate;

/** Completer for event type names after events/, metadata/, or cp/. */
public final class EventTypeCompleter implements ContextCompleter {

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.EVENT_TYPE;
  }

  @Override
  public void complete(
      CompletionContext ctx, MetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput();
    String rootType = ctx.rootType();

    Set<String> types = getTypesForRoot(metadata, rootType);
    for (String type : types) {
      if (type.startsWith(partial)) {
        // Build full path with root
        String value = rootType + "/" + type;
        // Don't use group - let JLine display all candidates without grouping
        // Use complete=false to prevent unwanted auto-completion
        candidates.add(new Candidate(value, type, null, null, null, null, false));
      }
    }
  }

  private Set<String> getTypesForRoot(MetadataService metadata, String rootType) {
    if (rootType == null) {
      return metadata.getEventTypes();
    }
    return switch (rootType) {
      case "events" -> metadata.getEventTypes();
      case "metadata" -> metadata.getAllMetadataTypes();
      case "cp" -> metadata.getConstantPoolTypes();
      default -> metadata.getEventTypes();
    };
  }
}

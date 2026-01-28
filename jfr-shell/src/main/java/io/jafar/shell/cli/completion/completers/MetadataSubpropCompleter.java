package io.jafar.shell.cli.completion.completers;

import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextType;
import io.jafar.shell.cli.completion.ContextCompleter;
import io.jafar.shell.cli.completion.MetadataService;
import java.util.List;
import org.jline.reader.Candidate;

/**
 * Completer for metadata type subproperties after 'show metadata/Type/'. Suggests: fields,
 * settings, annotations
 */
public final class MetadataSubpropCompleter implements ContextCompleter {

  private static final String[] SUBPROPS = {"fields", "settings", "annotations"};

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.METADATA_SUBPROP;
  }

  @Override
  public void complete(
      CompletionContext ctx, MetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput();
    String eventType = ctx.eventType();
    String prefix = "metadata/" + eventType + "/";

    for (String subprop : SUBPROPS) {
      if (subprop.toLowerCase().startsWith(partial.toLowerCase())) {
        candidates.add(new Candidate(prefix + subprop, subprop, null, null, null, null, true));
      }
    }
  }
}

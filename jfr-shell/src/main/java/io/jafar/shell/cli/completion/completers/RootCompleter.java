package io.jafar.shell.cli.completion.completers;

import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextType;
import io.jafar.shell.cli.completion.ContextCompleter;
import io.jafar.shell.cli.completion.MetadataService;
import java.util.List;
import org.jline.reader.Candidate;

/**
 * Completer for root path types after 'show ' command. Suggests: events/, metadata/, cp/, chunks
 */
public final class RootCompleter implements ContextCompleter {

  private static final String[] ROOTS = {"events/", "metadata/", "cp/", "chunks/"};

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.ROOT;
  }

  @Override
  public void complete(
      CompletionContext ctx, MetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput();
    for (String root : ROOTS) {
      if (root.startsWith(partial)) {
        // Use noSpace for roots ending with / to allow immediate type completion
        if (root.endsWith("/")) {
          candidates.add(noSpace(root));
        } else {
          candidates.add(new Candidate(root));
        }
      }
    }
  }
}

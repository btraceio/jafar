package io.jafar.hdump.shell.cli.completion.completers;

import io.jafar.hdump.shell.cli.completion.HdumpMetadataService;
import io.jafar.shell.core.completion.CompletionContext;
import io.jafar.shell.core.completion.CompletionContextType;
import io.jafar.shell.core.completion.ContextCompleter;
import java.util.List;
import org.jline.reader.Candidate;

/**
 * Completer for root path types after 'show ' command. Suggests: objects/, classes/, gcroots/
 */
public final class HdumpRootCompleter implements ContextCompleter<HdumpMetadataService> {

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.ROOT;
  }

  @Override
  public void complete(
      CompletionContext ctx, HdumpMetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput();
    for (String root : metadata.getRootTypes()) {
      String rootWithSlash = root + "/";
      if (rootWithSlash.startsWith(partial)) {
        // Use noSpace for roots ending with / to allow immediate type completion
        candidates.add(noSpace(rootWithSlash));
      }
    }
  }
}

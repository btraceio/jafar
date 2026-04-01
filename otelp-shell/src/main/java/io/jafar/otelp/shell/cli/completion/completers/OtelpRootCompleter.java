package io.jafar.otelp.shell.cli.completion.completers;

import io.jafar.otelp.shell.cli.completion.OtelpMetadataService;
import io.jafar.shell.core.completion.CompletionContext;
import io.jafar.shell.core.completion.CompletionContextType;
import io.jafar.shell.core.completion.ContextCompleter;
import java.util.List;
import org.jline.reader.Candidate;

/** Completer for the otelp query root ({@code samples}) after a {@code show} command. */
public final class OtelpRootCompleter implements ContextCompleter<OtelpMetadataService> {

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.ROOT;
  }

  @Override
  public void complete(
      CompletionContext ctx, OtelpMetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput();
    for (String root : metadata.getRootTypes()) {
      if (root.startsWith(partial)) {
        candidates.add(candidate(root, root, "query all samples"));
      }
    }
  }
}

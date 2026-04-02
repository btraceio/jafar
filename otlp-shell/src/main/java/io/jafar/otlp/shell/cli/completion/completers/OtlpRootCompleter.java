package io.jafar.otlp.shell.cli.completion.completers;

import io.jafar.otlp.shell.cli.completion.OtlpMetadataService;
import io.jafar.shell.core.completion.CompletionContext;
import io.jafar.shell.core.completion.CompletionContextType;
import io.jafar.shell.core.completion.ContextCompleter;
import java.util.List;
import org.jline.reader.Candidate;

/** Completer for the otlp query root ({@code samples}) after a {@code show} command. */
public final class OtlpRootCompleter implements ContextCompleter<OtlpMetadataService> {

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.ROOT;
  }

  @Override
  public void complete(
      CompletionContext ctx, OtlpMetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput();
    for (String root : metadata.getRootTypes()) {
      if (root.startsWith(partial)) {
        candidates.add(candidate(root, root, "query all samples"));
      }
    }
  }
}

package io.jafar.pprof.shell.cli.completion.completers;

import io.jafar.pprof.shell.cli.completion.PprofMetadataService;
import io.jafar.shell.core.completion.CompletionContext;
import io.jafar.shell.core.completion.CompletionContextType;
import io.jafar.shell.core.completion.ContextCompleter;
import java.util.List;
import org.jline.reader.Candidate;

/** Completer for the pprof query root ({@code samples}) after a {@code show} command. */
public final class PprofRootCompleter implements ContextCompleter<PprofMetadataService> {

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.ROOT;
  }

  @Override
  public void complete(
      CompletionContext ctx, PprofMetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput();
    for (String root : metadata.getRootTypes()) {
      if (root.startsWith(partial)) {
        candidates.add(candidate(root, root, "query all samples"));
      }
    }
  }
}

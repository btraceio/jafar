package io.jafar.pprof.shell.cli.completion.completers;

import io.jafar.pprof.shell.cli.completion.PprofMetadataService;
import io.jafar.shell.core.completion.CompletionContext;
import io.jafar.shell.core.completion.CompletionContextType;
import io.jafar.shell.core.completion.ContextCompleter;
import java.util.List;
import org.jline.reader.Candidate;

/**
 * Completer for logical operators ({@code and}/{@code or} and their symbolic aliases) inside pprof
 * filter predicates.
 */
public final class PprofFilterLogicalCompleter implements ContextCompleter<PprofMetadataService> {

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.FILTER_LOGICAL;
  }

  @Override
  public void complete(
      CompletionContext ctx, PprofMetadataService metadata, List<Candidate> candidates) {
    String prefix = calculateJlinePrefix(ctx.jlineWord(), ctx.partialInput());
    candidates.add(candidate(prefix + "and", "and", "logical AND"));
    candidates.add(candidate(prefix + "or", "or", "logical OR"));
    candidates.add(candidate(prefix + "&&", "&&", "logical AND"));
    candidates.add(candidate(prefix + "||", "||", "logical OR"));
  }
}

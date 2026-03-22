package io.jafar.shell.cli.completion.completers;

import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextType;
import io.jafar.shell.cli.completion.ContextCompleter;
import io.jafar.shell.cli.completion.MetadataService;
import java.util.List;
import org.jline.reader.Candidate;

/** Completer for logical operators (&&, ||) inside filter predicates. */
public final class FilterLogicalCompleter implements ContextCompleter {

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.FILTER_LOGICAL;
  }

  @Override
  public void complete(
      CompletionContext ctx, MetadataService metadata, List<Candidate> candidates) {
    String prefix = calculateJlinePrefix(ctx.jlineWord(), ctx.partialInput());
    candidates.add(candidate(prefix + "&&", "&&", "logical AND"));
    candidates.add(candidate(prefix + "||", "||", "logical OR"));
  }
}

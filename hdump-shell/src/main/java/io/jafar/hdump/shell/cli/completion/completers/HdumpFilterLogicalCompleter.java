package io.jafar.hdump.shell.cli.completion.completers;

import io.jafar.hdump.shell.cli.completion.HdumpMetadataService;
import io.jafar.shell.core.completion.CompletionContext;
import io.jafar.shell.core.completion.CompletionContextType;
import io.jafar.shell.core.completion.ContextCompleter;
import java.util.List;
import org.jline.reader.Candidate;

/** Completer for logical operators (&&, ||) inside filter predicates. */
public final class HdumpFilterLogicalCompleter implements ContextCompleter<HdumpMetadataService> {

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.FILTER_LOGICAL;
  }

  @Override
  public void complete(
      CompletionContext ctx, HdumpMetadataService metadata, List<Candidate> candidates) {
    String prefix = calculateJlinePrefix(ctx.jlineWord(), ctx.partialInput());
    candidates.add(candidate(prefix + "&&", "&&", "logical AND"));
    candidates.add(candidate(prefix + "||", "||", "logical OR"));
  }
}

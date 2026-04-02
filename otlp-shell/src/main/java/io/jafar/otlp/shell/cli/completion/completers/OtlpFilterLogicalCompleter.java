package io.jafar.otlp.shell.cli.completion.completers;

import io.jafar.otlp.shell.cli.completion.OtlpMetadataService;
import io.jafar.shell.core.completion.CompletionContext;
import io.jafar.shell.core.completion.CompletionContextType;
import io.jafar.shell.core.completion.ContextCompleter;
import java.util.List;
import org.jline.reader.Candidate;

/**
 * Completer for logical operators ({@code and}/{@code or} and their symbolic aliases) inside otlp
 * filter predicates.
 */
public final class OtlpFilterLogicalCompleter implements ContextCompleter<OtlpMetadataService> {

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.FILTER_LOGICAL;
  }

  @Override
  public void complete(
      CompletionContext ctx, OtlpMetadataService metadata, List<Candidate> candidates) {
    String prefix = calculateJlinePrefix(ctx.jlineWord(), ctx.partialInput());
    candidates.add(candidate(prefix + "and", "and", "logical AND"));
    candidates.add(candidate(prefix + "or", "or", "logical OR"));
    candidates.add(candidate(prefix + "&&", "&&", "logical AND"));
    candidates.add(candidate(prefix + "||", "||", "logical OR"));
  }
}

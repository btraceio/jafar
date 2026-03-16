package io.jafar.hdump.shell.cli.completion.completers;

import io.jafar.hdump.shell.cli.completion.HdumpMetadataService;
import io.jafar.shell.core.completion.CompletionContext;
import io.jafar.shell.core.completion.CompletionContextType;
import io.jafar.shell.core.completion.ContextCompleter;
import java.util.List;
import java.util.Set;
import org.jline.reader.Candidate;

/** Completer for variable references after ${ in expressions. */
public final class HdumpVariableReferenceCompleter
    implements ContextCompleter<HdumpMetadataService> {

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.VARIABLE_REFERENCE;
  }

  @Override
  public void complete(
      CompletionContext ctx, HdumpMetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput();

    Set<String> varNames = metadata.getVariableNames();
    for (String name : varNames) {
      if (name.startsWith(partial)) {
        candidates.add(candidate(name, name, "variable"));
      }
    }
  }
}

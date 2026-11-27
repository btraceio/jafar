package io.jafar.shell.cli.completion.completers;

import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextType;
import io.jafar.shell.cli.completion.ContextCompleter;
import io.jafar.shell.cli.completion.MetadataService;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jline.reader.Candidate;

/**
 * Completer for variable references: ${varName}.
 *
 * <p>Suggests variable names from the current session's variable store when the user is typing
 * inside a variable reference pattern like ${var...}.
 */
public final class VariableReferenceCompleter implements ContextCompleter {

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.VARIABLE_REFERENCE;
  }

  @Override
  public void complete(
      CompletionContext ctx, MetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput();
    String lowerPartial = partial.toLowerCase(Locale.ROOT);
    String prefix = calculateJlinePrefix(ctx.jlineWord(), "${" + partial);

    Set<String> variableNames = metadata.getSessionVariableNames();

    for (String varName : variableNames) {
      if (varName.toLowerCase(Locale.ROOT).startsWith(lowerPartial)) {
        // Include ${ prefix and closing } in the candidate
        String value = prefix + "${" + varName + "}";
        candidates.add(noSpace(value));
      }
    }
  }
}

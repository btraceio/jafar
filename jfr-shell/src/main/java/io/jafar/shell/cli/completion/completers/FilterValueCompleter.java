package io.jafar.shell.cli.completion.completers;

import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextType;
import io.jafar.shell.cli.completion.ContextCompleter;
import io.jafar.shell.cli.completion.MetadataService;
import java.util.List;
import org.jline.reader.Candidate;

/**
 * Completer for filter values after an operator. Suggests common value templates and the closing
 * bracket.
 */
public class FilterValueCompleter implements ContextCompleter {

  // Common value templates
  private static final String[][] VALUE_TEMPLATES = {
    {"0", "0", "numeric zero"},
    {"true", "true", "boolean true"},
    {"false", "false", "boolean false"},
    {"null", "null", "null value"},
    {"\"\"", "\"...\"", "string literal"},
  };

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.FILTER_VALUE;
  }

  @Override
  public void complete(
      CompletionContext ctx, MetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput().toLowerCase();

    // Suggest value templates
    for (String[] template : VALUE_TEMPLATES) {
      String value = template[0];
      String display = template[1];
      String description = template[2];

      if (value.toLowerCase().startsWith(partial) || partial.isEmpty()) {
        candidates.add(candidate(value, display, description));
      }
    }

    // Always suggest closing the filter with ]
    candidates.add(candidateNoSpace("]", "]", "close filter"));

    // Suggest logical operators to continue the filter
    if (partial.isEmpty()) {
      candidates.add(candidateNoSpace("&& ", "&& ...", "and condition"));
      candidates.add(candidateNoSpace("|| ", "|| ...", "or condition"));
    }
  }
}

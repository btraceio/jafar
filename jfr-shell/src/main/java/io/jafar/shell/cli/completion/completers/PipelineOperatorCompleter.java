package io.jafar.shell.cli.completion.completers;

import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextType;
import io.jafar.shell.cli.completion.ContextCompleter;
import io.jafar.shell.cli.completion.MetadataService;
import java.util.List;
import org.jline.reader.Candidate;

/**
 * Completer for pipeline operators after |. Suggests aggregation functions, transforms, and
 * decorators.
 */
public class PipelineOperatorCompleter implements ContextCompleter {

  // Pipeline operators with their templates
  private static final String[][] OPERATORS = {
    // {value, display, description}
    {"count()", "count()", "Count events"},
    {"sum(", "sum(field)", "Sum field values"},
    {"groupBy(", "groupBy(field)", "Group by field"},
    {"top(", "top(N, field)", "Top N by field"},
    {"stats()", "stats()", "Statistics"},
    {"quantiles(0.5,0.9,0.99)", "quantiles(...)", "Percentiles"},
    {"sketch()", "sketch()", "Approximate statistics"},
    {"select(", "select(field, ...)", "Select fields"},
    {"toMap(", "toMap(keyField, valueField)", "Convert to map"},
    // Transform functions
    {"len()", "len()", "String length"},
    {"uppercase()", "uppercase()", "Uppercase string"},
    {"lowercase()", "lowercase()", "Lowercase string"},
    {"trim()", "trim()", "Trim whitespace"},
    {"abs()", "abs()", "Absolute value"},
    {"round()", "round()", "Round number"},
    {"floor()", "floor()", "Floor number"},
    {"ceil()", "ceil()", "Ceiling number"},
    {"contains(\"\")", "contains(str)", "String contains"},
    {"replace(\"\",\"\")", "replace(old,new)", "String replace"},
    // Decorators
    {"decorateByTime(eventType, fields=)", "decorateByTime(...)", "Join by time overlap"},
    {"decorateByKey(eventType, key=, decoratorKey=, fields=)", "decorateByKey(...)", "Join by key"}
  };

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.PIPELINE_OPERATOR;
  }

  @Override
  public void complete(
      CompletionContext ctx, MetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput().toLowerCase();

    for (String[] op : OPERATORS) {
      String value = op[0];
      String display = op[1];
      String description = op[2];

      // Match against both value and display text
      if (value.toLowerCase().startsWith(partial) || display.toLowerCase().startsWith(partial)) {
        // Use noSpace for templates ending with ( to allow immediate parameter entry
        if (value.endsWith("(")) {
          candidates.add(candidateNoSpace(value, display, description));
        } else {
          candidates.add(candidate(value, display, description));
        }
      }
    }
  }
}

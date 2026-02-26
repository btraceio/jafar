package io.jafar.shell.cli.completion.completers;

import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextType;
import io.jafar.shell.cli.completion.ContextCompleter;
import io.jafar.shell.cli.completion.FunctionRegistry;
import io.jafar.shell.cli.completion.FunctionSpec;
import io.jafar.shell.cli.completion.MetadataService;
import io.jafar.shell.cli.completion.ParamSpec;
import java.util.List;
import org.jline.reader.Candidate;

/**
 * Completer for filter function arguments like contains(field, "value").
 *
 * <p>Filter functions have specific parameter patterns:
 *
 * <ul>
 *   <li>contains(field, value) - first param is field, second is string
 *   <li>starts_with(field, prefix) - first param is field
 *   <li>ends_with(field, suffix) - first param is field
 *   <li>matches(field, regex) - first param is field
 *   <li>between(field, low, high) - first param is field
 *   <li>len(field) - single field param
 *   <li>exists(field) - single field param
 *   <li>empty(field) - single field param
 * </ul>
 *
 * <p>For the first parameter (index 0), this completer suggests field names from the event type.
 * Subsequent parameters are typically literal values and don't need completion.
 */
public final class FilterFunctionArgCompleter implements ContextCompleter {

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.FILTER_FUNCTION_ARG;
  }

  @Override
  public void complete(
      CompletionContext ctx, MetadataService metadata, List<Candidate> candidates) {
    String eventType = ctx.eventType();
    String partial = ctx.partialInput();
    int paramIndex = ctx.parameterIndex();

    if (eventType == null || eventType.isEmpty()) {
      return;
    }

    // Compute the jline prefix once — used for all param completions
    String jlineWord = ctx.jlineWord();
    String jlineWordPrefix = "";
    if (jlineWord != null && !jlineWord.isEmpty()) {
      if (partial.isEmpty()) {
        jlineWordPrefix = jlineWord;
      } else if (jlineWord.length() > partial.length()) {
        jlineWordPrefix = jlineWord.substring(0, jlineWord.length() - partial.length());
      }
    }

    // First parameter: field names from the event type
    if (paramIndex == 0) {
      String lowerPartial = partial.toLowerCase();
      for (String fieldName : metadata.getFieldNames(eventType)) {
        if (fieldName.toLowerCase().startsWith(lowerPartial)) {
          candidates.add(noSpace(jlineWordPrefix + fieldName));
        }
      }
      return;
    }

    // Subsequent parameters: look up the FunctionSpec and suggest based on param type
    FunctionSpec spec = FunctionRegistry.getFilterFunction(ctx.functionName());
    if (spec == null) {
      return;
    }
    ParamSpec param = spec.getPositionalParam(paramIndex);
    if (param == null) {
      return;
    }

    switch (param.type()) {
      case ENUM -> {
        for (String val : param.enumValues()) {
          if (val.toLowerCase().startsWith(partial.toLowerCase())) {
            candidates.add(noSpace(jlineWordPrefix + val));
          }
        }
      }
      case STRING -> {
        // Suggest an empty quoted string as a template with the param description as hint
        if (partial.isEmpty() || partial.equals("\"")) {
          candidates.add(
              new Candidate(
                  jlineWordPrefix + "\"\"", "\"\"", null, param.description(), null, null, true));
        }
      }
      default -> {} // NUMBER, FIELD_PATH, etc. — no suggestion
    }
  }
}

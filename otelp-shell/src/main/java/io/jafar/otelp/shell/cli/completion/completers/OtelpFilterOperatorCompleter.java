package io.jafar.otelp.shell.cli.completion.completers;

import io.jafar.otelp.shell.cli.completion.OtelpMetadataService;
import io.jafar.shell.core.completion.CompletionContext;
import io.jafar.shell.core.completion.CompletionContextType;
import io.jafar.shell.core.completion.ContextCompleter;
import java.util.List;
import org.jline.reader.Candidate;

/** Completer for comparison operators inside otelp filter predicates. */
public final class OtelpFilterOperatorCompleter implements ContextCompleter<OtelpMetadataService> {

  private static final String[] OPERATORS = {"=", "==", "!=", ">", ">=", "<", "<=", "~"};

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.FILTER_OPERATOR;
  }

  @Override
  public void complete(
      CompletionContext ctx, OtelpMetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput();
    String prefix = calculateJlinePrefix(ctx.jlineWord(), partial);

    for (String op : OPERATORS) {
      if (op.startsWith(partial)) {
        candidates.add(candidate(prefix + op, op, getDescription(op)));
      }
    }
  }

  private String getDescription(String op) {
    return switch (op) {
      case "=", "==" -> "equals";
      case "!=" -> "not equals";
      case ">" -> "greater than";
      case ">=" -> "greater or equal";
      case "<" -> "less than";
      case "<=" -> "less or equal";
      case "~" -> "regex match";
      default -> null;
    };
  }
}

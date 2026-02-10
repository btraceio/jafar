package io.jafar.hdump.shell.cli.completion.completers;

import io.jafar.hdump.shell.cli.completion.HdumpMetadataService;
import io.jafar.shell.core.completion.CompletionContext;
import io.jafar.shell.core.completion.CompletionContextType;
import io.jafar.shell.core.completion.ContextCompleter;
import java.util.List;
import org.jline.reader.Candidate;

/**
 * Completer for comparison operators inside filter predicates. Suggests operators like =, !=, <, >,
 * etc.
 */
public final class HdumpFilterOperatorCompleter implements ContextCompleter<HdumpMetadataService> {

  private static final String[] OPERATORS = {"=", "==", "!=", ">", ">=", "<", "<=", "~"};

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.FILTER_OPERATOR;
  }

  @Override
  public void complete(
      CompletionContext ctx, HdumpMetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput();

    for (String op : OPERATORS) {
      if (op.startsWith(partial)) {
        String description = getOperatorDescription(op);
        candidates.add(candidate(op, op, description));
      }
    }
  }

  private String getOperatorDescription(String op) {
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

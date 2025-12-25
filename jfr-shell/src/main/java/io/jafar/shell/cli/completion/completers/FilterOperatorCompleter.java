package io.jafar.shell.cli.completion.completers;

import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextType;
import io.jafar.shell.cli.completion.ContextCompleter;
import io.jafar.shell.cli.completion.MetadataService;
import java.util.List;
import org.jline.reader.Candidate;

/**
 * Completer for comparison operators inside filter predicates. Builds full path + operator
 * completions to match JLine's word replacement behavior.
 */
public class FilterOperatorCompleter implements ContextCompleter {

  private static final String[] SHORT_OPERATORS = {"=", "!=", ">", ">=", "<", "<=", "~"};
  private static final String[] WORD_OPERATORS = {
    "==", "!=", ">", ">=", "<", "<=", "~", "contains", "startsWith", "endsWith", "matches"
  };

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.FILTER_OPERATOR;
  }

  @Override
  public void complete(
      CompletionContext ctx, MetadataService metadata, List<Candidate> candidates) {
    // Get the prefix (current word up to and including field name)
    String prefix = extractPrefix(ctx);

    // If prefix is empty (cursor is after space), just suggest operators
    boolean usePrefix = !prefix.isEmpty() && !prefix.endsWith(" ");

    // Add operators
    for (String op : WORD_OPERATORS) {
      String value = usePrefix ? prefix + op : op;
      String description = getOperatorDescription(op);
      candidates.add(candidate(value, op, description));
    }
  }

  private String extractPrefix(CompletionContext ctx) {
    String fullLine = ctx.fullLine();
    int cursor = ctx.cursor();

    // Find the start of the current word (last whitespace before cursor)
    int wordStart = 0;
    for (int i = cursor - 1; i >= 0; i--) {
      if (Character.isWhitespace(fullLine.charAt(i))) {
        wordStart = i + 1;
        break;
      }
    }

    // The prefix is from word start to cursor
    return fullLine.substring(wordStart, cursor);
  }

  private boolean isShortOperator(String op) {
    for (String s : SHORT_OPERATORS) {
      if (s.equals(op)) return true;
    }
    return false;
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
      case "contains" -> "string contains";
      case "startsWith" -> "string starts with";
      case "endsWith" -> "string ends with";
      case "matches" -> "regex matches";
      default -> null;
    };
  }
}

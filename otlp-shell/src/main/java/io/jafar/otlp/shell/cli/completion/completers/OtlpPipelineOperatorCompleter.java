package io.jafar.otlp.shell.cli.completion.completers;

import io.jafar.otlp.shell.cli.completion.OtlpMetadataService;
import io.jafar.shell.core.completion.CompletionContext;
import io.jafar.shell.core.completion.CompletionContextType;
import io.jafar.shell.core.completion.ContextCompleter;
import java.util.List;
import java.util.Set;
import org.jline.reader.Candidate;

/** Completer for pipeline operators after {@code |} in otlp queries. */
public final class OtlpPipelineOperatorCompleter implements ContextCompleter<OtlpMetadataService> {

  /** Operators that take no parameters. */
  private static final Set<String> NO_PARAM_OPS = Set.of("count", "stackprofile");

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.PIPELINE_OPERATOR;
  }

  @Override
  public void complete(
      CompletionContext ctx, OtlpMetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput().toLowerCase();
    String prefix = calculateJlinePrefix(ctx.jlineWord(), partial);

    for (String op : metadata.getOperators()) {
      if (op.toLowerCase().startsWith(partial)) {
        boolean noParam = NO_PARAM_OPS.contains(op);
        String value = noParam ? op + "()" : op + "(";
        candidates.add(candidateNoSpace(prefix + value, getTemplate(op), getDescription(op)));
      }
    }
  }

  private String getTemplate(String op) {
    return switch (op) {
      case "count" -> "count()";
      case "top" -> "top(n, field, [asc|desc])";
      case "groupBy" -> "groupBy(field, [sum(field)])";
      case "stats" -> "stats(field)";
      case "head" -> "head(n)";
      case "tail" -> "tail(n)";
      case "filter" -> "filter(predicate)";
      case "select" -> "select(field1, field2, ...)";
      case "sortBy" -> "sortBy(field [asc|desc])";
      case "stackprofile" -> "stackprofile([valueField])";
      case "distinct" -> "distinct(field)";
      default -> op + "(...)";
    };
  }

  private String getDescription(String op) {
    return switch (op) {
      case "count" -> "count total samples";
      case "top" -> "get top N results sorted by field";
      case "groupBy" -> "group and aggregate";
      case "stats" -> "get statistics (min, max, avg, sum, count)";
      case "head" -> "take first N results";
      case "tail" -> "take last N results";
      case "filter" -> "filter results by condition";
      case "select" -> "project specific fields";
      case "sortBy" -> "sort results by field";
      case "stackprofile" -> "aggregate into folded stack format";
      case "distinct" -> "get distinct values";
      default -> null;
    };
  }
}

package io.jafar.hdump.shell.cli.completion.completers;

import io.jafar.hdump.shell.cli.completion.HdumpMetadataService;
import io.jafar.shell.core.completion.CompletionContext;
import io.jafar.shell.core.completion.CompletionContextType;
import io.jafar.shell.core.completion.ContextCompleter;
import java.util.List;
import org.jline.reader.Candidate;

/**
 * Completer for pipeline operators after |. Suggests aggregation functions and transforms like
 * select, top, groupBy, etc.
 */
public final class HdumpPipelineOperatorCompleter
    implements ContextCompleter<HdumpMetadataService> {

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.PIPELINE_OPERATOR;
  }

  @Override
  public void complete(
      CompletionContext ctx, HdumpMetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput().toLowerCase();

    for (String op : metadata.getOperators()) {
      if (op.toLowerCase().startsWith(partial)) {
        String template = getOperatorTemplate(op);
        String description = getOperatorDescription(op);
        // For operators with parameters, complete with "name(" to allow field completion
        // For no-arg operators like count, complete with "name()"
        boolean hasParams = hasParameters(op);
        String value = hasParams ? op + "(" : op + "()";
        candidates.add(candidateNoSpace(value, template, description));
      }
    }
  }

  private String getOperatorTemplate(String op) {
    return switch (op) {
      case "select" -> "select(field1, field2 as alias, ...)";
      case "top" -> "top(n, field, [asc|desc])";
      case "groupBy" -> "groupBy(field, agg)";
      case "count" -> "count()";
      case "sum" -> "sum(field)";
      case "stats" -> "stats(field)";
      case "sortBy" -> "sortBy(field [asc|desc], ...)";
      case "head" -> "head(n)";
      case "tail" -> "tail(n)";
      case "filter" -> "filter(predicate)";
      case "distinct" -> "distinct(field)";
      case "pathToRoot" -> "pathToRoot()";
      case "checkLeaks" -> "checkLeaks(detector=\"name\", threshold=N, minSize=N)";
      case "dominators" -> "dominators()";
      default -> op + "(...)";
    };
  }

  private String getOperatorDescription(String op) {
    return switch (op) {
      case "select" -> "project specific fields";
      case "top" -> "get top N results sorted by field";
      case "groupBy" -> "group and aggregate";
      case "count" -> "count total results";
      case "sum" -> "sum numeric field values";
      case "stats" -> "get statistics (min, max, avg, sum, count)";
      case "sortBy" -> "sort results by field(s)";
      case "head" -> "take first N results";
      case "tail" -> "take last N results";
      case "filter" -> "filter results by condition";
      case "distinct" -> "get distinct values";
      case "pathToRoot" -> "find path to GC root";
      case "checkLeaks" -> "run leak detection analysis";
      case "dominators" -> "get dominated objects";
      default -> null;
    };
  }

  private boolean hasParameters(String op) {
    return switch (op) {
      case "count", "pathToRoot", "dominators" -> false;
      default -> true;
    };
  }
}

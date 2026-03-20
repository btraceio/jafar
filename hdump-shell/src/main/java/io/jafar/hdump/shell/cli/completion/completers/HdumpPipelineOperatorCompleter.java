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
    String prefix = calculateJlinePrefix(ctx.jlineWord(), partial);

    for (String op : metadata.getOperators()) {
      if (op.toLowerCase().startsWith(partial)) {
        String template = getOperatorTemplate(op);
        String description = getOperatorDescription(op);
        // For operators with parameters, complete with "name(" to allow field completion
        // For no-arg operators like count, complete with "name()"
        boolean hasParams = hasParameters(op);
        String value = hasParams ? op + "(" : op + "()";
        candidates.add(candidateNoSpace(prefix + value, template, description));
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
      case "retentionPaths" -> "retentionPaths()";
      case "retainedBreakdown" -> "retainedBreakdown(depth=N)";
      case "checkLeaks" -> "checkLeaks(detector=\"name\", threshold=N, minSize=N)";
      case "dominators" -> "dominators()";
      case "join" -> "join(session=id|alias, root=\"eventType\", by=field)";
      case "len" -> "len(field)";
      case "uppercase" -> "uppercase(field)";
      case "lowercase" -> "lowercase(field)";
      case "trim" -> "trim(field)";
      case "replace" -> "replace(field, \"old\", \"new\")";
      case "abs" -> "abs(field)";
      case "round" -> "round(field)";
      case "floor" -> "floor(field)";
      case "ceil" -> "ceil(field)";
      case "waste" -> "waste()";
      case "objects" -> "objects()";
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
      case "retentionPaths" -> "merge retention paths at class level";
      case "retainedBreakdown" -> "expand dominator subtree by class";
      case "checkLeaks" -> "run leak detection analysis";
      case "dominators" -> "get dominated objects";
      case "join" -> "join with another session (heap diff or JFR correlation)";
      case "len" -> "string length or collection size";
      case "uppercase" -> "convert to uppercase";
      case "lowercase" -> "convert to lowercase";
      case "trim" -> "trim whitespace";
      case "replace" -> "replace occurrences in string";
      case "abs" -> "absolute value";
      case "round" -> "round to nearest integer";
      case "floor" -> "round down";
      case "ceil" -> "round up";
      case "waste" -> "analyze collection capacity waste";
      case "objects" -> "drill down from clusters to member objects";
      default -> null;
    };
  }

  private boolean hasParameters(String op) {
    return switch (op) {
      case "count", "pathToRoot", "retentionPaths", "dominators", "waste", "objects" -> false;
      default -> true;
    };
  }
}

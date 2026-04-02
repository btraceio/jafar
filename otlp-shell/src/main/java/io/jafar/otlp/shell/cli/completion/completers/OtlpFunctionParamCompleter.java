package io.jafar.otlp.shell.cli.completion.completers;

import io.jafar.otlp.shell.cli.completion.OtlpMetadataService;
import io.jafar.shell.core.completion.CompletionContext;
import io.jafar.shell.core.completion.CompletionContextType;
import io.jafar.shell.core.completion.ContextCompleter;
import java.util.List;
import org.jline.reader.Candidate;

/** Completer for function parameters inside otlp pipeline operators. */
public final class OtlpFunctionParamCompleter implements ContextCompleter<OtlpMetadataService> {

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.FUNCTION_PARAM;
  }

  @Override
  public void complete(
      CompletionContext ctx, OtlpMetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput();
    String functionName = ctx.functionName();
    int paramIndex = ctx.parameterIndex();

    String prefix;
    if (partial.isEmpty() && ctx.jlineWord() != null && ctx.jlineWord().isBlank()) {
      prefix = "";
    } else {
      prefix = calculateJlinePrefix(ctx.jlineWord(), partial);
    }

    if (functionName == null) {
      return;
    }

    switch (functionName) {
      case "top" -> completeTop(paramIndex, partial, prefix, metadata, candidates);
      case "groupBy" -> completeGroupBy(paramIndex, partial, prefix, metadata, candidates);
      case "stackprofile" -> completeStackprofile(partial, prefix, metadata, candidates);
      case "stats", "distinct", "sortBy", "select", "filter" ->
          completeFieldNames(partial, prefix, metadata.getFieldNames("samples"), candidates);
      default -> completeFieldNames(partial, prefix, metadata.getFieldNames("samples"), candidates);
    }
  }

  private void completeTop(
      int paramIndex,
      String partial,
      String prefix,
      OtlpMetadataService metadata,
      List<Candidate> candidates) {
    switch (paramIndex) {
      case 0 -> {
        // First param: numeric count hint
        for (String n : List.of("10", "20", "50")) {
          if (n.startsWith(partial)) {
            candidates.add(candidateNoSpace(prefix + n, n, "top N"));
          }
        }
      }
      case 1 -> completeFieldNames(partial, prefix, metadata.getFieldNames("samples"), candidates);
      case 2 -> {
        if ("asc".startsWith(partial)) {
          candidates.add(candidate(prefix + "asc", "asc", "ascending order"));
        }
        if ("desc".startsWith(partial)) {
          candidates.add(candidate(prefix + "desc", "desc", "descending order"));
        }
      }
      default -> {}
    }
  }

  private void completeGroupBy(
      int paramIndex,
      String partial,
      String prefix,
      OtlpMetadataService metadata,
      List<Candidate> candidates) {
    if (paramIndex == 0) {
      completeFieldNames(partial, prefix, metadata.getFieldNames("samples"), candidates);
    } else {
      // Second param: aggregation function
      for (String agg : List.of("sum(", "count()")) {
        if (agg.startsWith(partial)) {
          candidates.add(candidateNoSpace(prefix + agg, agg, "aggregation"));
        }
      }
    }
  }

  private void completeStackprofile(
      String partial, String prefix, OtlpMetadataService metadata, List<Candidate> candidates) {
    // stackprofile only accepts value type names, not structural fields
    for (String name : metadata.getValueTypeNames()) {
      if (name.startsWith(partial)) {
        candidates.add(candidate(prefix + name, name, "value type"));
      }
    }
  }

  private void completeFieldNames(
      String partial, String prefix, List<String> fields, List<Candidate> candidates) {
    for (String field : fields) {
      if (field.startsWith(partial)) {
        candidates.add(candidate(prefix + field, field, null));
      }
    }
  }
}

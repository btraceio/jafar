package io.jafar.otelp.shell.cli.completion.completers;

import io.jafar.otelp.shell.cli.completion.OtelpMetadataService;
import io.jafar.shell.core.completion.CompletionContext;
import io.jafar.shell.core.completion.CompletionContextType;
import io.jafar.shell.core.completion.ContextCompleter;
import java.util.List;
import org.jline.reader.Candidate;

/**
 * Completer for field names inside otelp filter predicates ({@code samples[...]}).
 *
 * <p>Suggests value type names from the active session plus structural fields such as {@code
 * stackTrace/0/name} and {@code thread}.
 */
public final class OtelpFilterFieldCompleter implements ContextCompleter<OtelpMetadataService> {

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.FILTER_FIELD;
  }

  @Override
  public void complete(
      CompletionContext ctx, OtelpMetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput();
    String prefix = calculateJlinePrefix(ctx.jlineWord(), partial);

    for (String field : metadata.getFieldNames("samples")) {
      if (field.startsWith(partial)) {
        candidates.add(candidate(prefix + field, field, getDescription(field)));
      }
    }
  }

  private String getDescription(String field) {
    return switch (field) {
      case "sampleType" -> "sample type name (e.g. cpu, wall, alloc_space)";
      case "stackTrace" -> "stack frames (leaf-first list)";
      case "stackTrace/0/name" -> "leaf frame function name";
      case "stackTrace/0/filename" -> "leaf frame source file";
      case "stackTrace/0/line" -> "leaf frame line number";
      case "stackTrace/1/name" -> "caller frame function name";
      case "stackTrace/1/filename" -> "caller frame source file";
      case "thread" -> "thread name";
      default -> "sample value";
    };
  }
}

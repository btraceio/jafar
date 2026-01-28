package io.jafar.shell.cli.completion.completers;

import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextType;
import io.jafar.shell.cli.completion.ContextCompleter;
import io.jafar.shell.cli.completion.FunctionRegistry;
import io.jafar.shell.cli.completion.FunctionSpec;
import io.jafar.shell.cli.completion.MetadataService;
import java.util.List;
import org.jline.reader.Candidate;

/**
 * Completer for pipeline operators after |. Suggests aggregation functions, transforms, and
 * decorators based on the available field types in the current event type.
 *
 * <p>Uses semantic filtering to hide functions that don't apply to the current context. For
 * example, {@code sum()} won't be suggested if the event type has no numeric fields.
 */
public class PipelineOperatorCompleter implements ContextCompleter {

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.PIPELINE_OPERATOR;
  }

  @Override
  public void complete(
      CompletionContext ctx, MetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput().toLowerCase();
    String eventType = ctx.eventType();

    // Analyze available field types for semantic filtering
    // If no event type or no field metadata, show all functions (graceful fallback)
    boolean hasMetadata = eventType != null && !metadata.getFieldNames(eventType).isEmpty();
    boolean hasNumeric = hasMetadata && metadata.hasNumericFields(eventType);
    boolean hasString = hasMetadata && metadata.hasStringFields(eventType);
    boolean hasTime = hasMetadata && metadata.hasTimeFields(eventType);

    // Get all pipeline operators from the registry
    for (FunctionSpec spec : FunctionRegistry.getPipelineOperators()) {
      String name = spec.name();
      String template = spec.template();
      String description = spec.description();

      // Filter by prefix match
      if (!name.toLowerCase().startsWith(partial) && !template.toLowerCase().startsWith(partial)) {
        continue;
      }

      // Filter by applicability only if we have metadata (otherwise show all)
      if (hasMetadata && !spec.isApplicable(hasNumeric, hasString, hasTime)) {
        continue;
      }

      // For functions with parameters, complete with "name(" to allow field completion
      // For no-arg functions like count(), complete with "name()"
      boolean hasParams = !spec.parameters().isEmpty() || spec.hasVarargs();
      String value = hasParams ? name + "(" : name + "()";

      // Show template in display for user reference, but insert simpler value
      // This allows function parameter completer to suggest actual fields
      candidates.add(candidateNoSpace(value, template, description));
    }
  }
}

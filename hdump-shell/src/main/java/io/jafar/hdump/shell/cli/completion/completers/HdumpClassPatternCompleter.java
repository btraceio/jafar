package io.jafar.hdump.shell.cli.completion.completers;

import io.jafar.hdump.shell.cli.completion.HdumpMetadataService;
import io.jafar.shell.core.completion.CompletionContext;
import io.jafar.shell.core.completion.CompletionContextType;
import io.jafar.shell.core.completion.ContextCompleter;
import java.util.List;
import java.util.Set;
import org.jline.reader.Candidate;

/**
 * Completer for class name patterns after objects/ or classes/. Suggests class names from the
 * current heap dump session.
 */
public final class HdumpClassPatternCompleter implements ContextCompleter<HdumpMetadataService> {

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.TYPE_PATTERN;
  }

  @Override
  public void complete(
      CompletionContext ctx, HdumpMetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput();
    String rootType = ctx.rootType();

    // Get available class names from the heap dump
    Set<String> types = metadata.getAvailableTypes();

    for (String type : types) {
      if (type.startsWith(partial)) {
        // Build full path with root
        String value = rootType + "/" + type;
        // Show simple class name in display, full path in value
        String simpleName = type.contains(".") ? type.substring(type.lastIndexOf('.') + 1) : type;
        candidates.add(new Candidate(value, type, null, simpleName, null, null, false));
      }
    }
  }
}

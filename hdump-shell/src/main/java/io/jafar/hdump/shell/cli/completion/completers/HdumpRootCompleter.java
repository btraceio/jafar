package io.jafar.hdump.shell.cli.completion.completers;

import io.jafar.hdump.shell.cli.completion.HdumpMetadataService;
import io.jafar.shell.core.completion.CompletionContext;
import io.jafar.shell.core.completion.CompletionContextType;
import io.jafar.shell.core.completion.ContextCompleter;
import java.util.List;
import org.jline.reader.Candidate;

/**
 * Completer for root path types after 'show ' command.
 *
 * <p>Roots that accept a type path (objects, classes, gcroots, clusters) are completed with a
 * trailing {@code /} so the user can immediately type a class pattern. Roots that accept an
 * optional parameter list instead (duplicates) are completed with a trailing space.
 */
public final class HdumpRootCompleter implements ContextCompleter<HdumpMetadataService> {

  /** Roots that do not use a {@code /type-pattern} suffix. */
  private static final java.util.Set<String> PARAM_ROOTS =
      java.util.Set.of("duplicates", "clusters", "whatif");

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.ROOT;
  }

  @Override
  public void complete(
      CompletionContext ctx, HdumpMetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput();
    for (String root : metadata.getRootTypes()) {
      if (PARAM_ROOTS.contains(root)) {
        // Complete with a space; no slash — these roots don't take a type path
        if (root.startsWith(partial)) {
          candidates.add(candidate(root, root, null));
        }
      } else {
        // Complete with trailing / to allow immediate class-pattern typing
        String rootWithSlash = root + "/";
        if (rootWithSlash.startsWith(partial)) {
          candidates.add(noSpace(rootWithSlash));
        }
      }
    }
  }
}

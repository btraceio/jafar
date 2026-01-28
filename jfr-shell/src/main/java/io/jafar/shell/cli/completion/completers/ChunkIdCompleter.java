package io.jafar.shell.cli.completion.completers;

import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextType;
import io.jafar.shell.cli.completion.ContextCompleter;
import io.jafar.shell.cli.completion.MetadataService;
import java.util.List;
import org.jline.reader.Candidate;

/**
 * Completer for chunk IDs after 'show chunks/'. Suggests available chunk IDs from the recording.
 */
public final class ChunkIdCompleter implements ContextCompleter {

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.CHUNK_ID;
  }

  @Override
  public void complete(
      CompletionContext ctx, MetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput();
    List<Integer> chunkIds = metadata.getChunkIds();

    for (Integer id : chunkIds) {
      String value = "chunks/" + id;
      if (value.startsWith("chunks/" + partial)) {
        candidates.add(new Candidate(value, value, null, null, null, null, true));
      }
    }
  }
}

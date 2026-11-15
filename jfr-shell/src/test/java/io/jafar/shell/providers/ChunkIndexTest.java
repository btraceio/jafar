package io.jafar.shell.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import io.jafar.shell.jfrpath.JfrPathParser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A chunk is addressed by the {@code index} that {@code chunks} lists: 1-based, in file order.
 *
 * <p>{@code loadChunk} used to treat the number as a 0-based position while every listing used the
 * 1-based index. {@code chunks} printed index 1 and 2, and tab completion offered {@code chunks/1}
 * and {@code chunks/2} ({@code JFRSession.getAvailableChunkIds}). Completing {@code chunks/1}
 * showed the second chunk, and {@code chunks/2} showed nothing.
 */
class ChunkIndexTest {

  private static final Path TCK_RECORDING =
      Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr")
          .toAbsolutePath()
          .normalize();

  @Test
  void eachListedIndexLoadsThatChunk() throws Exception {
    List<Map<String, Object>> chunks = ChunkProvider.loadAllChunks(TCK_RECORDING);
    assertEquals(2, chunks.size(), "chunks in fixture");
    for (Map<String, Object> row : chunks) {
      int index = ((Number) row.get("index")).intValue();
      assertEquals(row, ChunkProvider.loadChunk(TCK_RECORDING, index), "chunk " + index);
    }
    assertNull(ChunkProvider.loadChunk(TCK_RECORDING, 0), "indexes start at 1");
    assertNull(ChunkProvider.loadChunk(TCK_RECORDING, chunks.size() + 1), "past the last chunk");
  }

  /** The ids completion offers resolve, through JfrPath, to the chunk carrying that index. */
  @Test
  void completedChunkIdsSelectThatChunk() throws Exception {
    ParsingContext ctx = ParsingContext.create();
    SessionManager<Session> sessions =
        new SessionManager<>((path, c) -> new JFRSession(path, (ParsingContext) c), ctx);
    sessions.open(TCK_RECORDING, null);
    try {
      JFRSession session = (JFRSession) sessions.getCurrent().get().session;
      List<Integer> ids = session.getAvailableChunkIds();
      assertEquals(List.of(1, 2), ids, "completion ids");
      List<Object> selected = new ArrayList<>();
      for (int id : ids) {
        List<Map<String, Object>> rows =
            new JfrPathEvaluator().evaluate(session, JfrPathParser.parse("chunks/" + id));
        assertEquals(1, rows.size(), "rows for chunks/" + id);
        selected.add(rows.get(0).get("index"));
      }
      assertEquals(ids, selected);
    } finally {
      sessions.closeAll();
    }
  }
}

package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The TUI events browser must count every event of a multi-chunk recording.
 *
 * <p>{@code StreamingChunkParser} parses each chunk on its own pool thread, so the untyped handler
 * runs concurrently for different chunks. {@code JfrTuiAdapter.loadEventsSummary} used to count
 * into a plain {@code HashMap<String, long[]>} with {@code computeIfAbsent(...)[0]++}, which loses
 * increments. The race needs at least two parser threads ({@code max(nproc - 2, 1)}): on a machine
 * with three or fewer processors this test passes with or without the fix.
 */
class JfrTuiAdapterEventsSummaryTest {

  /** Checked into the repository (two chunks), so this needs no download. */
  private static final Path TCK_RECORDING =
      Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr")
          .toAbsolutePath()
          .normalize();

  private static JFRSession session;
  private static JfrTuiAdapter adapter;

  @BeforeAll
  static void open() throws Exception {
    session = new JFRSession(TCK_RECORDING, ParsingContext.create());
    // Without at least two chunks there is nothing to race.
    assertEquals(List.of(1, 2), session.getAvailableChunkIds(), "chunks in fixture");
    adapter =
        new JfrTuiAdapter(
            new SessionManager<JFRSession>(
                (path, c) -> new JFRSession(path, (ParsingContext) c), ParsingContext.create()),
            null);
  }

  @AfterAll
  static void close() throws Exception {
    session.close();
  }

  @Test
  void eventCountsMatchTheJdkReaderOnEveryRun() throws Exception {
    Map<String, Long> expected = jdkCounts();
    // Sanity check of the oracle itself: the figures `jfr summary` prints for this file.
    assertEquals(78, expected.size(), "event types in fixture");
    assertEquals(67_657L, expected.values().stream().mapToLong(Long::longValue).sum(), "events");

    for (int run = 1; run <= 10; run++) {
      Map<String, Long> actual = new TreeMap<>();
      for (Map<String, Object> row : adapter.loadBrowseSummary(session, "events")) {
        actual.put((String) row.get("name"), ((Number) row.get("count")).longValue());
      }
      assertEquals(expected, actual, "events summary on run " + run);
    }
  }

  /** Per-type counts from the JDK's own reader, which decodes chunks sequentially. */
  private static Map<String, Long> jdkCounts() throws Exception {
    Map<String, Long> counts = new TreeMap<>();
    try (RecordingFile file = new RecordingFile(TCK_RECORDING)) {
      while (file.hasMoreEvents()) {
        RecordedEvent e = file.readEvent();
        counts.merge(e.getEventType().getName(), 1L, Long::sum);
      }
    }
    return counts;
  }
}

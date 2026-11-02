package io.jafar.shell.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import io.jafar.shell.jfrpath.JfrPathParser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The Jafar backend's sources must give the same answer on every run of a multi-chunk recording.
 *
 * <p>{@code StreamingChunkParser} parses each chunk on its own pool thread, so listener callbacks
 * for different chunks run concurrently. {@code JafarSources} used to collect into state shared by
 * all chunks: {@code cp/jdk.types.Symbol} lost rows to a plain {@code ArrayList} (and once threw
 * {@code ArrayIndexOutOfBoundsException} from {@code ArrayList.add}), the chunk list came back in
 * completion order, and {@code crossref} let one chunk's {@code onChunkStart} replace the other
 * chunk's state mid-flight.
 *
 * <p>The race needs at least two parser threads, and the pool has {@code max(nproc - 2, 1)}: on a
 * machine with three or fewer processors these tests pass with or without the fix. The repetition
 * counts are sized from the failure rates measured against the unfixed code on four processors (two
 * parser threads), over 200 runs each: {@code cp/jdk.types.Symbol} gave a different count on nearly
 * every run, the summary on 38 runs, the class list on 5 and the chunk order on 5.
 */
class MultiChunkDeterminismTest {

  /** Checked into the repository (two chunks), so this needs no download. */
  private static final Path TCK_RECORDING =
      Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr")
          .toAbsolutePath()
          .normalize();

  @BeforeAll
  static void recordingHasSeveralChunks() throws Exception {
    // Without at least two chunks there is nothing to race.
    assertEquals(2, ChunkProvider.loadAllChunks(TCK_RECORDING).size(), "chunks in fixture");
  }

  /** The reported route: JfrPathEvaluator with a fresh session each time. */
  @Test
  void cpSymbolStringValuesAreStableAcrossFreshSessions() throws Exception {
    assertStable(
        30,
        () -> {
          ParsingContext ctx = ParsingContext.create();
          SessionManager<JFRSession> sessions =
              new SessionManager<>((path, c) -> new JFRSession(path, (ParsingContext) c), ctx);
          sessions.open(TCK_RECORDING, null);
          try {
            return new JfrPathEvaluator()
                .evaluateValues(
                    sessions.getCurrent().get().session,
                    JfrPathParser.parse("cp/jdk.types.Symbol/string"))
                .size();
          } finally {
            sessions.closeAll();
          }
        });
  }

  @Test
  void constantPoolEntriesAreStable() throws Exception {
    assertStable(30, () -> ConstantPoolProvider.loadEntries(TCK_RECORDING, "jdk.types.Symbol"));
  }

  @Test
  void constantPoolSummaryIsStable() throws Exception {
    assertStable(30, () -> ConstantPoolProvider.loadSummary(TCK_RECORDING));
  }

  @Test
  void constantPoolTypesAreStable() throws Exception {
    assertStable(30, () -> new TreeSet<>(ConstantPoolProvider.getAvailableTypes(TCK_RECORDING)));
  }

  @Test
  void crossrefIsStable() throws Exception {
    assertStable(
        10,
        () -> {
          Map<String, Object> r = ConstantPoolProvider.crossref(TCK_RECORDING, "jdk.types.Symbol");
          return List.of(
              r.get("cpTotal"),
              r.get("cpReferenced"),
              new TreeMap<>((Map<?, ?>) r.get("eventCounts")),
              new TreeMap<>((Map<?, ?>) r.get("uniqueByEventType")),
              new TreeMap<>((Map<?, ?>) r.get("totalRefsByEventType")));
        });
  }

  @Test
  void allClassesAreStable() throws Exception {
    assertStable(
        200,
        () -> {
          List<Object> names = new ArrayList<>();
          for (Map<String, Object> c : MetadataProvider.loadAllClasses(TCK_RECORDING)) {
            names.add(c.get("name"));
          }
          return names;
        });
  }

  @Test
  void chunksComeBackInRecordingOrder() throws Exception {
    assertStable(
        200,
        () -> {
          List<Object> indexes = new ArrayList<>();
          for (Map<String, Object> c : ChunkProvider.loadAllChunks(TCK_RECORDING)) {
            indexes.add(c.get("index"));
          }
          assertEquals(List.of(1, 2), indexes, "chunk order");
          return indexes;
        });
  }

  @Test
  void chunkSummaryIsStable() throws Exception {
    assertStable(30, () -> new TreeMap<>(ChunkProvider.getChunkSummary(TCK_RECORDING)));
  }

  /**
   * Runs {@code query} {@code times} times and fails on the first answer that differs from the
   * first one. The answers are compared whole, so a reordering or a changed value fails as surely
   * as a lost row.
   */
  private static void assertStable(int times, Callable<Object> query) throws Exception {
    Object first = query.call();
    for (int i = 1; i < times; i++) {
      Object next = query.call();
      if (!first.equals(next)) {
        throw new AssertionError(
            "run " + (i + 1) + " of " + times + " differs from run 1: " + difference(first, next));
      }
    }
  }

  private static String difference(Object a, Object b) {
    if (a instanceof List<?> la && b instanceof List<?> lb) {
      if (la.size() != lb.size()) {
        return "size " + la.size() + " vs " + lb.size();
      }
      for (int i = 0; i < la.size(); i++) {
        if (!Objects.equals(la.get(i), lb.get(i))) {
          return "element " + i + ": " + describe(la.get(i)) + " vs " + describe(lb.get(i));
        }
      }
    }
    return describe(a) + " vs " + describe(b);
  }

  private static String describe(Object o) {
    String s = String.valueOf(o);
    return s.length() > 200 ? s.substring(0, 200) + "..." : s;
  }
}

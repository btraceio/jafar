package io.jafar.shell.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The Jafar backend's metadata and chunk sources must give the same answer on every run of a
 * multi-chunk recording.
 *
 * <p>{@code StreamingChunkParser} parses each chunk on its own pool thread, so listener callbacks
 * for different chunks run concurrently. {@code JafarSources} collected into state shared by all
 * chunks: {@code loadAllClasses} appended to a plain {@code ArrayList}, {@code loadClass} kept
 * whichever chunk's definition was written last, {@code loadChunks} returned rows in completion
 * order (so {@code loadChunk(i)} picked a different chunk from run to run) and {@code
 * getChunkSummary} updated one {@code long[]} from every thread. The constant pool sources are
 * covered by {@code ConstantPoolChunkLocalityTest} and the TCK.
 *
 * <p>The race needs at least two parser threads, and the pool has {@code max(nproc - 2, 1)}: on a
 * machine with three or fewer processors these tests pass with or without the fix. The repetition
 * counts are sized from failure rates measured against the unfixed code on four processors (two
 * parser threads), over 200 runs each: the class list differed on 5 runs and the chunk order on 5.
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

  @Test
  void allClassesAreStable() throws Exception {
    assertStable(200, () -> MetadataProvider.loadAllClasses(TCK_RECORDING));
  }

  /** java.lang.Thread is defined differently by the two chunks of the fixture. */
  @Test
  void classDefinedByBothChunksIsStable() throws Exception {
    assertStable(100, () -> MetadataProvider.loadClass(TCK_RECORDING, "java.lang.Thread"));
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

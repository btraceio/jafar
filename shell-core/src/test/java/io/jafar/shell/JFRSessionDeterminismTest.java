package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.parser.api.JfrType;
import io.jafar.parser.api.ParsingContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@link JFRSession} must give the same answer for every fresh session on a multi-chunk recording.
 *
 * <p>{@code StreamingChunkParser} parses each chunk on its own pool thread, so listener callbacks
 * for different chunks run concurrently. {@code JFRSession} used to collect into state shared by
 * all chunks: plain {@code HashMap}/{@code HashSet} fields from {@code onMetadata}, an {@code
 * ArrayList} of chunk ids from {@code onChunkStart} (completion order, lost entries), a {@code
 * HashSet} of constant-pool types from {@code onCheckpoint}, and a {@code HashMap.merge} event
 * count from the typed handler wrapper.
 *
 * <p>The race needs at least two parser threads, and the pool has {@code max(nproc - 2, 1)}: on a
 * machine with three or fewer processors these tests pass with or without the fix.
 */
class JFRSessionDeterminismTest {

  /** Checked into the repository (two chunks), so this needs no download. */
  private static final Path TCK_RECORDING =
      Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr")
          .toAbsolutePath()
          .normalize();

  /** 13,800 events in the fixture, split across both chunks. */
  @JfrType("jdk.TenuringDistribution")
  public interface JFRTenuringDistribution {
    int age();
  }

  @BeforeAll
  static void recordingHasSeveralChunks() throws Exception {
    // Without at least two chunks there is nothing to race.
    try (JFRSession s = new JFRSession(TCK_RECORDING, ParsingContext.create())) {
      assertEquals(List.of(1, 2), s.getAvailableChunkIds(), "chunks in fixture");
    }
  }

  @Test
  void metadataScanIsStable() throws Exception {
    assertStable(
        200,
        () ->
            withSession(
                s ->
                    List.of(
                        new TreeSet<>(s.getAllMetadataTypes()),
                        new TreeMap<>(s.getMetadataTypeIds()),
                        new TreeSet<>(s.getAvailableTypes()),
                        new TreeSet<>(s.getAvailableMetadataTypes()),
                        new TreeSet<>(s.getNonPrimitiveMetadataTypes()),
                        new TreeSet<>(s.getPrimitiveMetadataTypes()))));
  }

  @Test
  void chunkIdsComeBackInRecordingOrder() throws Exception {
    assertStable(
        1000,
        () ->
            withSession(
                s -> {
                  List<Integer> ids = s.getAvailableChunkIds();
                  assertEquals(List.of(1, 2), ids, "chunk order");
                  return ids;
                }));
  }

  @Test
  void constantPoolTypesAreStable() throws Exception {
    assertStable(200, () -> withSession(s -> new TreeSet<>(s.getAvailableConstantPoolTypes())));
  }

  /** {@code handle} + {@code run}: the counting wrapper and the typed-parser metadata listener. */
  @Test
  void typedRunIsStable() throws Exception {
    assertStable(
        10,
        () ->
            withSession(
                s -> {
                  Map<String, Long> scanned = new TreeMap<>(s.getMetadataTypeIds());
                  s.handle(JFRTenuringDistribution.class, (event, ctl) -> {});
                  s.run();
                  // Each chunk defines its own type ids. A collector that ran again during run()
                  // left whichever chunk finished last, which need not be the one the scan kept.
                  assertEquals(scanned, new TreeMap<>(s.getMetadataTypeIds()), "ids after run()");
                  return List.of(
                      s.getTotalEvents(),
                      new TreeMap<>(s.getEventTypeCounts()),
                      new TreeMap<>(s.getMetadataTypeIds()),
                      new TreeSet<>(s.getAllMetadataTypes()));
                }));
  }

  private interface SessionQuery {
    Object apply(JFRSession session) throws Exception;
  }

  private static Object withSession(SessionQuery query) throws Exception {
    try (JFRSession s = new JFRSession(TCK_RECORDING, ParsingContext.create())) {
      return query.apply(s);
    }
  }

  /**
   * Runs {@code query} {@code times} times and fails on the first answer that differs from the
   * first one. The answers are compared whole, so a reordering or a changed value fails as surely
   * as a lost entry.
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
    if (a instanceof List<?> la && b instanceof List<?> lb && la.size() == lb.size()) {
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
    return s.length() > 300 ? s.substring(0, 300) + "..." : s;
  }
}

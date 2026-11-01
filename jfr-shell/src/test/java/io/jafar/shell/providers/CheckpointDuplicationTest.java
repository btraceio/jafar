package io.jafar.shell.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.impl.UntypedParserContextFactory;
import io.jafar.parser.internal_api.CheckpointEvent;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import io.jafar.shell.jfrpath.JfrPathParser;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Each constant pool entry is counted once per chunk, however many checkpoints the chunk has.
 *
 * <p>The parser keeps one pool per type per chunk and adds every checkpoint's entries to it, so the
 * pool seen in {@code onCheckpoint} holds everything read so far, not that checkpoint's entries.
 * {@code JafarSources} drained (or counted) the whole pool at every checkpoint. Chunk 1 of {@code
 * tck-test.jfr} has 152 checkpoints: its 875 distinct {@code jdk.types.Symbol} ids came back as
 * 28882 rows, and with chunk 2's 2508 the query answered 31390 instead of 3383. {@code loadSummary}
 * and {@code crossref}'s {@code cpTotal} summed the same cumulative sizes.
 *
 * <p>The opposite mistake, reading only the first checkpoint, missed types: {@code
 * getAvailableTypes} stopped there, and chunk 1's first checkpoint holds 1 of its 29 types, so it
 * reported 13 of the recording's 32.
 *
 * <p>The oracles below read every checkpoint and collect into sets, independent of the code under
 * test.
 */
class CheckpointDuplicationTest {

  private static final Path TCK_RECORDING =
      Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr")
          .toAbsolutePath()
          .normalize();

  private static final String SYMBOL = "jdk.types.Symbol";

  private static long distinctSymbolIds;
  private static final Set<String> allPoolTypes = new TreeSet<>();

  @BeforeAll
  static void countDistinctIdsPerChunk() throws Exception {
    Map<Integer, Set<Long>> idsByChunk = new ConcurrentSkipListMap<>();
    Map<Integer, Integer> checkpointsByChunk = new ConcurrentSkipListMap<>();
    try (StreamingChunkParser parser =
        new StreamingChunkParser(new UntypedParserContextFactory())) {
      parser.parse(
          TCK_RECORDING,
          new ChunkParserListener() {
            @Override
            public boolean onCheckpoint(ParserContext context, CheckpointEvent event) {
              int chunk = context.getChunkIndex();
              checkpointsByChunk.merge(chunk, 1, Integer::sum);
              Set<Long> ids = idsByChunk.computeIfAbsent(chunk, k -> new HashSet<>());
              context
                  .getConstantPools()
                  .pools()
                  .forEach(
                      cp -> {
                        synchronized (allPoolTypes) { // shared by both chunks' threads
                          allPoolTypes.add(cp.getType().getName());
                        }
                        if (cp.getType().getName().equals(SYMBOL)) {
                          for (Iterator<Long> it = cp.ids(); it.hasNext(); ) {
                            ids.add(it.next());
                          }
                        }
                      });
              return true;
            }
          });
    }
    // The fixture must exercise the bug: some chunk with more than one checkpoint.
    assertTrue(
        checkpointsByChunk.values().stream().anyMatch(n -> n > 1),
        "checkpoints per chunk: " + checkpointsByChunk);
    distinctSymbolIds = idsByChunk.values().stream().mapToLong(Set::size).sum();
    // 875 + 2508 when this was written; the assertions below use the oracle, not the literal.
    assertEquals(3383, distinctSymbolIds, "distinct Symbol ids per chunk, summed");
  }

  @Test
  void availableTypesIncludeTypesFromEveryCheckpoint() throws Exception {
    assertEquals(
        allPoolTypes, new TreeSet<>(ConstantPoolProvider.getAvailableTypes(TCK_RECORDING)));
  }

  @Test
  void entriesAreNotRepeatedPerCheckpoint() throws Exception {
    assertEquals(distinctSymbolIds, ConstantPoolProvider.loadEntries(TCK_RECORDING, SYMBOL).size());
  }

  @Test
  void jfrPathValuesAreNotRepeatedPerCheckpoint() throws Exception {
    ParsingContext ctx = ParsingContext.create();
    SessionManager<JFRSession> sessions =
        new SessionManager<>((path, c) -> new JFRSession(path, (ParsingContext) c), ctx);
    sessions.open(TCK_RECORDING, null);
    try {
      List<Object> values =
          new JfrPathEvaluator()
              .evaluateValues(
                  sessions.getCurrent().get().session,
                  JfrPathParser.parse("cp/jdk.types.Symbol/string"));
      assertEquals(distinctSymbolIds, values.size());
    } finally {
      sessions.closeAll();
    }
  }

  @Test
  void summaryCountsEachEntryOnce() throws Exception {
    Object total = null;
    for (Map<String, Object> row : ConstantPoolProvider.loadSummary(TCK_RECORDING)) {
      if (SYMBOL.equals(row.get("name"))) {
        total = row.get("totalSize");
      }
    }
    assertEquals(distinctSymbolIds, total);
  }

  @Test
  void crossrefTotalCountsEachEntryOnce() throws Exception {
    Map<String, Object> r = ConstantPoolProvider.crossref(TCK_RECORDING, SYMBOL);
    assertEquals(distinctSymbolIds, r.get("cpTotal"));
    long referenced = (long) r.get("cpReferenced");
    assertTrue(referenced <= distinctSymbolIds, "cpReferenced " + referenced);
    assertEquals(distinctSymbolIds - referenced, r.get("cpUnused"));
  }
}

package io.jafar.parser.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;

/**
 * {@code Control.ChunkInfo.chunkIndex()} is the chunk's position in the file: 1 for the first
 * chunk, the same on every parse and whatever the parallelism, unlike {@code chunkId()}, which
 * counts chunks as they are parsed in the JVM.
 */
class ChunkIndexTest {
  /** Real recording, checked in: 67,657 events in 2 chunks. */
  private static final Path RECORDING =
      Paths.get("../jfr-shell-tck/src/main/resources/tck-test.jfr");

  @Test
  void isTheChunksPositionInTheFileOnEveryParse() throws Exception {
    // Read one chunk after the other: the index must go 1, 1, ..., 2, 2, ... and never back.
    List<Integer> inFileOrder = Collections.synchronizedList(new ArrayList<>());
    try (UntypedJafarParser parser =
        ParsingContext.create().withParallelism(1).newUntypedParser(RECORDING)) {
      parser.handle((type, event, ctl) -> inFileOrder.add(ctl.chunkInfo().chunkIndex()));
      parser.run();
    }
    assertEquals(67_657, inFileOrder.size());
    List<Integer> sorted = new ArrayList<>(inFileOrder);
    Collections.sort(sorted);
    assertEquals(sorted, inFileOrder, "indexes are not in file order");
    Map<Integer, Long> sequential = countPerIndex(inFileOrder);
    assertEquals(List.of(1, 2), new ArrayList<>(sequential.keySet()));

    // Chunks in parallel, twice: the same events under the same indexes.
    for (int run = 0; run < 2; run++) {
      Map<Integer, LongAdder> parallel = new ConcurrentHashMap<>();
      try (UntypedJafarParser parser = ParsingContext.create().newUntypedParser(RECORDING)) {
        parser.handle(
            (type, event, ctl) ->
                parallel
                    .computeIfAbsent(ctl.chunkInfo().chunkIndex(), k -> new LongAdder())
                    .increment());
        parser.run();
      }
      Map<Integer, Long> counts = new TreeMap<>();
      parallel.forEach((index, count) -> counts.put(index, count.sum()));
      assertEquals(sequential, counts, "parallel run " + run);
    }
  }

  private static Map<Integer, Long> countPerIndex(List<Integer> indexes) {
    Map<Integer, Long> counts = new TreeMap<>();
    for (Integer index : indexes) {
      counts.merge(index, 1L, Long::sum);
    }
    return counts;
  }
}

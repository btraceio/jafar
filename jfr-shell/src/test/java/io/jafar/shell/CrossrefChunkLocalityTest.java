package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ConstantPool;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.impl.UntypedParserContextFactory;
import io.jafar.parser.internal_api.CheckpointEvent;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.shell.backend.BackendRegistry;
import io.jafar.shell.providers.ConstantPoolProvider;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import jdk.jfr.Recording;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code crossref()} counts per chunk, on chunk-local ids, deterministically.
 *
 * <p>The fixture is a real recording made in this JVM. A disk recording flushes about once a
 * second, writing a checkpoint each time, so its single chunk has several checkpoints and the stack
 * trace pool grows across them (asserted as the first test's precondition). Two bugs it pins:
 *
 * <ul>
 *   <li>The pool size was added at every checkpoint, so an entry was counted once per checkpoint it
 *       survived: on a 36-checkpoint recording {@code cpTotal} was 1047 for 196 entries.
 *   <li>Per-chunk state sat in single-slot arrays shared by the parser's worker threads and reset
 *       by every {@code onChunkStart}, and ids from different chunks were merged as if they were
 *       the same entries. On three concatenated copies of one chunk, five runs gave five different
 *       {@code cpTotal}/{@code cpReferenced} pairs, none the expected 3x.
 * </ul>
 */
class CrossrefChunkLocalityTest {
  private static final String TYPE = "jdk.types.StackTrace";
  private static final int COPIES = 3;

  @TempDir static Path dir;
  private static Path single;
  private static Path concatenated;

  @BeforeAll
  static void record() throws Exception {
    single = dir.resolve("single.jfr");
    try (Recording r = new Recording()) {
      r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10)).withStackTrace();
      r.enable("jdk.ObjectAllocationSample").withStackTrace();
      r.enable("jdk.JavaExceptionThrow").withStackTrace();
      r.setDestination(single);
      r.start();
      long end = System.nanoTime() + Duration.ofMillis(2500).toNanos();
      long sink = 0;
      int depth = 0;
      while (System.nanoTime() < end) {
        sink += work(1 + depth++ % 12);
      }
      r.stop();
      assertTrue(sink != 42, "keep the work alive");
    }
    concatenated = dir.resolve("concatenated.jfr");
    byte[] bytes = Files.readAllBytes(single);
    try (OutputStream out = Files.newOutputStream(concatenated)) {
      for (int i = 0; i < COPIES; i++) {
        out.write(bytes); // JFR chunks concatenate into a valid multi-chunk recording
      }
    }
    assertEquals("jafar", BackendRegistry.getInstance().getCurrent().getId());
  }

  /** Recursion of varying depth, so samples and allocations produce distinct stack traces. */
  private static long work(int depth) {
    if (depth > 0) {
      return work(depth - 1) + 1;
    }
    long[] a = new long[64 + (int) (System.nanoTime() & 63)];
    try {
      if ((a.length & 7) == 0) {
        throw new IllegalStateException("x");
      }
    } catch (IllegalStateException e) {
      return a.length + e.getMessage().length();
    }
    return a.length;
  }

  @Test
  void cpTotalCountsEachEntryOnceAcrossCheckpoints() throws Exception {
    PoolSizes sizes = poolSizes(single);
    // Precondition: without a pool that grows across checkpoints, this test proves nothing.
    assertTrue(
        sizes.summedPerCheckpoint > sizes.atChunkEnd,
        "fixture must have the "
            + TYPE
            + " pool in several checkpoints; per checkpoint "
            + sizes.summedPerCheckpoint
            + ", at chunk end "
            + sizes.atChunkEnd);

    Map<String, Object> result = ConstantPoolProvider.crossref(single, TYPE);
    assertEquals(sizes.atChunkEnd, result.get("cpTotal"), String.valueOf(result));
    long referenced = (Long) result.get("cpReferenced");
    assertTrue(referenced > 0 && referenced <= sizes.atChunkEnd, String.valueOf(result));
  }

  @Test
  void concatenatedChunksGiveExactlyTheSumOnEveryRun() throws Exception {
    Map<String, Object> one = ConstantPoolProvider.crossref(single, TYPE);
    assertTrue(((Long) one.get("cpReferenced")) > 0, String.valueOf(one));
    List<String> runs = new ArrayList<>();
    for (int run = 0; run < 10; run++) {
      Map<String, Object> many = ConstantPoolProvider.crossref(concatenated, TYPE);
      String ctx = "run " + run + ": " + many + "\nsingle chunk: " + one;
      for (String key : new String[] {"cpTotal", "cpReferenced", "cpUnused"}) {
        assertEquals(COPIES * (Long) one.get(key), many.get(key), key + "; " + ctx);
      }
      for (String key :
          new String[] {"eventCounts", "totalRefsByEventType", "uniqueRefsByEventType"}) {
        @SuppressWarnings("unchecked")
        Map<String, Long> perType = (Map<String, Long>) one.get(key);
        @SuppressWarnings("unchecked")
        Map<String, Long> perTypeMany = (Map<String, Long>) many.get(key);
        assertTrue(!perType.isEmpty(), key + " is empty; " + ctx);
        assertEquals(perType.keySet(), perTypeMany.keySet(), key + "; " + ctx);
        perType.forEach(
            (type, n) -> assertEquals(COPIES * n, perTypeMany.get(type), key + "/" + type));
      }
      runs.add(many.get("cpTotal") + "/" + many.get("cpReferenced"));
    }
    assertEquals(1, runs.stream().distinct().count(), "runs differ: " + runs);
  }

  /** The {@value #TYPE} pool's size per checkpoint (summed) and at chunk end, over all chunks. */
  private record PoolSizes(long summedPerCheckpoint, long atChunkEnd) {}

  private static PoolSizes poolSizes(Path jfr) throws Exception {
    AtomicLong perCheckpoint = new AtomicLong();
    AtomicLong atEnd = new AtomicLong();
    try (StreamingChunkParser p = new StreamingChunkParser(new UntypedParserContextFactory())) {
      p.parse(
          jfr,
          new ChunkParserListener() {
            @Override
            public boolean onCheckpoint(ParserContext c, CheckpointEvent event) {
              perCheckpoint.addAndGet(size(c));
              return true;
            }

            @Override
            public boolean onChunkEnd(ParserContext c, int chunkIndex, boolean skipped) {
              atEnd.addAndGet(size(c));
              return true;
            }
          });
    }
    return new PoolSizes(perCheckpoint.get(), atEnd.get());
  }

  private static long size(ParserContext c) {
    return c.getConstantPools()
        .pools()
        .filter(cp -> TYPE.equals(cp.getType().getName()))
        .mapToLong(ConstantPool::entryCount)
        .sum();
  }
}

package io.jafar.parser.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.TimeRange;
import org.junit.jupiter.api.Test;

/** The chunk-skipping rules of {@link ChunkPruning#canHoldMatch}, on numbers alone. */
class ChunkPruningTest {
  private static final long SECOND = 1_000_000_000L;
  private static final long MARGIN = ChunkPruning.MARGIN_NANOS;

  /** A chunk spanning [100 s, 110 s]. */
  private static final long START = 100 * SECOND;

  private static final long DURATION = 10 * SECOND;
  private static final long END = START + DURATION;

  private static boolean holds(TimeRange range) {
    return ChunkPruning.canHoldMatch(range, START, DURATION);
  }

  @Test
  void chunkEndingBeforeTheRangeIsSkipped() {
    assertFalse(holds(TimeRange.since(END + MARGIN + 1)));
    assertFalse(holds(TimeRange.between(END + MARGIN + 1, END + 60 * SECOND)));
  }

  @Test
  void lowerBoundKeepsAMarginAfterTheChunkEnd() {
    assertTrue(holds(TimeRange.since(END + MARGIN)));
    assertTrue(holds(TimeRange.since(END)));
    assertTrue(holds(TimeRange.since(START)));
  }

  /**
   * An event is written when it is committed, with whatever start time its writer gives it: one
   * that starts in the range can sit in a chunk that starts long after the range ends.
   */
  @Test
  void chunkStartingAfterTheRangeIsNeverSkipped() {
    assertTrue(holds(TimeRange.until(START - MARGIN - 1)));
    assertTrue(holds(TimeRange.until(0)));
    assertTrue(holds(TimeRange.between(0, START - 3600 * SECOND)));
  }

  @Test
  void rangeOverlappingTheChunkIsRead() {
    assertTrue(holds(TimeRange.between(START + SECOND, START + 2 * SECOND)));
    assertTrue(holds(TimeRange.all()));
  }

  @Test
  void unfinishedChunkIsAlwaysRead() {
    // a chunk that was never finished carries no duration; its span is unknown
    assertTrue(ChunkPruning.canHoldMatch(TimeRange.since(Long.MAX_VALUE - 1), START, 0));
    assertTrue(ChunkPruning.canHoldMatch(TimeRange.since(Long.MAX_VALUE - 1), START, -5));
  }

  @Test
  void extremeHeaderValuesDoNotOverflowIntoASkip() {
    // start + duration overflows a long; saturated, the chunk still "ends" after any lower bound
    assertTrue(
        ChunkPruning.canHoldMatch(
            TimeRange.since(Long.MAX_VALUE - 5), Long.MAX_VALUE - 10, Long.MAX_VALUE));
  }
}

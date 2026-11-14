package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.internal_api.StreamingChunkParser;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The session's own scans take their chunk thread count from the session's {@link ParsingContext}.
 * They used to build their chunk parser with the default pool whatever the context said, so a
 * session opened with {@code withParallelism(1)} still scanned one chunk per thread.
 *
 * <p>A scan has no handler to observe, so the test counts the threads the JVM starts while one
 * runs: each scan creates its own pool, and a fixed pool starts one thread per submitted chunk up
 * to its size.
 */
class JFRSessionParallelismTest {
  /** Checked into the repository (two chunks), so this needs no download. */
  private static final Path TWO_CHUNKS =
      Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr")
          .toAbsolutePath()
          .normalize();

  private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

  /**
   * Threads started by one metadata scan ({@code refreshTypes()} rescans every time, unlike the
   * cached chunk-id scan): the smallest count over several scans, so that an unrelated thread the
   * JVM happens to start during one of them does not inflate the answer.
   */
  private static long threadsStartedByOneScan(JFRSession session) throws Exception {
    assertEquals(List.of(1, 2), session.getAvailableChunkIds(), "chunks in fixture");
    long fewest = Long.MAX_VALUE;
    for (int i = 0; i < 5; i++) {
      long before = THREADS.getTotalStartedThreadCount();
      session.refreshTypes();
      fewest = Math.min(fewest, THREADS.getTotalStartedThreadCount() - before);
    }
    return fewest;
  }

  @Test
  void scansUseTheContextsParallelism() throws Exception {
    assumeTrue(Files.exists(TWO_CHUNKS), "fixture missing: " + TWO_CHUNKS);
    // With a default pool of one thread the two cases below cannot be told apart.
    assumeTrue(StreamingChunkParser.defaultParallelism() > 1, "needs a default pool above one");

    try (JFRSession sequential =
        new JFRSession(TWO_CHUNKS, ParsingContext.create().withParallelism(1))) {
      assertEquals(1, threadsStartedByOneScan(sequential), "withParallelism(1): one scan thread");
    }
    try (JFRSession parallel = new JFRSession(TWO_CHUNKS, ParsingContext.create())) {
      assertEquals(2, threadsStartedByOneScan(parallel), "default: one scan thread per chunk");
    }
  }
}

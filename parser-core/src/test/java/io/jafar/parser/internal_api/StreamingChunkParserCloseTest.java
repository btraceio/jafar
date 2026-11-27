package io.jafar.parser.internal_api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.impl.UntypedParserContextFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #279: close() shut the chunk parsing pool down without waiting, so its workers, and their
 * thread-locals holding the parse's data and through it the mapped file, could outlive the call. A
 * collection requested right after close (jfr-mcp's jfr_close) then missed the file.
 */
class StreamingChunkParserCloseTest {

  /** How long chunk 2 keeps its worker busy after the parse has failed. */
  private static final long HOLD_MILLIS = 1_000;

  @TempDir Path dir;

  @Test
  void closeReturnsOnlyOnceTheWorkersHaveExited() throws Exception {
    // A failed parse returns while the other chunks' tasks still run: they are cancelled, and a
    // task that does not stop on the interrupt runs on. close() is called with one running.
    Set<Thread> workers = ConcurrentHashMap.newKeySet();
    StreamingChunkParser parser = failedParseWithAChunkStillRunning(workers, HOLD_MILLIS);

    parser.close();

    assertEquals(List.of(), workers.stream().filter(Thread::isAlive).map(Thread::getName).toList());
    assertTrue(
        workers.stream()
            .allMatch(t -> t.getName().startsWith(StreamingChunkParser.WORKER_THREAD_PREFIX)),
        String.valueOf(workers));
  }

  @Test
  void anInterruptedCloseReturnsAndKeepsTheInterrupt() throws Exception {
    Set<Thread> workers = ConcurrentHashMap.newKeySet();
    StreamingChunkParser parser = failedParseWithAChunkStillRunning(workers, 3 * HOLD_MILLIS);
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    AtomicBoolean interruptKept = new AtomicBoolean();
    Thread closer =
        new Thread(
            () -> {
              try {
                parser.close();
              } catch (Throwable t) {
                thrown.set(t);
              }
              interruptKept.set(Thread.currentThread().isInterrupted());
            });
    closer.start();
    Thread.sleep(100);
    long interrupted = System.nanoTime();
    closer.interrupt();
    closer.join(10_000);

    assertNull(thrown.get(), "close() threw");
    assertTrue(interruptKept.get(), "the closing thread's interrupt status was lost");
    assertTrue(
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - interrupted) < HOLD_MILLIS,
        "close() waited on after the interrupt");
  }

  @Test
  void aWorkerClosingItsParserDoesNotWaitForItself() throws Exception {
    AtomicReference<StreamingChunkParser> parser = new AtomicReference<>();
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    parser.set(new StreamingChunkParser(new UntypedParserContextFactory(), 4));
    long start = System.nanoTime();
    parser
        .get()
        .parse(
            Paths.get("src/test/resources/test-ap.jfr"),
            new ChunkParserListener() {
              @Override
              public boolean onChunkStart(
                  ParserContext context, int chunkIndex, ChunkHeader header) {
                try {
                  parser.get().close();
                } catch (Throwable t) {
                  thrown.set(t);
                }
                return false;
              }
            });

    assertNull(thrown.get(), "close() threw");
    long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertTrue(millis < 2_000, "close() from a worker took " + millis + " ms");
  }

  /**
   * A parser whose parse of two chunks failed while chunk 2's task still runs, for {@code
   * holdMillis}, not stopping on an interrupt (the parse cancels it).
   */
  private StreamingChunkParser failedParseWithAChunkStillRunning(
      Set<Thread> workers, long holdMillis) throws Exception {
    byte[] chunk = Files.readAllBytes(Paths.get("src/test/resources/test-ap.jfr"));
    byte[] two = new byte[chunk.length * 2];
    System.arraycopy(chunk, 0, two, 0, chunk.length);
    System.arraycopy(chunk, 0, two, chunk.length, chunk.length);
    Path recording = Files.write(dir.resolve("two-chunks.jfr"), two);

    CountDownLatch secondStarted = new CountDownLatch(1);
    ChunkParserListener listener =
        new ChunkParserListener() {
          @Override
          public boolean onChunkStart(ParserContext context, int chunkIndex, ChunkHeader header) {
            workers.add(Thread.currentThread());
            if (chunkIndex == 1) {
              awaitUninterruptibly(secondStarted);
              throw new IllegalStateException("chunk 1 fails");
            }
            secondStarted.countDown();
            holdUninterruptibly(holdMillis);
            return false;
          }
        };

    StreamingChunkParser parser = new StreamingChunkParser(new UntypedParserContextFactory(), 4);
    assertThrows(IOException.class, () -> parser.parse(recording, listener));
    assertEquals(2, workers.size(), "the chunks ran on two workers: " + workers);
    return parser;
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (latch.getCount() > 0 && System.nanoTime() < deadline) {
      try {
        latch.await(10, TimeUnit.MILLISECONDS);
      } catch (InterruptedException ignored) {
        // keep waiting
      }
    }
  }

  private static void holdUninterruptibly(long millis) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    while (System.nanoTime() < deadline) {
      try {
        Thread.sleep(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
      } catch (InterruptedException ignored) {
        // the parse's cancel(true); hold on
      }
    }
  }
}

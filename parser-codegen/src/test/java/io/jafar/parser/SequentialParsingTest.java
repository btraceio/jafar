package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.Control;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.types.JFRExecutionSample;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ParsingContext#withParallelism(int) withParallelism(1)}: one parsing thread, chunks in
 * file order, events in file order within a chunk. Runs against {@code test-ap.jfr} concatenated
 * into three chunks, where the default pool starts one thread per chunk on any machine whose
 * default parallelism is above one.
 *
 * <p>The handlers deliberately collect into plain, unsynchronized collections: that is the use the
 * sequential mode exists for.
 */
class SequentialParsingTest {
  @TempDir Path tempDir;

  /** What a handler saw, in the order it saw it. Not thread-safe on purpose. */
  private static final class Trace {
    final List<Long> chunkIds = new ArrayList<>();
    final List<Long> positions = new ArrayList<>();
    final Set<Thread> threads = new HashSet<>();

    void record(Control ctl) {
      chunkIds.add(ctl.chunkInfo().chunkId());
      positions.add(ctl.stream().position());
      threads.add(Thread.currentThread());
    }

    void assertSequential(int expectedEvents) {
      assertEquals(expectedEvents, chunkIds.size(), "events delivered");
      assertEquals(1, threads.size(), "parsing threads: " + threads);
      Set<Long> distinctChunks = new HashSet<>(chunkIds);
      assertEquals(3, distinctChunks.size(), "chunks seen: " + distinctChunks);
      // chunkId() comes from a process-wide counter taken when a chunk starts; with one parsing
      // thread chunks start in file order, so the ids must never decrease.
      for (int i = 1; i < chunkIds.size(); i++) {
        long previous = chunkIds.get(i - 1);
        long current = chunkIds.get(i);
        assertTrue(
            current >= previous,
            "chunk " + current + " delivered after chunk " + previous + " at event " + i);
        if (current == previous) {
          assertTrue(
              positions.get(i) > positions.get(i - 1),
              "event " + i + " of chunk " + current + " delivered out of file order");
        }
      }
    }
  }

  private Path multiChunk() {
    return AbortProbe.fixtures(tempDir).get(1);
  }

  @Test
  void untypedEventsArriveInFileOrderOnOneThread() throws Exception {
    Path recording = multiChunk();
    AtomicInteger expected = new AtomicInteger();
    try (UntypedJafarParser p = UntypedJafarParser.open(recording)) {
      p.handle((type, value, ctl) -> expected.incrementAndGet());
      p.run();
    }

    Trace trace = new Trace();
    ParsingContext sequential = ParsingContext.create().withParallelism(1);
    try (UntypedJafarParser p = sequential.newUntypedParser(recording)) {
      p.handle((type, value, ctl) -> trace.record(ctl));
      p.run();
    }
    trace.assertSequential(expected.get());
  }

  @Test
  void typedEventsArriveInFileOrderOnOneThread() throws Exception {
    Path recording = multiChunk();
    AtomicInteger expected = new AtomicInteger();
    try (TypedJafarParser p = TypedJafarParser.open(recording.toString())) {
      p.handle(JFRExecutionSample.class, (e, ctl) -> expected.incrementAndGet());
      p.run();
    }

    Trace trace = new Trace();
    ParsingContext sequential = ParsingContext.create().withParallelism(1);
    try (TypedJafarParser p = sequential.newTypedParser(recording)) {
      p.handle(JFRExecutionSample.class, (e, ctl) -> trace.record(ctl));
      p.run();
    }
    trace.assertSequential(expected.get());
  }

  @Test
  void parallelismMustBePositive() {
    ParsingContext ctx = ParsingContext.create();
    assertThrows(IllegalArgumentException.class, () -> ctx.withParallelism(0));
    assertThrows(IllegalArgumentException.class, () -> ctx.withParallelism(-1));
  }
}

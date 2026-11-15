package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ConstantPools;
import io.jafar.parser.api.HandlerFactory;
import io.jafar.parser.api.MetadataLookup;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.RecordingStream;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.types.JFRGCHeapSummary;
import io.jafar.testsupport.ProcessorCount;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A typed parse must resolve a constant-pool field of a chunk whatever another chunk of the same
 * recording declares.
 *
 * <p>{@code tck-test.jfr} has two chunks from two producers: chunk 1 declares {@code
 * jdk.GCHeapSummary} and the {@code jdk.types.GCWhen} its {@code when} field points at, chunk 2
 * declares neither. The typed parser limits the constant pools it indexes to the types reachable
 * from the registered handlers, and computes that set from a chunk's metadata. It used to keep the
 * set in one field shared by the chunk threads of a parse, written by whichever thread found it
 * unset: when both threads found it unset, chunk 2's set (without {@code jdk.types.GCWhen}) could
 * replace chunk 1's before chunk 1 indexed its pools, and all 1,892 {@code when()} values of the
 * parse came back {@code null}, with no error.
 *
 * <p><b>How the race is provoked.</b> Nothing a test can reach runs between the parser's check and
 * its write, so the window cannot be forced open, only approached. A handler factory registered on
 * the parser is bound under a per-parser lock at the start of the first chunk's metadata callback,
 * just before the set is computed. The test holds chunk 2 back until chunk 1 is inside {@code
 * bind}, and keeps chunk 1 there until chunk 2 is blocked on that lock, so both threads reach the
 * computation within microseconds of each other, {@value #ATTEMPTS} times. Against the parser
 * before the fix this failed in every one of 30 runs; a passing run is therefore evidence, not
 * proof, and {@code SharedParsingContextStressTest} remains the wider net.
 */
class TypedTypeFilterChunkRaceTest {

  private static final Path RECORDING =
      Paths.get("../jfr-shell-tck/src/main/resources/tck-test.jfr");

  /** {@code jdk.GCHeapSummary} events in the recording, all of them in chunk 1. */
  private static final int GC_HEAP_SUMMARIES = 1_892;

  /** Parses per test run, each lined up as described on the class. */
  private static final int ATTEMPTS = 60;

  /** A type no recording declares: the factory exists only for its {@code bind} callback. */
  interface NeverDecoded {}

  @BeforeAll
  static void processorCountMatrixFlagArrived() {
    ProcessorCount.assertExpected();
  }

  @Test
  @Timeout(value = 5, unit = TimeUnit.MINUTES) // a hang guard only, never a timing assertion
  void constantPoolFieldResolvesWhenAnotherChunkDoesNotDeclareItsType() throws Exception {
    // One chunk thread: chunks are parsed one after another, so nothing can interleave.
    List<String> reference = whenValues(ParsingContext.create().withParallelism(1), null);
    assertEquals(GC_HEAP_SUMMARIES, reference.size(), "event count of " + RECORDING + " changed");
    assertTrue(
        !reference.contains(null),
        "the sequential reference itself has unresolved when() values: " + reference.indexOf(null));

    int linedUp = 0;
    for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
      LineUp lineUp = new LineUp();
      List<String> actual = whenValues(ParsingContext.create().withParallelism(2), lineUp);
      assertEquals(
          GC_HEAP_SUMMARIES, actual.size(), "attempt " + attempt + ": events delivered changed");
      int firstDifference = firstDifference(reference, actual);
      if (firstDifference >= 0) {
        int unresolved = 0;
        for (String when : actual) {
          if (when == null) {
            unresolved++;
          }
        }
        assertEquals(
            reference.get(firstDifference),
            actual.get(firstDifference),
            "attempt "
                + attempt
                + ": when() of jdk.GCHeapSummary event "
                + firstDifference
                + " differs from the sequential parse ("
                + unresolved
                + " of "
                + actual.size()
                + " values are null)");
      }
      if (lineUp.linedUp) {
        linedUp++;
      }
    }
    System.out.println("typed type filter race: lined up " + linedUp + " of " + ATTEMPTS);
    // R3/R6: without the line-up the loop above is 60 ordinary parses and proves little. If this
    // fails, the parser no longer binds factories the way the class comment describes; find
    // another way to bring the two chunk threads together rather than deleting the assertion.
    assertEquals(
        ATTEMPTS, linedUp, "parses in which both chunk threads were brought to the same point");
  }

  /** One typed parse: the {@code when()} of every {@code jdk.GCHeapSummary}, in delivery order. */
  private static List<String> whenValues(ParsingContext context, LineUp lineUp) throws Exception {
    // Only chunk 1 has these events and a chunk is delivered by one thread; synchronized anyway,
    // so a recording that gains them in a second chunk cannot corrupt the list.
    List<String> values = new ArrayList<>();
    AtomicInteger delivered = new AtomicInteger();
    TypedJafarParser opened = context.newTypedParser(RECORDING);
    try (TypedJafarParser parser = lineUp == null ? opened : opened.withParserListener(lineUp)) {
      if (lineUp != null) {
        parser.registerFactory(lineUp);
      }
      parser.handle(
          JFRGCHeapSummary.class,
          (event, ctl) -> {
            String when = event.when();
            synchronized (values) {
              values.add(when);
            }
            delivered.incrementAndGet();
          });
      parser.run();
    } finally {
      opened.close();
    }
    assertEquals(values.size(), delivered.get());
    return values;
  }

  private static int firstDifference(List<String> expected, List<String> actual) {
    for (int i = 0; i < expected.size(); i++) {
      if (!expected.get(i).equals(actual.get(i))) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Brings the two chunk threads of one parse to the parser's type-filter computation together; see
   * the class comment. It is a listener (to hold chunk 2 back and to learn the chunk threads) and a
   * handler factory (for the {@code bind} callback) in one, used for a single parse.
   */
  private static final class LineUp implements ChunkParserListener, HandlerFactory<NeverDecoded> {
    private static final long LIMIT_NANOS = TimeUnit.SECONDS.toNanos(20);

    private final CountDownLatch chunk1Binding = new CountDownLatch(1);
    private volatile Thread chunk1Thread;
    private volatile Thread chunk2Thread;

    /** Whether chunk 2 was seen blocked while chunk 1 was in {@code bind}. */
    volatile boolean linedUp;

    @Override
    public boolean onChunkStart(ParserContext context, int chunkIndex, ChunkHeader header) {
      if (chunkIndex == 1) {
        chunk1Thread = Thread.currentThread();
      } else if (chunkIndex == 2) {
        chunk2Thread = Thread.currentThread();
        try {
          // Hold chunk 2 until chunk 1 is binding; on a timeout go on, linedUp stays false.
          chunk1Binding.await(LIMIT_NANOS, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      return true;
    }

    @Override
    public void bind(MetadataLookup metadata) {
      if (Thread.currentThread() != chunk1Thread) {
        return; // not the arrangement this class sets up; linedUp stays false
      }
      chunk1Binding.countDown();
      long deadline = System.nanoTime() + LIMIT_NANOS;
      while (System.nanoTime() < deadline) {
        Thread other = chunk2Thread;
        if (other != null && other.getState() == Thread.State.BLOCKED) {
          linedUp = true;
          return;
        }
        Thread.onSpinWait();
      }
    }

    @Override
    public NeverDecoded get(RecordingStream stream, MetadataClass metadata, ConstantPools pools) {
      throw new AssertionError("no recording declares " + getJfrTypeName());
    }

    @Override
    public String getJfrTypeName() {
      return "test.TypedTypeFilterChunkRaceTest.NeverDecoded";
    }

    @Override
    public Class<NeverDecoded> getInterfaceClass() {
      return NeverDecoded.class;
    }
  }
}

package io.jafar.parser.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.TypeFilter;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import io.jafar.parser.types.JFRGCHeapSummary;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Every chunk of a typed parse filters its constant pools by the types its own metadata says the
 * registered handlers reach.
 *
 * <p>{@code tck-test.jfr} has two chunks, and only the first declares {@code jdk.GCHeapSummary} and
 * the {@code jdk.types.GCWhen} its {@code when} field refers to. The typed parser used to keep the
 * set of referenced types in one field shared by the chunk threads, computed by whichever chunk
 * read its metadata first, and each chunk's filter read that field instead of a set of its own. Two
 * things followed. Only the chunk that computed the set got a filter at all. And when both chunks
 * read their metadata at the same moment, the second chunk's set (which has no {@code GCWhen})
 * could replace the first's while the first was still loading its constant pools: its {@code
 * GCWhen} pool was skipped, and {@code when()} returned {@code null} for all 1,892 events, with no
 * error. That is the intermittent failure of {@code SharedParsingContextStressTest}; in 150 cold
 * JVMs under CPU load it happened 5 times.
 *
 * <p>The race itself cannot be forced from a test, so this pins what made it possible: each chunk
 * has a filter, and after the whole parse that filter answers from its own chunk's metadata and not
 * from another chunk's.
 */
class TypedChunkTypeFilterTest {
  private static final Path RECORDING =
      Paths.get("../jfr-shell-tck/src/main/resources/tck-test.jfr");

  private static final String EVENT = "jdk.GCHeapSummary";
  private static final String REFERENCED = "jdk.types.GCWhen";
  private static final String UNRELATED = "jdk.types.StackTrace";

  /** What the listener saw of one chunk: its context and the three types, where declared. */
  private static final class Chunk {
    TypedParserContext context;
    MetadataClass event;
    MetadataClass referenced;
    MetadataClass unrelated;
  }

  @Test
  void everyChunkFiltersByItsOwnMetadata() throws Exception {
    Map<Integer, Chunk> chunks = new ConcurrentHashMap<>();
    ThreadLocal<Chunk> current = new ThreadLocal<>();
    ChunkParserListener listener =
        new ChunkParserListener() {
          @Override
          public boolean onChunkStart(ParserContext context, int chunkIndex, ChunkHeader header) {
            Chunk chunk = new Chunk();
            chunk.context = (TypedParserContext) context;
            chunks.put(chunkIndex, chunk);
            current.set(chunk);
            return true;
          }

          @Override
          public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
            Chunk chunk = current.get();
            chunk.event = context.getMetadataLookup().getClass(EVENT);
            chunk.referenced = context.getMetadataLookup().getClass(REFERENCED);
            chunk.unrelated = context.getMetadataLookup().getClass(UNRELATED);
            return true;
          }
        };

    AtomicInteger events = new AtomicInteger();
    AtomicInteger withoutWhen = new AtomicInteger();
    TypedJafarParser parser =
        ParsingContext.create().newTypedParser(RECORDING).withParserListener(listener);
    parser.handle(
        JFRGCHeapSummary.class,
        (event, ctl) -> {
          events.incrementAndGet();
          if (event.when() == null) {
            withoutWhen.incrementAndGet();
          }
        });
    try {
      parser.run();
    } finally {
      parser.close();
    }

    // The premise: more than one chunk, and they do not declare the same types.
    assertTrue(
        chunks.size() > 1, "expected a multi-chunk recording, got chunks " + chunks.keySet());
    int declaring = 0;
    for (Integer index : new TreeSet<>(chunks.keySet())) {
      Chunk chunk = chunks.get(index);
      // Read after the whole parse: whatever another chunk did must not have changed the answer.
      TypeFilter filter = chunk.context.getTypeFilter();
      assertNotNull(filter, "chunk " + index + " has no type filter");
      if (chunk.event != null) {
        declaring++;
        assertNotNull(chunk.referenced, "chunk " + index + " declares the event but not its type");
        assertTrue(filter.test(chunk.event), "chunk " + index + " filters out " + EVENT);
        assertTrue(filter.test(chunk.referenced), "chunk " + index + " filters out " + REFERENCED);
      }
      if (chunk.unrelated != null) {
        assertFalse(filter.test(chunk.unrelated), "chunk " + index + " lets " + UNRELATED + " in");
      }
    }
    // A chunk that does not declare the event reaches nothing through it, so its filter must not
    // let in a type that only another chunk's closure holds. With one shared set it did.
    for (Integer index : new TreeSet<>(chunks.keySet())) {
      Chunk chunk = chunks.get(index);
      if (chunk.event != null) continue;
      for (Chunk other : chunks.values()) {
        if (other.referenced != null) {
          assertFalse(
              chunk.context.getTypeFilter().test(other.referenced),
              "chunk " + index + " answers from another chunk's referenced types");
        }
      }
    }
    assertTrue(
        declaring > 0 && declaring < chunks.size(),
        "expected some chunks, not all, to declare " + EVENT + ": " + declaring);

    assertEquals(1_892, events.get());
    assertEquals(0, withoutWhen.get(), "events whose when() could not be resolved");
  }
}

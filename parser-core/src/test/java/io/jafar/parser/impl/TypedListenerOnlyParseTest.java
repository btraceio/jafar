package io.jafar.parser.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.ChunkParserListener;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;

/**
 * A typed parser with a parser listener and no registered handler parses the recording for the
 * listener.
 *
 * <p>It used to fail on the first event. The chunk was set up (its class map among other things)
 * only when a handler was registered, but with a listener the chunk was parsed all the same, and
 * {@code onEvent} read the class map without checking: {@code IOException: Error occurred while
 * parsing JFR recording: Cannot invoke "LongObjectHashMap.get(long)" because "typeClassMap" is
 * null}.
 */
class TypedListenerOnlyParseTest {
  /** Real recording, checked in: 67,657 events in 2 chunks (pinned by the shared-context test). */
  private static final Path RECORDING =
      Paths.get("../jfr-shell-tck/src/main/resources/tck-test.jfr");

  @Test
  void listenerSeesEveryChunkAndEvent() throws Exception {
    Set<Integer> started = ConcurrentHashMap.newKeySet();
    Set<Integer> ended = ConcurrentHashMap.newKeySet();
    LongAdder events = new LongAdder();
    ChunkParserListener listener =
        new ChunkParserListener() {
          @Override
          public boolean onChunkStart(ParserContext context, int chunkIndex, ChunkHeader header) {
            started.add(chunkIndex);
            return true;
          }

          @Override
          public boolean onEvent(
              ParserContext context,
              long typeId,
              long eventStartPos,
              long rawSize,
              long payloadSize) {
            events.increment();
            return true;
          }

          @Override
          public boolean onChunkEnd(ParserContext context, int chunkIndex, boolean skipped) {
            if (!skipped) {
              ended.add(chunkIndex);
            }
            return true;
          }
        };

    try (TypedJafarParser parser =
        TypedJafarParser.open(RECORDING.toString()).withParserListener(listener)) {
      parser.run();
    }

    assertEquals(2, started.size(), "chunks started: " + started);
    assertEquals(started, ended, "chunks parsed to the end");
    assertEquals(67_657, events.sum());
  }
}

package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.Control;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedStrategy;
import io.jafar.parser.impl.EventStream;
import io.jafar.parser.impl.EventStreamGenerated;
import io.jafar.parser.impl.ParsingContextImpl;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The generated untyped deserializers must load and run on JDK 8: they were once emitted as Java 11
 * class files ({@code UnsupportedClassVersionError} here). Full value parity is checked on the main
 * JDK by {@code GeneratedUntypedParityTest}; this only checks that every event arrives.
 */
public class CoreUntypedGeneratedSmokeTest {
  private static final Path RECORDING =
      Paths.get("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr");

  @Test
  void generatedUntypedPathRunsOnThisJdk() throws Exception {
    assertTrue(Files.isRegularFile(RECORDING), "missing " + RECORDING.toAbsolutePath());
    final AtomicLong generic = new AtomicLong();
    parse(
        UntypedStrategy.SPARSE_ACCESS,
        new EventStream(null) {
          @Override
          protected void onEventValue(MetadataClass type, Map<String, Object> value, Control c) {
            generic.incrementAndGet();
          }
        });
    assertTrue(generic.get() > 0, "generic path delivered no events");

    for (UntypedStrategy strategy : UntypedStrategy.values()) {
      final AtomicLong generated = new AtomicLong();
      parse(
          strategy,
          new EventStreamGenerated(null) {
            @Override
            protected void onEventValue(MetadataClass type, Map<String, Object> value, Control c) {
              generated.incrementAndGet();
            }
          });
      assertEquals(generic.get(), generated.get(), strategy.name());
    }
  }

  private static void parse(UntypedStrategy strategy, ChunkParserListener listener)
      throws Exception {
    ParsingContextImpl ctx = (ParsingContextImpl) ParsingContext.create();
    try (StreamingChunkParser parser =
        new StreamingChunkParser(ctx.untypedContextFactory(strategy))) {
      parser.parse(RECORDING, listener);
    }
  }
}

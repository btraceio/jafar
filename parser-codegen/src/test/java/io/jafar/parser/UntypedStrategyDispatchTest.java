package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.UntypedStrategy;
import io.jafar.parser.impl.UntypedJafarParserImpl;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.UntypedDeserializerCache;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Issue 124: the strategy given to the public untyped parser reaches the generated reader.
 *
 * <p>Which reader ran is observed, not assumed: only the generated reader fills the shared {@link
 * UntypedDeserializerCache}; the generic reader never touches it.
 */
class UntypedStrategyDispatchTest {
  private static final Path RECORDING =
      Paths.get("../jfr-shell-tck/src/main/resources/tck-test.jfr");

  private record Run(Map<String, Long> countsByType, int cachedDeserializers) {}

  private static Run run(UntypedStrategy strategy) throws Exception {
    Map<String, LongAdder> counts = new ConcurrentHashMap<>(); // chunks parse on parallel threads
    AtomicInteger cached = new AtomicInteger();
    ChunkParserListener probe =
        new ChunkParserListener() {
          @Override
          public boolean onChunkEnd(ParserContext context, int chunkIndex, boolean skipped) {
            UntypedDeserializerCache cache = context.get(UntypedDeserializerCache.class);
            if (cache != null) {
              cached.accumulateAndGet(cache.size(), Math::max);
            }
            return true;
          }
        };
    try (UntypedJafarParser p =
        ((UntypedJafarParserImpl) ParsingContext.create().newUntypedParser(RECORDING, strategy))
            .withParserListener(probe)) {
      p.handle(
          (type, event, ctl) ->
              counts.computeIfAbsent(type.getName(), k -> new LongAdder()).increment());
      p.run();
    }
    Map<String, Long> sorted = new TreeMap<>();
    counts.forEach((k, v) -> sorted.put(k, v.sum()));
    return new Run(sorted, cached.get());
  }

  @ParameterizedTest
  @EnumSource(UntypedStrategy.class)
  void everyStrategyReadsWithTheGeneratedReader(UntypedStrategy strategy) throws Exception {
    assertTrue(
        ((UntypedJafarParserImpl) ParsingContext.create().newUntypedParser(RECORDING, strategy))
            .usesGeneratedReader());

    Run run = run(strategy);

    assertFalse(run.countsByType().isEmpty());
    assertTrue(run.cachedDeserializers() > 0, "no generated deserializer was used");
  }

  @Test
  void defaultStrategyReadsWithTheGeneratedReader() throws Exception {
    assertTrue(
        ((UntypedJafarParserImpl) ParsingContext.create().newUntypedParser(RECORDING))
            .usesGeneratedReader());
  }

  @Test
  void propertyForcesTheGenericReaderAndBothDeliverTheSameEvents() throws Exception {
    Run generated = run(UntypedStrategy.FULL_ITERATION);
    assertTrue(generated.cachedDeserializers() > 0, "baseline run must be generated");
    String old = System.getProperty(UntypedJafarParserImpl.GENERATED_PROPERTY);
    System.setProperty(UntypedJafarParserImpl.GENERATED_PROPERTY, "false");
    try {
      assertFalse(
          ((UntypedJafarParserImpl)
                  ParsingContext.create()
                      .newUntypedParser(RECORDING, UntypedStrategy.FULL_ITERATION))
              .usesGeneratedReader());
      Run generic = run(UntypedStrategy.FULL_ITERATION);

      assertEquals(0, generic.cachedDeserializers(), "generic reader must not use the cache");
      assertEquals(generic.countsByType(), generated.countsByType());
    } finally {
      if (old == null) {
        System.clearProperty(UntypedJafarParserImpl.GENERATED_PROPERTY);
      } else {
        System.setProperty(UntypedJafarParserImpl.GENERATED_PROPERTY, old);
      }
    }
  }
}

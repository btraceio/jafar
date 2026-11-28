package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.impl.ParsingContextImpl;
import io.jafar.parser.types.JFRGCHeapSummary;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code ParsingContextImpl.EMPTY} is a context like any other for typed parsing.
 *
 * <p>It was declared above the static {@code DeserializerFactory} lookup of its own class. Static
 * initializers run in textual order, so its typed factory was built with a {@code null}
 * deserializer factory, every type bound {@code Deserializer.none()}, and a typed parse through it
 * returned normally having delivered nothing.
 */
class EmptyContextTypedParseTest {
  private static final Path RECORDING =
      Paths.get("../jfr-shell-tck/src/main/resources/tck-test.jfr");

  /** The {@code jdk.GCHeapSummary} events of {@code tck-test.jfr}. */
  private static final long GC_HEAP_SUMMARIES = 1_892;

  /** Every event rendered from all its getters, sorted: chunks are parsed in parallel. */
  private static List<String> events(ParsingContext context) throws Exception {
    List<String> seen = Collections.synchronizedList(new ArrayList<>());
    try (TypedJafarParser parser = context.newTypedParser(RECORDING)) {
      HandlerRegistration<JFRGCHeapSummary> registration =
          parser.handle(
              JFRGCHeapSummary.class,
              (event, ctl) ->
                  seen.add(
                      event.startTime()
                          + " gcId="
                          + event.gcId()
                          + " when="
                          + event.when()
                          + " heapUsed="
                          + event.heapUsed()));
      parser.run();
      registration.close();
    }
    List<String> sorted = new ArrayList<>(seen);
    Collections.sort(sorted);
    return sorted;
  }

  @Test
  void theEmptyContextDecodesWhatACreatedContextDecodes() throws Exception {
    List<String> expected = events(ParsingContext.create());
    assertEquals(GC_HEAP_SUMMARIES, expected.size());
    for (String line : expected) {
      assertFalse(line.contains("when=null"), line);
    }

    assertEquals(expected, events(ParsingContextImpl.EMPTY));
  }
}

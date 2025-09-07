package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.types.JFRExecutionSample;
import java.io.File;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Integration sanity: ensure typed parsing works with the active ClassDefiner strategy. This
 * exercise triggers code generation and class definition, then parses a small recording to confirm
 * end-to-end wiring.
 */
public class TypedParserDefinerIntegrationTest {

  @Test
  void typedParserParsesWithActiveDefiner() throws Exception {
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    AtomicInteger count = new AtomicInteger();
    try (TypedJafarParser p = TypedJafarParser.open(new File(uri).getAbsolutePath())) {
      HandlerRegistration<JFRExecutionSample> reg =
          p.handle(JFRExecutionSample.class, (e, ctl) -> count.incrementAndGet());
      p.run();
      reg.destroy(p);
    }
    assertTrue(count.get() > 0, "Expected at least one typed event");
  }
}

package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.Control;
import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import java.io.File;
import java.net.URI;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class UntypedJafarParserTest {

  @Test
  void testUntypedParsingCountsEvents() throws Exception {
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    ParsingContext ctx = ParsingContext.create();
    try (UntypedJafarParser p = ctx.newUntypedParser(Paths.get(new File(uri).getAbsolutePath()))) {
      AtomicInteger count = new AtomicInteger();
      HandlerRegistration<?> reg =
          p.handle(
              (t, v, ctl) -> {
                assertNotNull(ctl.chunkInfo());
                assertNotEquals(Control.ChunkInfo.NONE, ctl.chunkInfo());
                count.incrementAndGet();
              });
      p.run();
      reg.destroy(p);
      assertTrue(count.get() > 0, "Expected at least one event");
    }
  }

  @Test
  void testUntypedHandlerExceptionStopsParsing() throws Exception {
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    ParsingContext ctx = ParsingContext.create();
    try (UntypedJafarParser p = ctx.newUntypedParser(Paths.get(new File(uri).getAbsolutePath()))) {
      p.handle(
          (t, v, ctl) -> {
            throw new RuntimeException("boom");
          });
      assertThrows(java.io.IOException.class, p::run);
    }
  }
}

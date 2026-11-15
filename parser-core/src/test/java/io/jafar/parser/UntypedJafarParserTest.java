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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class UntypedJafarParserTest {

  @Test
  void testUntypedParsingCountsEvents() throws Exception {
    URI uri = UntypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    ParsingContext ctx = ParsingContext.create();
    try (UntypedJafarParser p = ctx.newUntypedParser(Paths.get(new File(uri).getAbsolutePath()))) {
      AtomicInteger count = new AtomicInteger();
      HandlerRegistration<?> reg =
          p.handle(
              (t, v, ctl) -> {
                assertNotNull(ctl.chunkInfo());
                assertNotEquals(Control.ChunkInfo.NONE, ctl.chunkInfo());
                Instant i = Instant.ofEpochSecond(0, (long) (v.get("startTime")));
                assertTrue(
                    i.isAfter(ctl.chunkInfo().startTime().minus(1, ChronoUnit.MILLIS)),
                    i + " is not after " + ctl.chunkInfo().startTime());
                // No later than the chunk's end (the recording's own bound, rather than a date
                // that only held for one particular file).
                Instant chunkEnd =
                    ctl.chunkInfo()
                        .startTime()
                        .plus(ctl.chunkInfo().duration())
                        .plus(1, ChronoUnit.MILLIS);
                assertTrue(i.isBefore(chunkEnd), i + " is after the chunk end " + chunkEnd);
                count.incrementAndGet();
                // this is a sanity test only; we can end early
                //                ctl.abort();
              });
      p.run();
      reg.destroy(p);
      assertTrue(count.get() > 0, "Expected at least one event");
    }
  }

  @Test
  void testUntypedHandlerExceptionStopsParsing() throws Exception {
    URI uri = UntypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
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

package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import java.io.File;
import java.net.URI;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class CoreUntypedSmokeTest {
  @Test
  void parsesUntypedCountsEvents() throws Exception {
    URI uri = CoreUntypedSmokeTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    ParsingContext ctx = ParsingContext.create();
    AtomicInteger count = new AtomicInteger();
    try (UntypedJafarParser p = ctx.newUntypedParser(Paths.get(new File(uri).getAbsolutePath()))) {
      HandlerRegistration<?> reg = p.handle((t, m, ctl) -> count.incrementAndGet());
      p.run();
      reg.destroy(p);
    }
    assertTrue(count.get() > 0);
  }
}

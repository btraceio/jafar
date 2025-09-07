package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import java.io.File;
import java.net.URI;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

public class CoreUntypedExceptionTest {
  @Test
  public void untypedHandlerExceptionPropagatesAsIo() throws Exception {
    URI uri = CoreUntypedExceptionTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
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

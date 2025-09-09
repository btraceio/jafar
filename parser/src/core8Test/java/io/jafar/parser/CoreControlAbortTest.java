package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.JfrType;
import io.jafar.parser.api.TypedJafarParser;
import java.io.File;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class CoreControlAbortTest {

  @JfrType("jdk.ExecutionSample")
  public interface JFRExecSampleLite {
    String state();
  }

  @Test
  public void abortStopsDelivery() throws Exception {
    URI uri = CoreControlAbortTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    try (TypedJafarParser p = TypedJafarParser.open(new File(uri).getAbsolutePath())) {
      final AtomicInteger seen = new AtomicInteger();
      final int threshold = 5;
      HandlerRegistration<JFRExecSampleLite> reg =
          p.handle(
              JFRExecSampleLite.class,
              (e, ctl) -> {
                if (seen.incrementAndGet() >= threshold) {
                  ctl.abort();
                }
              });
      p.run();
      reg.destroy(p);
      assertEquals(threshold, seen.get());
    }
  }
}

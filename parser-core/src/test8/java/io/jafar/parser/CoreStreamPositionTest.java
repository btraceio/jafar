package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.JfrType;
import io.jafar.parser.api.TypedJafarParser;
import java.io.File;
import java.net.URI;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

public class CoreStreamPositionTest {

  @JfrType("jdk.ExecutionSample")
  public interface JFRExecSampleLite {
    String state();
  }

  @Test
  public void streamPositionExposed() throws Exception {
    URI uri = CoreStreamPositionTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    try (TypedJafarParser p = TypedJafarParser.open(new File(uri).getAbsolutePath())) {
      AtomicLong last = new AtomicLong(-1);
      HandlerRegistration<JFRExecSampleLite> reg =
          p.handle(
              JFRExecSampleLite.class,
              (e, ctl) -> {
                long pos = ctl.stream().position();
                if (pos > last.get()) {
                  last.set(pos);
                }
              });
      p.run();
      reg.destroy(p);
      assertTrue(last.get() >= 0);
    }
  }
}

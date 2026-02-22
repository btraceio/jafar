package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.JfrType;
import io.jafar.parser.api.TypedJafarParser;
import java.io.File;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class CoreTypedSmokeTest {
  @Test
  void parsesTypedExecutionSample() throws Exception {
    URI uri = CoreTypedSmokeTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    AtomicInteger count = new AtomicInteger();
    try (TypedJafarParser p = TypedJafarParser.open(new File(uri).getAbsolutePath())) {
      HandlerRegistration<JFRExecSampleLite> reg =
          p.handle(JFRExecSampleLite.class, (e, ctl) -> count.incrementAndGet());
      p.run();
      reg.destroy(p);
    }
    assertTrue(count.get() > 0);
  }

  @JfrType("jdk.ExecutionSample")
  public interface JFRExecSampleLite {
    String state();
  }
}

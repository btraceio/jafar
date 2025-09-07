package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.types.JFRExecutionSample;
import java.io.File;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class ControlTest {

  @Test
  void abortStopsFurtherEventDeliveryTyped() throws Exception {
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    try (TypedJafarParser p = TypedJafarParser.open(new File(uri).getAbsolutePath())) {
      AtomicInteger seen = new AtomicInteger();
      final int threshold = 5;

      HandlerRegistration<JFRExecutionSample> reg =
          p.handle(
              JFRExecutionSample.class,
              (e, ctl) -> {
                int n = seen.incrementAndGet();
                if (n >= threshold) {
                  ctl.abort();
                }
              });

      p.run();
      reg.destroy(p);

      // Expect we stopped close to threshold; exact equality is expected because abort happens
      // inside the handler, which should stop further events.
      assertEquals(threshold, seen.get());
    }
  }

  @Test
  void abortStopsFurtherEventDeliveryUntyped() throws Exception {
    URI uri = UntypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    try (UntypedJafarParser p = UntypedJafarParser.open(new File(uri).getAbsolutePath())) {
      AtomicInteger seen = new AtomicInteger();
      final int threshold = 5;

      HandlerRegistration<?> reg =
          p.handle(
              (type, value, ctl) -> {
                int n = seen.incrementAndGet();
                if (n >= threshold) {
                  ctl.abort();
                }
              });

      p.run();
      reg.destroy(p);

      // Expect we stopped close to threshold; exact equality is expected because abort happens
      // inside the handler, which should stop further events.
      assertEquals(threshold, seen.get());
    }
  }

  @Test
  void abortAllowsSubsequentRunsAfterReconfiguringHandlersTyped() throws Exception {
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    try (TypedJafarParser p = TypedJafarParser.open(new File(uri).getAbsolutePath())) {
      AtomicInteger seen = new AtomicInteger();

      // First run: abort early
      final int threshold = 3;
      HandlerRegistration<JFRExecutionSample> r1 =
          p.handle(
              JFRExecutionSample.class,
              (e, ctl) -> {
                if (seen.incrementAndGet() >= threshold) {
                  ctl.abort();
                }
              });
      p.run();
      r1.destroy(p);
      assertEquals(threshold, seen.get());

      // Second run: new handler without abort, should see more events
      HandlerRegistration<JFRExecutionSample> r2 =
          p.handle(JFRExecutionSample.class, (e, ctl) -> seen.incrementAndGet());
      p.run();
      r2.destroy(p);

      assertTrue(seen.get() > threshold, "expected additional events after re-run without abort");
    }
  }

  @Test
  void abortAllowsSubsequentRunsAfterReconfiguringHandlersUntyped() throws Exception {
    URI uri = UntypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    try (UntypedJafarParser p = UntypedJafarParser.open(new File(uri).getAbsolutePath())) {
      AtomicInteger seen = new AtomicInteger();

      // First run: abort early
      final int threshold = 3;
      HandlerRegistration<?> r1 =
          p.handle(
              (type, value, ctl) -> {
                if (seen.incrementAndGet() >= threshold) {
                  ctl.abort();
                }
              });
      p.run();
      r1.destroy(p);
      assertEquals(threshold, seen.get());

      // Second run: new handler without abort, should see more events
      HandlerRegistration<?> r2 = p.handle((type, value, ctl) -> seen.incrementAndGet());
      p.run();
      r2.destroy(p);

      assertTrue(seen.get() > threshold, "expected additional events after re-run without abort");
    }
  }
}

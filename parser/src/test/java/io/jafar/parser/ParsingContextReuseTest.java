package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.types.JFRExecutionSample;
import io.jafar.parser.types.JFRGCHeapSummary;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;

/** Tests focused on {@linkplain ParsingContext} reuse */
public class ParsingContextReuseTest {

  @Test
  void typedThenUntypedOnSameContextIncrementsUptime() throws Exception {
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    ParsingContext ctx = ParsingContext.create();

    // First: typed session
    long before = ctx.uptime();
    LongAdder cntr = new LongAdder();
    try (TypedJafarParser p = ctx.newTypedParser(new File(uri).toPath())) {
      HandlerRegistration<JFRExecutionSample> reg =
          p.handle(
              JFRExecutionSample.class,
              (e, c) -> {
                cntr.increment();
              });
      p.run();
      reg.destroy(p);
    }
    long cntr1 = cntr.sum();
    long mid = ctx.uptime();
    assertTrue(mid > before, "uptime should increase after first run");

    // Second: untyped session on the same file
    try (UntypedJafarParser p = ctx.newUntypedParser(Paths.get(new File(uri).getAbsolutePath()))) {
      p.handle((t, v, ctl) -> cntr.increment());
      p.run();
    }
    long after = ctx.uptime();
    assertTrue(after >= mid, "uptime should be monotonic increasing");
    assertTrue(cntr.sum() > cntr1, "expected some events to be processed in untyped run");
  }

  @Test
  void untypedThenTypedOnSameContextIncrementsUptime() throws Exception {
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    ParsingContext ctx = ParsingContext.create();

    // First: typed session
    long before = ctx.uptime();
    LongAdder cntr = new LongAdder();
    try (UntypedJafarParser p = ctx.newUntypedParser(Paths.get(new File(uri).getAbsolutePath()))) {
      p.handle((t, v, ctl) -> cntr.increment());
      p.run();
    }

    long cntr1 = cntr.sum();
    long mid = ctx.uptime();
    assertTrue(mid > before, "uptime should increase after first run");

    // Second: untyped session on the same file
    try (TypedJafarParser p = ctx.newTypedParser(new File(uri).toPath())) {
      HandlerRegistration<JFRExecutionSample> reg =
          p.handle(
              JFRExecutionSample.class,
              (e, c) -> {
                cntr.increment();
              });
      p.run();
      reg.destroy(p);
    }
    long after = ctx.uptime();
    assertTrue(after >= mid, "uptime should be monotonic increasing");
    assertTrue(cntr.sum() > cntr1, "expected some events to be processed in untyped run");
  }

  @Test
  void contextIsReusableAfterHandlerFailure() throws Exception {
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    ParsingContext ctx = ParsingContext.create();

    // First run: force a handler exception and ensure run() fails
    AtomicBoolean thrown = new AtomicBoolean(false);
    try (UntypedJafarParser p = ctx.newUntypedParser(Path.of(new File(uri).getAbsolutePath()))) {
      p.handle(
          (t, v, ctl) -> {
            thrown.set(true);
            throw new RuntimeException("boom");
          });
      try {
        p.run();
        fail("expected run() to propagate handler exception");
      } catch (Exception expected) {
        // ok
      }
    }

    // Second run: ensure the same context still works fine
    AtomicInteger count = new AtomicInteger();
    try (UntypedJafarParser p = ctx.newUntypedParser(Path.of(new File(uri).getAbsolutePath()))) {
      p.handle((t, v, ctl) -> count.incrementAndGet());
      assertDoesNotThrow(p::run);
    }
    assertTrue(count.get() > 0, "context should remain usable after previous failure");
  }

  @Test
  void reuseWithDifferentTypeHandled() throws Exception {
    ClassLoader cl = TypedJafarParserTest.class.getClassLoader();
    URI uri = cl.getResource("test-jfr.jfr").toURI();
    ParsingContext ctx = ParsingContext.create();

    LongAdder cntr = new LongAdder();
    long before = ctx.uptime();
    try (TypedJafarParser p = ctx.newTypedParser(Path.of(new File(uri).getAbsolutePath()))) {
      HandlerRegistration<JFRExecutionSample> reg =
          p.handle(
              JFRExecutionSample.class,
              (e, c) -> {
                cntr.increment();
              });
      p.run();
      reg.destroy(p);
    }
    long cntr1 = cntr.sum();
    long mid = ctx.uptime();
    assertTrue(mid > before, "uptime should increase after first recording");

    try (TypedJafarParser p = ctx.newTypedParser(Path.of(new File(uri).getAbsolutePath()))) {
      HandlerRegistration<JFRGCHeapSummary> reg =
          p.handle(
              JFRGCHeapSummary.class,
              (e, c) -> {
                cntr.increment();
              });
      p.run();
      reg.destroy(p);
    }
    long after = ctx.uptime();
    assertTrue(after > mid, "uptime should increase after second recording");
    assertTrue(cntr.sum() > cntr1, "second run should have invoked handler callbacks");
  }
}

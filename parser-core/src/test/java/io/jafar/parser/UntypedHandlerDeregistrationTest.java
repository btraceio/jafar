package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.UntypedJafarParser;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Deregistering a handler while {@code run()} is dispatching events, twice, with the wrong parser,
 * or after the parser was closed. Before the copy-on-write handler array these either threw a
 * {@code ConcurrentModificationException} out of {@code run()} or, for a wrong parser, depended on
 * whether assertions were enabled.
 */
class UntypedHandlerDeregistrationTest {
  private static Path recording;
  private static int totalEvents;

  /** Upper bound on events a deregistered handler may still receive: one per parsing thread. */
  private static final int PARSING_THREADS =
      Math.max(Runtime.getRuntime().availableProcessors() - 2, 1);

  @BeforeAll
  static void countEvents() throws Exception {
    recording =
        Paths.get(
            new File(
                    UntypedHandlerDeregistrationTest.class
                        .getClassLoader()
                        .getResource("test-ap.jfr")
                        .toURI())
                .getAbsolutePath());
    AtomicInteger count = new AtomicInteger();
    try (UntypedJafarParser p = UntypedJafarParser.open(recording)) {
      p.handle((type, value, ctl) -> count.incrementAndGet());
      p.run();
    }
    totalEvents = count.get();
    assertTrue(totalEvents > PARSING_THREADS + 1, "recording too small: " + totalEvents);
  }

  @Test
  void handlerClosingItselfWhileAnotherIsRegistered() throws Exception {
    AtomicInteger first = new AtomicInteger();
    AtomicInteger second = new AtomicInteger();
    AtomicReference<HandlerRegistration<?>> self = new AtomicReference<>();
    try (UntypedJafarParser p = UntypedJafarParser.open(recording)) {
      self.set(
          p.handle(
              (type, value, ctl) -> {
                first.incrementAndGet();
                self.get().close();
              }));
      p.handle((type, value, ctl) -> second.incrementAndGet());
      p.run();
    }
    assertTrue(
        first.get() >= 1 && first.get() <= PARSING_THREADS,
        "closed handler received " + first.get() + " events");
    assertEquals(totalEvents, second.get());
  }

  @Test
  void handlerClosingAnotherHandler() throws Exception {
    AtomicInteger first = new AtomicInteger();
    AtomicInteger second = new AtomicInteger();
    AtomicReference<HandlerRegistration<?>> other = new AtomicReference<>();
    try (UntypedJafarParser p = UntypedJafarParser.open(recording)) {
      p.handle(
          (type, value, ctl) -> {
            first.incrementAndGet();
            other.get().close();
          });
      other.set(p.handle((type, value, ctl) -> second.incrementAndGet()));
      p.run();
    }
    assertEquals(totalEvents, first.get());
    assertTrue(
        second.get() >= 1 && second.get() <= PARSING_THREADS,
        "closed handler received " + second.get() + " events");
  }

  @Test
  void closeIsIdempotentAndSafeAfterParserClose() throws Exception {
    AtomicInteger count = new AtomicInteger();
    HandlerRegistration<?> reg;
    try (UntypedJafarParser p = UntypedJafarParser.open(recording)) {
      reg = p.handle((type, value, ctl) -> count.incrementAndGet());
      reg.close();
      reg.close();
      p.run();
    }
    reg.close();
    assertEquals(0, count.get());
  }

  @Test
  @SuppressWarnings("deprecation")
  void destroyWithAnotherParserDoesNothing() throws Exception {
    AtomicInteger count = new AtomicInteger();
    try (UntypedJafarParser p = UntypedJafarParser.open(recording);
        UntypedJafarParser unrelated = UntypedJafarParser.open(recording)) {
      HandlerRegistration<?> reg = p.handle((type, value, ctl) -> count.incrementAndGet());
      reg.destroy(unrelated);
      p.run();
      assertEquals(totalEvents, count.get());

      reg.destroy(p);
      p.run();
      assertEquals(totalEvents, count.get());
    }
  }
}

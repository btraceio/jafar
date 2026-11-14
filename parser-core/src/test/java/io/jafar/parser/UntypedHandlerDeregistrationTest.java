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
      io.jafar.parser.internal_api.StreamingChunkParser.defaultParallelism();

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

  // Registered twice, one slot: before registrations were counted, closing either registration
  // removed the handler for both.
  @Test
  void sameHandlerRegisteredTwiceStaysUntilBothRegistrationsAreClosed() throws Exception {
    AtomicInteger count = new AtomicInteger();
    UntypedJafarParser.EventHandler handler = (type, value, ctl) -> count.incrementAndGet();
    try (UntypedJafarParser p = UntypedJafarParser.open(recording)) {
      HandlerRegistration<?> first = p.handle(handler);
      HandlerRegistration<?> second = p.handle(handler);

      p.run();
      assertEquals(totalEvents, count.get(), "a handler is called once per event");

      first.close();
      first.close(); // a repeated close must not release the second registration
      count.set(0);
      p.run();
      assertEquals(totalEvents, count.get(), "still registered through the second registration");

      second.close();
      count.set(0);
      p.run();
      assertEquals(0, count.get());
    }
  }

  // Before the parsers shared one registry, a copy took its own copy of the handlers and a
  // registration made on the original could not deregister the handler from it.
  @Test
  void registrationOnOriginalDeregistersFromWithParserListenerCopy() throws Exception {
    AtomicInteger count = new AtomicInteger();
    try (UntypedJafarParser p = UntypedJafarParser.open(recording)) {
      HandlerRegistration<?> reg = p.handle((type, value, ctl) -> count.incrementAndGet());
      UntypedJafarParser copy =
          p.withParserListener(io.jafar.parser.internal_api.ChunkParserListener.NOOP);

      copy.run();
      assertEquals(totalEvents, count.get());

      reg.close();
      count.set(0);
      copy.run();
      assertEquals(0, count.get());
    }
  }

  @Test
  void closingACopyKeepsTheHandlersOfTheOriginal() throws Exception {
    AtomicInteger count = new AtomicInteger();
    try (UntypedJafarParser p = UntypedJafarParser.open(recording)) {
      p.handle((type, value, ctl) -> count.incrementAndGet());
      UntypedJafarParser copy =
          p.withParserListener(io.jafar.parser.internal_api.ChunkParserListener.NOOP);
      copy.close();
      copy.close();

      p.run();
      assertEquals(totalEvents, count.get());
    }
  }

  // Deregistration from a thread that is not a parsing thread, while run() is dispatching. The
  // first handler called for the first event parks its chunk thread in the middle of the handler
  // loop; another thread then closes a different registration. With the handlers in a HashSet
  // the loop's iterator threw ConcurrentModificationException when the chunk thread resumed.
  @Test
  void anotherThreadClosesARegistrationWhileRunIsDispatching() throws Exception {
    int handlerCount = 4;
    java.util.concurrent.CountDownLatch parked = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch resume = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicBoolean firstCall =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    AtomicInteger[] counts = new AtomicInteger[handlerCount];
    HandlerRegistration<?>[] regs = new HandlerRegistration<?>[handlerCount];
    AtomicInteger parkedHandler = new AtomicInteger(-1);
    AtomicReference<Throwable> closerFailure = new AtomicReference<>();

    try (UntypedJafarParser p = UntypedJafarParser.open(recording)) {
      for (int i = 0; i < handlerCount; i++) {
        int self = i;
        counts[i] = new AtomicInteger();
        regs[i] =
            p.handle(
                (type, value, ctl) -> {
                  counts[self].incrementAndGet();
                  if (firstCall.compareAndSet(true, false)) {
                    parkedHandler.set(self);
                    parked.countDown();
                    try {
                      resume.await();
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                  }
                });
      }
      Thread closer =
          new Thread(
              () -> {
                try {
                  parked.await();
                  // close every registration except the parked handler's own
                  for (int i = 0; i < handlerCount; i++) {
                    if (i != parkedHandler.get()) {
                      regs[i].close();
                    }
                  }
                } catch (Throwable t) {
                  closerFailure.set(t);
                } finally {
                  resume.countDown();
                }
              });
      closer.start();
      p.run();
      closer.join();
    }
    assertEquals(null, closerFailure.get());
    int survivor = parkedHandler.get();
    assertEquals(totalEvents, counts[survivor].get(), "the handler left registered");
    for (int i = 0; i < handlerCount; i++) {
      if (i != survivor) {
        assertTrue(
            counts[i].get() <= PARSING_THREADS,
            "handler " + i + " closed during the first event received " + counts[i].get());
      }
    }
  }
}

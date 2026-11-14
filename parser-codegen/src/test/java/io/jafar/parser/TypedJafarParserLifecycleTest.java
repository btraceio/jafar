package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.types.JFRExecutionSample;
import java.io.File;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

public class TypedJafarParserLifecycleTest {

  @Test
  void testControlStreamPositionExposedInHandler() throws Exception {
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    try (TypedJafarParser p = TypedJafarParser.open(new File(uri).getAbsolutePath())) {
      AtomicLong lastPos = new AtomicLong(-1);
      HandlerRegistration<JFRExecutionSample> reg =
          p.handle(
              JFRExecutionSample.class,
              (event, ctl) -> {
                long pos = ctl.stream().position();
                assertTrue(pos >= 0);
                lastPos.set(pos);
              });
      p.run();
      reg.destroy(p);
      assertTrue(lastPos.get() >= 0);
    }
  }

  @Test
  void testDestroyRegistrationStopsInvocationOnSubsequentRun() throws Exception {
    // create a synthetic recording with a single ParserEvent
    java.io.ByteArrayOutputStream recordingStream = new java.io.ByteArrayOutputStream();
    try (org.openjdk.jmc.flightrecorder.writer.api.Recording recording =
        org.openjdk.jmc.flightrecorder.writer.api.Recordings.newRecording(recordingStream)) {
      io.jafar.TestJfrRecorder rec = new io.jafar.TestJfrRecorder(recording);
      rec.registerEventType(ParserEvent.class);
      rec.writeEvent(new ParserEvent(42));
    }
    java.nio.file.Path tmp = java.nio.file.Files.createTempFile("recording", ".jfr");
    tmp.toFile().deleteOnExit();
    java.nio.file.Files.write(tmp, recordingStream.toByteArray());

    try (TypedJafarParser p = TypedJafarParser.open(tmp.toString())) {
      AtomicInteger count = new AtomicInteger();
      HandlerRegistration<ParserEvent1> r1 =
          p.handle(ParserEvent1.class, (e, c) -> count.incrementAndGet());
      HandlerRegistration<ParserEvent1> r2 =
          p.handle(ParserEvent1.class, (e, c) -> count.incrementAndGet());

      p.run();
      assertEquals(2, count.get());

      r1.destroy(p);

      p.run();
      assertEquals(3, count.get());

      r2.destroy(p);
    }
  }

  @Test
  void testRunAfterCloseThrows() throws Exception {
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    TypedJafarParser p = TypedJafarParser.open(new File(uri).getAbsolutePath());
    p.close();
    assertThrows(java.io.IOException.class, p::run);
  }

  /** Events of JFRExecutionSample in test-ap.jfr, counted by a plain run. */
  private static int countSamples(String path) throws Exception {
    AtomicInteger count = new AtomicInteger();
    try (TypedJafarParser p = TypedJafarParser.open(path)) {
      p.handle(JFRExecutionSample.class, (e, c) -> count.incrementAndGet());
      p.run();
    }
    return count.get();
  }

  /** Upper bound on events a deregistered handler may still receive: one per parsing thread. */
  private static final int PARSING_THREADS =
      Math.max(Runtime.getRuntime().availableProcessors() - 2, 1);

  // Before the copy-on-write handler map, run() threw an IOException caused by a
  // ConcurrentModificationException here.
  @Test
  void handlerClosingItselfWhileAnotherIsRegistered() throws Exception {
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    String path = new File(uri).getAbsolutePath();
    int total = countSamples(path);
    assertTrue(total > PARSING_THREADS + 1, "recording too small: " + total);

    AtomicInteger first = new AtomicInteger();
    AtomicInteger second = new AtomicInteger();
    java.util.concurrent.atomic.AtomicReference<HandlerRegistration<JFRExecutionSample>> self =
        new java.util.concurrent.atomic.AtomicReference<>();
    try (TypedJafarParser p = TypedJafarParser.open(path)) {
      self.set(
          p.handle(
              JFRExecutionSample.class,
              (e, c) -> {
                first.incrementAndGet();
                self.get().close();
              }));
      p.handle(JFRExecutionSample.class, (e, c) -> second.incrementAndGet());
      p.run();
    }
    assertTrue(
        first.get() >= 1 && first.get() <= PARSING_THREADS,
        "closed handler received " + first.get() + " events");
    assertEquals(total, second.get());
  }

  // Before the fix the second close()/destroy() threw a NullPointerException: the handler set of
  // the class had already been dropped from the map.
  @Test
  @SuppressWarnings("deprecation")
  void closeIsIdempotentAndSafeAfterParserClose() throws Exception {
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    AtomicInteger count = new AtomicInteger();
    HandlerRegistration<JFRExecutionSample> reg;
    TypedJafarParser p = TypedJafarParser.open(new File(uri).getAbsolutePath());
    try {
      reg = p.handle(JFRExecutionSample.class, (e, c) -> count.incrementAndGet());
      reg.close();
      reg.close();
      reg.destroy(p);
      p.run();
    } finally {
      p.close();
    }
    reg.close();
    reg.destroy(p);
    assertEquals(0, count.get());
  }

  @Test
  @SuppressWarnings("deprecation")
  void destroyWithAnotherParserDoesNothing() throws Exception {
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    String path = new File(uri).getAbsolutePath();
    int total = countSamples(path);
    AtomicInteger count = new AtomicInteger();
    try (TypedJafarParser p = TypedJafarParser.open(path);
        TypedJafarParser unrelated = TypedJafarParser.open(path)) {
      HandlerRegistration<JFRExecutionSample> reg =
          p.handle(JFRExecutionSample.class, (e, c) -> count.incrementAndGet());
      reg.destroy(unrelated);
      p.run();
      assertEquals(total, count.get());
    }
  }
}

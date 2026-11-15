package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.JfrType;
import io.jafar.parser.api.TypedJafarParser;
import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Control#abort() on JDK 8 stops the whole parse. Runs against test-ap.jfr and against the same
 * recording concatenated into three chunks, with assertions that hold for any chunk count: the
 * aborting thread gets no further event, and any other thread at most the one it was dispatching.
 */
public class CoreControlAbortTest {

  @JfrType("jdk.ExecutionSample")
  public interface JFRExecSampleLite {
    String state();
  }

  private static final int THRESHOLD = 5;

  @Test
  public void abortStopsDelivery() throws Exception {
    assertAbortStopsDelivery(testAp());
  }

  @Test
  public void abortStopsDeliveryAcrossChunks() throws Exception {
    Path single = testAp();
    Path multi = Files.createTempFile("test-ap-x3", ".jfr");
    try {
      try (OutputStream os = Files.newOutputStream(multi)) {
        for (int i = 0; i < 3; i++) {
          Files.copy(single, os);
        }
      }
      assertAbortStopsDelivery(multi);
    } finally {
      Files.deleteIfExists(multi);
    }
  }

  private static Path testAp() throws Exception {
    return new File(CoreControlAbortTest.class.getClassLoader().getResource("test-ap.jfr").toURI())
        .toPath();
  }

  private static int countAll(Path recording) throws Exception {
    final AtomicInteger n = new AtomicInteger();
    try (TypedJafarParser p = TypedJafarParser.open(recording.toString())) {
      p.handle(JFRExecSampleLite.class, (e, ctl) -> n.incrementAndGet());
      p.run();
    }
    return n.get();
  }

  private static void assertAbortStopsDelivery(Path recording) throws Exception {
    final AtomicInteger seen = new AtomicInteger();
    final AtomicBoolean abortCalled = new AtomicBoolean();
    final Set<Thread> abortingThreads = ConcurrentHashMap.newKeySet();
    final Map<String, AtomicInteger> lateByThread = new ConcurrentHashMap<>();
    final List<String> violations = Collections.synchronizedList(new ArrayList<String>());

    try (TypedJafarParser p = TypedJafarParser.open(recording.toString())) {
      HandlerRegistration<JFRExecSampleLite> reg =
          p.handle(
              JFRExecSampleLite.class,
              (e, ctl) -> {
                Thread t = Thread.currentThread();
                if (abortingThreads.contains(t)) {
                  violations.add("event on " + t.getName() + " after it called abort()");
                }
                if (abortCalled.get()) {
                  lateByThread
                      .computeIfAbsent(t.getName(), k -> new AtomicInteger())
                      .incrementAndGet();
                }
                if (seen.incrementAndGet() == THRESHOLD) {
                  ctl.abort();
                  abortingThreads.add(t);
                  abortCalled.set(true);
                }
              });
      p.run();
      reg.destroy(p);
    }

    String name = recording.getFileName().toString();
    int total = countAll(recording);
    assertTrue(total > THRESHOLD, name + ": too few events: " + total);
    assertTrue(seen.get() >= THRESHOLD, name + ": seen=" + seen.get());
    assertTrue(violations.isEmpty(), name + ": " + violations);
    for (Map.Entry<String, AtomicInteger> e : lateByThread.entrySet()) {
      assertTrue(
          e.getValue().get() <= 1,
          name + ": " + e.getKey() + " received " + e.getValue() + " events after abort()");
    }
    assertTrue(seen.get() < total, name + ": abort did not stop the parse, seen=" + seen.get());
  }
}

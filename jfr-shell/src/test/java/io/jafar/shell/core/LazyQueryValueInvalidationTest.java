package io.jafar.shell.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.jfrpath.JfrPathParser;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An {@code invalidate()} or {@code release()} while an evaluation is running must not be undone
 * when that evaluation finishes. Evaluation is held in flight at {@code getAvailableTypes()}, which
 * JfrPathEvaluator calls first, on a real session over the checked-in TCK recording.
 */
class LazyQueryValueInvalidationTest {
  private JFRSession session;

  /**
   * Held for the whole test: LazyQueryValue keeps its SessionRef only weakly, so with no other
   * reference a GC before the evaluation's sessionRef.get() cleared it, get() threw "Session no
   * longer available" before reaching getAvailableTypes(), and the test failed at "evaluation did
   * not start" after 30 s (seen in CI under memory pressure; a System.gc() there reproduces it).
   */
  private SessionManager.SessionRef<JFRSession> ref;

  private final CountDownLatch entered = new CountDownLatch(1);
  private final CountDownLatch proceed = new CountDownLatch(1);

  @BeforeEach
  void open() throws Exception {
    Path recording =
        Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr")
            .toAbsolutePath()
            .normalize();
    session = spy(new JFRSession(recording, ParsingContext.create()));
    doAnswer(
            inv -> {
              entered.countDown();
              assertTrue(proceed.await(30, TimeUnit.SECONDS));
              return inv.callRealMethod();
            })
        .when(session)
        .getAvailableTypes();
  }

  @AfterEach
  void close() throws Exception {
    session.close();
  }

  private Object duringEvaluation(LazyQueryValue value, Runnable action) throws Exception {
    FutureTask<Object> read = new FutureTask<>(value::get);
    new Thread(read).start();
    // An evaluation that fails before getAvailableTypes() never opens the latch: report its own
    // exception (read.get() throws it) instead of waiting 30 s for "evaluation did not start".
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!entered.await(50, TimeUnit.MILLISECONDS)) {
      if (read.isDone()) {
        read.get();
        throw new AssertionError("evaluation returned without reaching getAvailableTypes()");
      }
      assertTrue(System.nanoTime() < deadline, "evaluation did not start");
    }
    action.run(); // must return without waiting for the evaluation
    proceed.countDown();
    return read.get(60, TimeUnit.SECONDS);
  }

  private LazyQueryValue value() throws Exception {
    ref = new SessionManager.SessionRef<>(1, null, session);
    return new LazyQueryValue(JfrPathParser.parse("events/jdk.ThreadStart"), ref, "q");
  }

  @Test
  void invalidateDuringEvaluationIsNotLost() throws Exception {
    LazyQueryValue value = value();
    Object rows = duringEvaluation(value, value::invalidate);
    assertEquals(17, ((List<?>) rows).size(), "the reader still gets its rows");
    assertFalse(
        value.isCached(), "rows computed before the invalidate must not be cached after it");
  }

  @Test
  void releaseDuringEvaluationIsNotUndone() throws Exception {
    LazyQueryValue value = value();
    duringEvaluation(value, value::release);
    assertFalse(value.isCached(), "a released value must not get its rows back");
  }
}

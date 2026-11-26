package io.jafar.testsupport.work;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * The counters the work-budget agent's probes increment, and the measurement around one parse.
 *
 * <p>{@link #hit(int)} is called from instrumented parser code on whatever thread runs it — the
 * chunk pool decodes on several threads at once — so each counter is a {@link LongAdder}. This
 * class must be loadable by the class loader of the parser classes: it lives in {@code
 * test-support}, which the work tests have on their classpath next to the parser.
 */
public final class WorkCounters {

  private static final LongAdder[] COUNTS = new LongAdder[WorkProbes.COUNTERS.size()];

  static {
    for (int i = 0; i < COUNTS.length; i++) {
      COUNTS[i] = new LongAdder();
    }
  }

  private WorkCounters() {}

  /**
   * Counts one unit of work; called by the probes. Public because instrumented parser classes call
   * it from other packages.
   *
   * @param counter an index into {@link WorkProbes#COUNTERS}
   */
  public static void hit(int counter) {
    COUNTS[counter].increment();
  }

  /** Zeroes every counter. */
  public static void reset() {
    for (LongAdder count : COUNTS) {
      count.reset();
    }
  }

  /** The current value of every counter, in {@link WorkProbes#COUNTERS} order. */
  public static Map<String, Long> snapshot() {
    Map<String, Long> values = new LinkedHashMap<>();
    for (int i = 0; i < COUNTS.length; i++) {
      values.put(WorkProbes.COUNTERS.get(i), COUNTS[i].sum());
    }
    return Collections.unmodifiableMap(values);
  }

  /** A parse to measure. */
  public interface Parse {
    void run() throws Exception;
  }

  /**
   * Runs {@code parse} on zeroed counters and returns what it counted.
   *
   * <p>The parse must have finished all its work when {@code run()} returns: the parsers wait for
   * every chunk task before returning from {@code run()}, so the pool threads' increments
   * happen-before the snapshot.
   *
   * <p>Fails (R3) when the agent is not installed — every counter would read 0 and a budget of 0
   * would pass — and when the agent reported a probe it could not place.
   */
  public static Map<String, Long> measure(Parse parse) throws Exception {
    assertAgentInstalled();
    reset();
    parse.run();
    Map<String, Long> counted = snapshot();
    assertNoAgentErrors();
    return counted;
  }

  /** Fails unless the work-budget agent ran {@code premain} in this JVM. */
  public static void assertAgentInstalled() {
    if (System.getProperty(WorkProbes.AGENT_PROPERTY) == null) {
      throw new AssertionError(
          "the work-budget agent is not installed in this JVM (system property "
              + WorkProbes.AGENT_PROPERTY
              + " is not set), so every work counter would read 0. Run the work tests through"
              + " their Gradle task (workTest), which adds -javaagent:<jafar-work-agent jar>;"
              + " never a plain test task.");
    }
  }

  /** Fails when the agent recorded a failure: a probe it could not place, or a transform error. */
  public static void assertNoAgentErrors() {
    String errors = System.getProperty(WorkProbes.ERRORS_PROPERTY);
    if (errors != null && !errors.isEmpty()) {
      throw new AssertionError(
          "the work-budget agent could not instrument the parser as WorkProbes describes; the"
              + " parser changed under a probe, so its counter would be wrong. Update WorkProbes"
              + " (and doc/plans/work-budgets.md): "
              + errors.replace(";", "\n  "));
    }
  }

  /** What the agent placed so far, as {@code <probe id>@<class>=<sites>} entries, for the log. */
  public static String applied() {
    String applied = System.getProperty(WorkProbes.APPLIED_PROPERTY);
    return applied == null ? "" : applied;
  }
}

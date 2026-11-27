package io.jafar.shell.core.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * #276: JDK recordings name thread states as HotSpot does ({@code STATE_RUNNABLE}, {@code
 * STATE_BLOCKED_ON_MONITOR_ENTER}, ...), and the analyses compared them with {@code RUNNABLE},
 * {@code BLOCKED}, {@code WAITING}: nothing matched.
 */
class ThreadStateNamesTest {

  @Test
  void jdkStatesBecomeTheThreadStateTheyAre() {
    // The names a JDK 25 recording's jdk.types.ThreadState pool holds
    assertEquals("NEW", JfrAnalyses.normalizeState("STATE_NEW"));
    assertEquals("RUNNABLE", JfrAnalyses.normalizeState("STATE_RUNNABLE"));
    assertEquals("TIMED_WAITING", JfrAnalyses.normalizeState("STATE_SLEEPING"));
    assertEquals("WAITING", JfrAnalyses.normalizeState("STATE_IN_OBJECT_WAIT"));
    assertEquals("TIMED_WAITING", JfrAnalyses.normalizeState("STATE_IN_OBJECT_WAIT_TIMED"));
    assertEquals("WAITING", JfrAnalyses.normalizeState("STATE_PARKED"));
    assertEquals("TIMED_WAITING", JfrAnalyses.normalizeState("STATE_PARKED_TIMED"));
    assertEquals("BLOCKED", JfrAnalyses.normalizeState("STATE_BLOCKED_ON_MONITOR_ENTER"));
    assertEquals("TERMINATED", JfrAnalyses.normalizeState("STATE_TERMINATED"));
  }

  @Test
  void datadogStatesBecomeTheThreadStateTheyAre() {
    // The Datadog profiler's state pool: RUNNABLE is already one, CONTENDED/PARKED/SLEEPING are not
    assertEquals("RUNNABLE", JfrAnalyses.normalizeState("RUNNABLE"));
    assertEquals("BLOCKED", JfrAnalyses.normalizeState("CONTENDED"));
    assertEquals("WAITING", JfrAnalyses.normalizeState("PARKED"));
    assertEquals("TIMED_WAITING", JfrAnalyses.normalizeState("SLEEPING"));
    assertEquals("SYSCALL", JfrAnalyses.normalizeState("SYSCALL"));
    // An unknown STATE_ name loses only its prefix
    assertEquals("SOMETHING_NEW", JfrAnalyses.normalizeState("STATE_SOMETHING_NEW"));
  }

  @Test
  void onlyRunnableSamplesCarryNoStateInformation() {
    assertEquals(true, JfrAnalyses.onlyRunnable(Map.of("RUNNABLE", 10L)));
    assertEquals(false, JfrAnalyses.onlyRunnable(Map.of("RUNNABLE", 10L, "BLOCKED", 1L)));
    assertEquals(false, JfrAnalyses.onlyRunnable(Map.of("TIMED_WAITING", 10L)));
  }

  @Test
  void samplesWithJdkStatesAreClassified() {
    JfrAnalyses analyses = new JfrAnalyses();
    assertEquals(
        "RUNNABLE", analyses.extractState(Map.of("state", Map.of("name", "STATE_RUNNABLE"))));
    assertEquals(
        "LOCK_CONTENTION",
        analyses.assessThreadBehavior(
            Map.of(
                analyses.extractState(
                    Map.of("state", Map.of("name", "STATE_BLOCKED_ON_MONITOR_ENTER"))),
                30L,
                analyses.extractState(Map.of("state", Map.of("name", "STATE_RUNNABLE"))),
                70L),
            100));
  }

  @Test
  void informativeStatesStillYieldPatternsAndProblematicThreads() {
    JfrAnalyses analyses = new JfrAnalyses();
    JfrAnalyses.ThreadStateMetrics worker = new JfrAnalyses.ThreadStateMetrics("1", "worker");
    worker.totalSamples.add(100);
    worker.stateCount.put("RUNNABLE", 60L);
    worker.stateCount.put("BLOCKED", 40L);
    Map<String, Long> global = Map.of("RUNNABLE", 60L, "BLOCKED", 40L);

    Map<String, Object> informative =
        analyses.generateTsaInsights(Map.of("1", worker), global, 100, Map.of(), Map.of(), true);
    Map<String, Object> suppressed =
        analyses.generateTsaInsights(Map.of("1", worker), global, 100, Map.of(), Map.of(), false);

    assertEquals(true, informative.containsKey("problematicThreads"), informative.toString());
    assertEquals(
        true,
        informative.get("patterns").toString().contains("lock contention"),
        informative.toString());
    assertEquals(false, suppressed.containsKey("problematicThreads"), suppressed.toString());
    assertEquals(
        true,
        suppressed.get("recommendations").toString().contains("cannot show blocking"),
        suppressed.toString());
  }
}

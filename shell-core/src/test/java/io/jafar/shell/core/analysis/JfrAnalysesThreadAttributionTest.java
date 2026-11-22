package io.jafar.shell.core.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import io.jafar.shell.jfrpath.JfrPathParser;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Samples carry their thread in {@code sampledThread}, not {@code eventThread}. The thread
 * extractors read only {@code eventThread}, so {@code jfr_tsa} put every sample under one thread
 * named "unknown" (#222).
 */
class JfrAnalysesThreadAttributionTest {
  private static Map<String, Object> thread(long id, String javaName, String osName) {
    Map<String, Object> thread = new HashMap<>();
    thread.put("javaThreadId", id);
    thread.put("javaName", javaName);
    thread.put("osName", osName);
    return thread;
  }

  @Test
  void aSampleIsAttributedToItsSampledThread() {
    JfrAnalyses analyses = new JfrAnalyses();
    Map<String, Object> sample = Map.of("sampledThread", thread(42, "worker-0", "os-42"));
    assertEquals("42", analyses.extractThreadId(sample));
    assertEquals("worker-0", analyses.extractThreadName(sample));
  }

  @Test
  void anEventWithOnlyAnEventThreadStillResolves() {
    JfrAnalyses analyses = new JfrAnalyses();
    Map<String, Object> monitorEnter = Map.of("eventThread", thread(7, "main", "os-7"));
    assertEquals("7", analyses.extractThreadId(monitorEnter));
    assertEquals("main", analyses.extractThreadName(monitorEnter));
  }

  @Test
  void idAndNameComeFromTheSameThread() {
    JfrAnalyses analyses = new JfrAnalyses();
    Map<String, Object> both =
        Map.of(
            "sampledThread", thread(42, null, "os-42"),
            "eventThread", thread(7, "main", "os-7"));
    assertEquals("42", analyses.extractThreadId(both));
    // the sampled thread has no Java name: its OS name, not the other thread's Java name
    assertEquals("os-42", analyses.extractThreadName(both));
  }

  /**
   * Records {@code jdk.ExecutionSample} from four named busy threads and this one. The checked-in
   * recordings do not serve: the TCK recording's samples are {@code datadog.ExecutionSample}, which
   * carries {@code eventThread} and so never showed the bug.
   */
  private static Path recordSamples(Path dir) throws Exception {
    Path file = dir.resolve("samples.jfr");
    AtomicBoolean stop = new AtomicBoolean();
    List<Thread> workers = new ArrayList<>();
    try (Recording recording = new Recording()) {
      recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
      recording.setDestination(file);
      recording.start();
      for (int i = 0; i < 4; i++) {
        Thread worker = new Thread(() -> spin(stop), "tsa-worker-" + i);
        worker.start();
        workers.add(worker);
      }
      long end = System.nanoTime() + Duration.ofMillis(1500).toNanos();
      while (System.nanoTime() < end) {
        spinOnce();
      }
      stop.set(true);
      for (Thread worker : workers) {
        worker.join();
      }
      recording.stop();
    }
    return file;
  }

  private static volatile long sink;

  private static void spin(AtomicBoolean stop) {
    while (!stop.get()) {
      spinOnce();
    }
  }

  private static void spinOnce() {
    long value = 0;
    for (int i = 0; i < 10_000; i++) {
      value += Long.hashCode(value ^ i);
    }
    sink += value;
  }

  @Test
  void tsaCountsSamplesPerSampledThreadAsTheQueryEngineDoes(@TempDir Path dir) throws Exception {
    try (JFRSession session = new JFRSession(recordSamples(dir), ParsingContext.create())) {
      JfrPathEvaluator evaluator = new JfrPathEvaluator();
      String samples = new JfrAnalyses().detectExecutionEventType(AnalysisTarget.of(1, session));
      assertEquals("jdk.ExecutionSample", samples, "the event type TSA reads");
      Map<String, Long> expected = new TreeMap<>();
      for (Map<String, Object> row :
          evaluator.evaluate(
              session,
              JfrPathParser.parse(
                  "events/" + samples + " | groupBy(sampledThread/javaThreadId)"))) {
        expected.put(String.valueOf(row.get("key")), ((Number) row.get("count")).longValue());
      }
      // more than one thread, or this test could not tell attribution from a single bucket
      assertTrue(expected.size() > 1, "sampled threads in the recording: " + expected);

      Map<String, Object> args = new HashMap<>();
      args.put("minSamples", 1);
      args.put("includeInsights", false);
      args.put("correlateBlocking", false);
      Map<String, Object> tsa =
          new JfrAnalyses().tsa(AnalysisTarget.of(1, session), args, Progress.NONE);

      assertEquals(expected.size(), ((Number) tsa.get("totalThreads")).intValue(), "threads");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> profiles = (List<Map<String, Object>>) tsa.get("threadProfiles");
      Map<String, Long> actual = new TreeMap<>();
      for (Map<String, Object> profile : profiles) {
        actual.put(
            String.valueOf(profile.get("threadId")),
            ((Number) profile.get("totalSamples")).longValue());
      }
      // threadProfiles lists the top 20 threads; compare those
      Map<String, Long> expectedListed = new TreeMap<>(expected);
      expectedListed.keySet().retainAll(actual.keySet());
      assertEquals(Math.min(expected.size(), 20), actual.size(), "listed threads: " + actual);
      assertEquals(expectedListed, actual);
      assertTrue(
          profiles.stream().noneMatch(p -> "unknown".equals(p.get("threadName"))),
          "a profile named 'unknown': " + profiles);
    }
  }
}

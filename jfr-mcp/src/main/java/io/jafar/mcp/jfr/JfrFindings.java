package io.jafar.mcp.jfr;

import io.jafar.mcp.findings.Finding;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Derives structured {@link Finding}s from the result maps produced by the USE and TSA analyses.
 *
 * <p>These analyses already reach a judgement — they emit {@code assessment} strings, a {@code
 * bottlenecks} list and prose recommendations. This class restates those same judgements in the
 * shared findings shape so that {@code jfr_diagnose} can merge them with its own, and so that an
 * agent can rank results from several tools without parsing prose. The thresholds mirror the ones
 * already applied in {@code generateUseInsights} and {@code generateTsaInsights}; this class
 * deliberately introduces no new ones.
 */
final class JfrFindings {

  private JfrFindings() {}

  /** Derives findings from a {@code jfr_use} resource-metrics map. */
  @SuppressWarnings("unchecked")
  static List<Finding> fromUse(Map<String, Object> resourceMetrics, String source) {
    List<Finding> findings = new ArrayList<>();
    if (resourceMetrics == null) {
      return findings;
    }

    Map<String, Object> cpu = asMap(resourceMetrics.get("cpu"));
    if (cpu != null && !cpu.containsKey("error")) {
      Map<String, Object> saturation = asMap(cpu.get("saturation"));
      if (saturation != null && saturation.get("value") instanceof Number value) {
        double satPct = value.doubleValue();
        if (satPct > 30) {
          findings.add(
              Finding.of("cpu", "saturation")
                  .warning()
                  .title("CPU saturation: %.1f%% of CPU time spent waiting or blocked", satPct)
                  .description(
                      "Threads are spending a substantial share of their time off-CPU. The"
                          + " bottleneck is contention or waiting, not raw compute.")
                  .source(source)
                  .evidence("saturationPct", satPct)
                  .evidence("assessment", cpu.get("assessment"))
                  .action("Run jfr_tsa to identify which threads wait and on what")
                  .build());
        }
      }
    }

    Map<String, Object> memory = asMap(resourceMetrics.get("memory"));
    if (memory != null && !memory.containsKey("error")) {
      String assessment = (String) memory.get("assessment");
      if ("HIGH_PRESSURE".equals(assessment) || "MODERATE_PRESSURE".equals(assessment)) {
        boolean high = "HIGH_PRESSURE".equals(assessment);
        findings.add(
            Finding.of("memory", "pressure")
                .severity(high ? Finding.Severity.CRITICAL : Finding.Severity.WARNING)
                .title("Memory pressure: %s", assessment)
                .description(
                    "GC is working hard relative to the recording length. Either allocation"
                        + " rate is high or the heap is undersized for the workload.")
                .source(source)
                .evidence("assessment", assessment)
                .evidence("utilization", memory.get("utilization"))
                .evidence("saturation", memory.get("saturation"))
                .action("Identify allocation hotspots, then consider heap sizing")
                .query(
                    "events/jdk.ObjectAllocationSample | groupBy(objectClass/name, agg=sum,"
                        + " value=weight) | top(20, by=value)")
                .build());
      }
    }

    Map<String, Object> threads = asMap(resourceMetrics.get("threads"));
    if (threads != null && !threads.containsKey("error")) {
      Map<String, Object> saturation = asMap(threads.get("saturation"));
      if (saturation != null) {
        Map<String, Object> lockContention = asMap(saturation.get("lockContention"));
        Object contentionEvents = saturation.get("contentionEvents");
        if (contentionEvents == null && lockContention != null) {
          contentionEvents = lockContention.get("contentionEvents");
        }
        Object topClass = saturation.get("topContendedClass");
        if (topClass == null && lockContention != null) {
          topClass = lockContention.get("topContendedClass");
        }
        if (contentionEvents instanceof Number events && events.intValue() > 100) {
          findings.add(
              Finding.of("threads", "lock-contention")
                  .warning()
                  .title(
                      "Lock contention: %,d contention events%s",
                      events.intValue(), topClass != null ? ", worst on " + topClass : "")
                  .source(source)
                  .evidence("contentionEvents", events.intValue())
                  .evidence("topContendedClass", topClass)
                  .action("Review synchronisation on the most contended monitor")
                  .query(
                      "events/jdk.JavaMonitorEnter | groupBy(monitorClass, agg=sum, value=duration)"
                          + " | top(10, by=value)")
                  .build());
        }

        Map<String, Object> queueSaturation = asMap(saturation.get("queueSaturation"));
        if (queueSaturation != null) {
          String queueAssessment = (String) queueSaturation.get("assessment");
          if ("HIGH_QUEUE_SATURATION".equals(queueAssessment)
              || "MODERATE_QUEUE_SATURATION".equals(queueAssessment)) {
            boolean high = "HIGH_QUEUE_SATURATION".equals(queueAssessment);
            findings.add(
                Finding.of("threads", "queue-saturation")
                    .severity(high ? Finding.Severity.CRITICAL : Finding.Severity.WARNING)
                    .title("Executor queue saturation: %s", queueAssessment)
                    .description(
                        "Work is waiting in executor queues before any application code runs"
                            + " for it. Method-level optimisation cannot recover this time.")
                    .source(source)
                    .evidence("avgQueueTimeMs", queueSaturation.get("avgQueueTimeMs"))
                    .evidence("assessment", queueAssessment)
                    .action("Increase pool size, or reduce per-task cost upstream")
                    .build());
          }
        }
      }
    }

    Map<String, Object> io = asMap(resourceMetrics.get("io"));
    if (io != null && !io.containsKey("error")) {
      String assessment = (String) io.get("assessment");
      if (assessment != null && assessment.contains("HIGH")) {
        findings.add(
            Finding.of("io", "saturation")
                .warning()
                .title("I/O pressure: %s", assessment)
                .source(source)
                .evidence("assessment", assessment)
                .evidence("utilization", io.get("utilization"))
                .action("Identify the slowest destinations and whether they are dependencies")
                .query(
                    "events/jdk.SocketRead | groupBy(address, agg=sum, value=duration) | top(10,"
                        + " by=value)")
                .build());
      }
    }

    return findings;
  }

  /** Derives findings from a {@code jfr_tsa} result map. */
  @SuppressWarnings("unchecked")
  static List<Finding> fromTsa(Map<String, Object> tsaResult, String source) {
    List<Finding> findings = new ArrayList<>();
    if (tsaResult == null) {
      return findings;
    }

    Map<String, Object> insights = asMap(tsaResult.get("insights"));
    if (insights == null) {
      return findings;
    }

    Object problematic = insights.get("problematicThreads");
    if (problematic instanceof List<?> threads && !threads.isEmpty()) {
      for (Object entry : threads) {
        Map<String, Object> thread = asMap(entry);
        if (thread == null) {
          continue;
        }
        String name = String.valueOf(thread.getOrDefault("thread", "unknown"));
        findings.add(
            Finding.of("threads", "problematic-thread-" + name)
                .warning()
                .title("Thread %s: %s", name, thread.getOrDefault("assessment", "problematic"))
                .description((String) thread.get("recommendation"))
                .source(source)
                .evidence("thread", name)
                .evidence("assessment", thread.get("assessment"))
                .evidence("dominantState", thread.get("dominantState"))
                .evidence("samples", thread.get("samples"))
                .build());
      }
    }

    Object patterns = insights.get("patterns");
    if (patterns instanceof List<?> patternList) {
      for (Object pattern : patternList) {
        if (pattern == null) {
          continue;
        }
        String text = String.valueOf(pattern);
        findings.add(
            Finding.of("threads", "pattern-" + text)
                .info()
                .title(text)
                .source(source)
                .evidence("stateDistribution", tsaResult.get("stateDistribution"))
                .build());
      }
    }

    return findings;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
  }
}

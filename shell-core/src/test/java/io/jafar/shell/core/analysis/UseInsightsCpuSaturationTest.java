package io.jafar.shell.core.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #263: the USE insights and summary read {@code cpu.saturation.value} as a percentage even
 * when it was a count. They now act only on {@code "unit": "%"} and word the insight by {@code
 * source}. Issue #297: with no share to weigh, the summary is UNKNOWN, not HEALTHY.
 */
class UseInsightsCpuSaturationTest {

  private final JfrAnalyses analyses = new JfrAnalyses();

  @Test
  void throttlingInsightNamesTheContainerLimit() {
    List<?> recommendations = recommendations(cpu(84.3, "%", "containerThrottling"));

    assertEquals(
        List.of(
            "Raise the container's CPU limit or reduce CPU demand: throttled in 84.3% of CPU"
                + " periods"),
        recommendations);
  }

  @Test
  void threadStateInsightNamesBlocking() {
    assertEquals(
        List.of("Investigate thread blocking: 45.0% of samples in blocking states"),
        recommendations(cpu(45.0, "%", "threadStates")));
  }

  @Test
  void aCountIsNeitherAnInsightNorTheWorstResource() {
    Map<String, Object> metrics = cpu(16356L, "slices", "containerThrottling");

    Map<String, Object> insights = analyses.generateUseInsights(metrics);
    assertEquals(List.of(), insights.get("bottlenecks"));
    // Nor a basis for a verdict (#297)
    assertEquals(
        List.of("No resource reported a saturation share - no verdict"),
        insights.get("recommendations"));
    assertEquals("UNKNOWN", analyses.generateUseSummary(metrics).get("overallAssessment"));
  }

  @Test
  void noShareAtAllIsNoVerdict() {
    // #297: a recording without the data (here memory with no heap figures, I/O with no events)
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("cpu", Map.of("error", "Failed to analyze CPU"));
    metrics.put("memory", Map.of("assessment", "UNKNOWN"));
    metrics.put("io", Map.of("assessment", "NO_IO"));

    Map<String, Object> summary = analyses.generateUseSummary(metrics);
    assertEquals("UNKNOWN", summary.get("overallAssessment"));
    assertEquals(
        "No resource reported a saturation share; see each resource's utilization and assessment",
        summary.get("note"));
    assertEquals(
        List.of("No resource reported a saturation share - no verdict"), recommendations(metrics));
  }

  @Test
  void aBottleneckTheInsightsReportIsNeverHealthyOrUnknown() {
    // #297: memory under pressure beside a 0% CPU share, and with no share at all
    Map<String, Object> withShare = cpu(0.0, "%", "threadStates");
    withShare.put("memory", Map.of("assessment", "HIGH_PRESSURE"));
    Map<String, Object> withoutShare = new LinkedHashMap<>();
    withoutShare.put("memory", Map.of("assessment", "HIGH_PRESSURE"));

    for (Map<String, Object> metrics : List.of(withShare, withoutShare)) {
      Map<String, Object> summary = analyses.generateUseSummary(metrics);
      assertEquals("NEEDS_ATTENTION", summary.get("overallAssessment"), summary.toString());
      assertEquals(List.of("memory_pressure"), summary.get("bottlenecks"), summary.toString());
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void anErroredResourceGivesNoShare() {
    Map<String, Object> metrics = cpu(0.0, "%", "threadStates");
    ((Map<String, Object>) metrics.get("cpu")).put("error", "Failed to analyze CPU");

    assertEquals("UNKNOWN", analyses.generateUseSummary(metrics).get("overallAssessment"));
  }

  @Test
  void aShareBelowTheBottleneckThresholdIsAcceptable() {
    Map<String, Object> summary = analyses.generateUseSummary(cpu(20.0, "%", "threadStates"));

    assertEquals("ACCEPTABLE", summary.get("overallAssessment"), summary.toString());
    assertEquals("cpu", summary.get("worstResource"), summary.toString());
    assertEquals(null, summary.get("bottlenecks"), summary.toString());
  }

  @Test
  void aMeasuredZeroShareIsHealthy() {
    Map<String, Object> metrics = cpu(0.0, "%", "threadStates");

    assertEquals("HEALTHY", analyses.generateUseSummary(metrics).get("overallAssessment"));
    assertEquals(
        List.of("No significant bottlenecks detected - system appears healthy"),
        recommendations(metrics));
  }

  @Test
  void summaryWeighsAPercentage() {
    assertEquals(
        "NEEDS_ATTENTION",
        analyses
            .generateUseSummary(cpu(84.3, "%", "containerThrottling"))
            .get("overallAssessment"));
  }

  private List<?> recommendations(Map<String, Object> metrics) {
    return (List<?>) analyses.generateUseInsights(metrics).get("recommendations");
  }

  private static Map<String, Object> cpu(Object value, String unit, String source) {
    Map<String, Object> saturation = new LinkedHashMap<>();
    saturation.put("value", value);
    saturation.put("unit", unit);
    saturation.put("source", source);
    Map<String, Object> cpu = new LinkedHashMap<>();
    cpu.put("saturation", saturation);
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("cpu", cpu);
    return metrics;
  }
}

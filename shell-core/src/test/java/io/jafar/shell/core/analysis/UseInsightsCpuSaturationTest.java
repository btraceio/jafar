package io.jafar.shell.core.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #263: the USE insights and summary read {@code cpu.saturation.value} as a percentage even
 * when it was a count. They now act only on {@code "unit": "%"} and word the insight by {@code
 * source}.
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
    assertEquals(
        List.of("No significant bottlenecks detected - system appears healthy"),
        insights.get("recommendations"));
    assertEquals("HEALTHY", analyses.generateUseSummary(metrics).get("overallAssessment"));
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

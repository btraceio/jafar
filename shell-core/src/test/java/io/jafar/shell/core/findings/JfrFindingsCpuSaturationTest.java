package io.jafar.shell.core.findings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #263: {@code jfr_use} put a count in {@code cpu.saturation.value} (throttled CFS periods,
 * or samples in blocking states) and the finding read it as a percentage: "16356.0% of CPU time
 * spent waiting or blocked". The finding now needs {@code unit: "%"} and words itself by source.
 */
class JfrFindingsCpuSaturationTest {

  @Test
  void containerThrottlingIsReportedAsThrottlingNotAsBlocking() {
    Finding f = single(saturation(45.0, "%", "containerThrottling"));

    assertEquals("CPU saturation: container throttled in 45.0% of CPU periods", f.title());
    assertTrue(f.action().contains("CPU limit"), f.action());
    assertEquals(45.0, f.evidence().get("saturationPct"));
  }

  @Test
  void blockedThreadStatesAreReportedAsBlocking() {
    Finding f = single(saturation(45.0, "%", "threadStates"));

    assertEquals("CPU saturation: 45.0% of execution samples in blocking states", f.title());
    assertTrue(f.action().contains("jfr_tsa"), f.action());
  }

  @Test
  void aValueThatIsNotAPercentageRaisesNoFinding() {
    // The shape main produced: 16356 throttled periods, "unit": "slices"
    assertTrue(
        JfrFindings.fromUse(saturation(16356L, "slices", "containerThrottling"), "t").isEmpty());
    assertTrue(JfrFindings.fromUse(saturation(16356L, null, null), "t").isEmpty());
  }

  @Test
  void aShareAtOrBelowThirtyPercentRaisesNoFinding() {
    assertTrue(JfrFindings.fromUse(saturation(30.0, "%", "threadStates"), "t").isEmpty());
  }

  private static Finding single(Map<String, Object> metrics) {
    List<Finding> findings = JfrFindings.fromUse(metrics, "test");
    assertEquals(1, findings.size(), findings.toString());
    return findings.get(0);
  }

  private static Map<String, Object> saturation(Object value, String unit, String source) {
    Map<String, Object> saturation = new LinkedHashMap<>();
    saturation.put("value", value);
    if (unit != null) {
      saturation.put("unit", unit);
    }
    if (source != null) {
      saturation.put("source", source);
    }
    Map<String, Object> cpu = new LinkedHashMap<>();
    cpu.put("saturation", saturation);
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("cpu", cpu);
    return metrics;
  }
}

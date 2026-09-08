package io.jafar.mcp.findings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Derives {@link Finding}s from the USE analyses of the sampling profile formats (pprof and OTLP).
 *
 * <p>These formats carry much less than JFR: there are no real thread-state transitions, no GC
 * events and no monitor events, so their USE analysis infers what it can from function names. Every
 * finding produced here therefore records {@code heuristic=true} in its evidence and says so in the
 * description, because a reader who cannot tell an inferred signal from a measured one will
 * over-trust it.
 */
public final class SamplingFindings {

  private SamplingFindings() {}

  /**
   * @param resourceMetrics the {@code resources} map from a {@code pprof_use} or {@code otlp_use}
   *     result
   * @param source the tool name to attribute findings to
   */
  @SuppressWarnings("unchecked")
  public static List<Finding> fromUse(Map<String, Object> resourceMetrics, String source) {
    List<Finding> findings = new ArrayList<>();
    if (resourceMetrics == null) {
      return findings;
    }

    Map<String, Object> threads = asMap(resourceMetrics.get("threads"));
    if (threads != null) {
      Map<String, Object> saturation = asMap(threads.get("saturation"));
      if (saturation != null && saturation.get("finding") != null) {
        findings.add(
            Finding.of("threads", "serial-execution")
                .warning()
                .title("%s", String.valueOf(saturation.get("finding")))
                .description(
                    "Derived from the distribution of samples across threads. It shows where the"
                        + " samples landed, not whether the work could have been parallelised.")
                .source(source)
                .evidence("heuristic", true)
                .evidence("saturation", saturation)
                .action("Check whether the dominant thread is doing parallelisable work")
                .build());
      }
    }

    Map<String, Object> errors = asMap(resourceMetrics.get("errors"));
    if (errors != null) {
      Object suspectsObj = errors.get("suspectFunctions");
      if (suspectsObj instanceof List<?> suspects && !suspects.isEmpty()) {
        findings.add(
            Finding.of("errors", "hot-error-paths")
                .info()
                .title("%d error-related function(s) appear in hot paths", suspects.size())
                .description(
                    "Matched by name against error-related keywords, not by observing a thrown"
                        + " exception. Confirm against the source before treating it as a finding.")
                .source(source)
                .evidence("heuristic", true)
                .evidence("suspectCount", suspects.size())
                .evidence("suspectFunctions", suspects)
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

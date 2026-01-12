package io.jafar.shell.llm.tuning;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Collects all test results for a single prompt variant and calculates aggregate metrics. */
public class TuningResults {
  private final String variantId;
  private final List<TestResult> results;
  private final long durationMs;

  public TuningResults(String variantId) {
    this(variantId, new ArrayList<>(), 0);
  }

  public TuningResults(String variantId, List<TestResult> results, long durationMs) {
    this.variantId = variantId;
    this.results = results;
    this.durationMs = durationMs;
  }

  public String getVariantId() {
    return variantId;
  }

  public List<TestResult> getResults() {
    return results;
  }

  public long getDurationMs() {
    return durationMs;
  }

  /**
   * Adds a test result to the collection.
   *
   * @param result the test result to add
   */
  public void addResult(TestResult result) {
    results.add(result);
  }

  /**
   * Calculates aggregate metrics for all test results.
   *
   * @return tuning metrics
   */
  public TuningMetrics calculateMetrics() {
    int total = results.size();
    int successes = (int) results.stream().filter(TestResult::isSuccess).count();
    int syntaxValid = (int) results.stream().filter(TestResult::syntaxValid).count();
    int semanticMatch = (int) results.stream().filter(TestResult::semanticMatch).count();

    Map<String, Long> errorCategories =
        results.stream()
            .filter(r -> !r.isSuccess())
            .collect(Collectors.groupingBy(TestResult::getErrorCategory, Collectors.counting()));

    return new TuningMetrics(
        variantId,
        total,
        successes,
        (double) successes / total,
        syntaxValid,
        semanticMatch,
        errorCategories,
        durationMs);
  }

  /**
   * Returns only the failed test results.
   *
   * @return list of failures
   */
  public List<TestResult> getFailures() {
    return results.stream().filter(r -> !r.isSuccess()).collect(Collectors.toList());
  }
}

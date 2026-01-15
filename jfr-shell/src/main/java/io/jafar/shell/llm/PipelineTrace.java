package io.jafar.shell.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * Debug trace for pipeline execution. Captures timing and size information for each step when
 * opt-in debugging is enabled via jfr.shell.debug=true.
 */
public class PipelineTrace {

  private final List<StepTrace> steps = new ArrayList<>();

  /**
   * Add a step trace without prompt size.
   *
   * @param name step name
   * @param details step details
   * @param durationMs duration in milliseconds
   */
  public void addStep(String name, String details, long durationMs) {
    addStep(name, details, durationMs, 0);
  }

  /**
   * Add a step trace with prompt size.
   *
   * @param name step name
   * @param details step details
   * @param durationMs duration in milliseconds
   * @param promptSize prompt size in characters
   */
  public void addStep(String name, String details, long durationMs, int promptSize) {
    steps.add(new StepTrace(name, details, durationMs, promptSize));
  }

  /**
   * Get all step traces.
   *
   * @return immutable list of step traces
   */
  public List<StepTrace> getSteps() {
    return List.copyOf(steps);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("Pipeline Trace:\n");
    for (StepTrace step : steps) {
      sb.append(String.format("  [%s] %s (%dms", step.name, step.details, step.durationMs));
      if (step.promptSize > 0) {
        sb.append(String.format(", prompt=%d chars", step.promptSize));
      }
      sb.append(")\n");
    }
    return sb.toString();
  }

  /**
   * Trace record for a single pipeline step.
   *
   * @param name step name
   * @param details step details
   * @param durationMs duration in milliseconds
   * @param promptSize prompt size in characters (0 if not applicable)
   */
  public record StepTrace(String name, String details, long durationMs, int promptSize) {}
}

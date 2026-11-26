package io.jafar.shell.core.compare;

/**
 * One sample type a profile file offers: for pprof a {@code (type, unit)} column, for JFR an
 * execution-sample event type.
 *
 * @param name the sample type's name ({@code cpu}, {@code jdk.ExecutionSample})
 * @param unit its unit ({@code nanoseconds}, {@code count}); {@code null} for a JFR event type,
 *     which is selected by its name alone ({@code --event-type jdk.ExecutionSample})
 * @param kind what it measures
 */
public record SampleTypeInfo(String name, String unit, SamplerKind kind) {

  /**
   * The form the option that selects it accepts: {@code name/unit} for pprof ({@code
   * --sample-type}), the bare event type for JFR ({@code --event-type}).
   */
  public String label() {
    return unit == null || unit.isEmpty() ? name : name + "/" + unit;
  }
}

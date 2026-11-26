package io.jafar.shell.core.compare;

/**
 * One sample type a profile file offers: for pprof a {@code (type, unit)} column, for JFR an
 * execution-sample event type (with unit {@code count}).
 *
 * @param name the sample type's name ({@code cpu}, {@code jdk.ExecutionSample})
 * @param unit its unit ({@code nanoseconds}, {@code count})
 * @param kind what it measures
 */
public record SampleTypeInfo(String name, String unit, SamplerKind kind) {

  /** {@code name/unit}, the form {@code --sample-type} accepts. */
  public String label() {
    return unit == null || unit.isEmpty() ? name : name + "/" + unit;
  }
}

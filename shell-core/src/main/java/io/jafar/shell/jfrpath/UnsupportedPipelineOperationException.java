package io.jafar.shell.jfrpath;

/**
 * Thrown when a JfrPath pipeline operator that requires the full row set (aggregations, ordering,
 * windowing — e.g. {@code count()}, {@code groupBy()}, {@code top()}) is applied in a per-event
 * context such as live {@code tail}, where events arrive one at a time and no finite row set exists
 * to aggregate over.
 */
public class UnsupportedPipelineOperationException extends IllegalArgumentException {
  private final String operation;

  public UnsupportedPipelineOperationException(String operation) {
    super(
        "Pipeline operator '"
            + operation
            + "()' requires the full row set and is not supported per event "
            + "(live tail supports per-event filters and transforms only)");
    this.operation = operation;
  }

  /** The rejected operator's name, without parentheses. */
  public String getOperation() {
    return operation;
  }
}

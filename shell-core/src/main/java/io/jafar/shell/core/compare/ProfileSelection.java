package io.jafar.shell.core.compare;

/**
 * What to read from a profile file.
 *
 * @param sampleType the sample type: for JFR an event type, for pprof {@code type} or {@code
 *     type/unit}. {@code null} or blank for the reader's default (JFR: the detected
 *     execution-sample event type; pprof: the first CPU sample type, else the first)
 * @param inclusive whether to also count inclusive frames (every frame on the stack, once per
 *     sample)
 */
public record ProfileSelection(String sampleType, boolean inclusive) {

  public ProfileSelection {
    if (sampleType != null && sampleType.isBlank()) {
      sampleType = null;
    }
  }
}

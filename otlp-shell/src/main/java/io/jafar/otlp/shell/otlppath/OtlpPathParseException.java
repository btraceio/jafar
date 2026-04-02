package io.jafar.otlp.shell.otlppath;

import io.jafar.shell.core.sampling.path.SamplesPathParseException;

/** Thrown when an OTLP samples query string is syntactically invalid. */
public class OtlpPathParseException extends SamplesPathParseException {
  public OtlpPathParseException(String message) {
    super(message);
  }
}

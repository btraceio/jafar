package io.jafar.otelp.shell.otelppath;

import io.jafar.shell.core.sampling.path.SamplesPathParseException;

/** Thrown when an OTLP samples query string is syntactically invalid. */
public class OtelpPathParseException extends SamplesPathParseException {
  public OtelpPathParseException(String message) {
    super(message);
  }
}

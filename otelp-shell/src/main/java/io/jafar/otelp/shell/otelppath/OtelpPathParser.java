package io.jafar.otelp.shell.otelppath;

import io.jafar.shell.core.sampling.path.SamplesPath;
import io.jafar.shell.core.sampling.path.SamplesPathParseException;
import io.jafar.shell.core.sampling.path.SamplesPathParser;

/** Thin facade over {@link SamplesPathParser} for the OTLP profiling query language. */
public final class OtelpPathParser {

  private OtelpPathParser() {}

  /**
   * Parses an OTLP samples query string.
   *
   * @param input the query string
   * @return parsed {@link SamplesPath.Query}
   * @throws OtelpPathParseException if the query is syntactically invalid
   */
  public static SamplesPath.Query parse(String input) {
    try {
      return SamplesPathParser.parse(input);
    } catch (SamplesPathParseException e) {
      throw new OtelpPathParseException(e.getMessage());
    }
  }
}

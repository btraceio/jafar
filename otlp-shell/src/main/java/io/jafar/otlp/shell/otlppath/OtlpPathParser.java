package io.jafar.otlp.shell.otlppath;

import io.jafar.shell.core.sampling.path.SamplesPath;
import io.jafar.shell.core.sampling.path.SamplesPathParseException;
import io.jafar.shell.core.sampling.path.SamplesPathParser;

/** Thin facade over {@link SamplesPathParser} for the OTLP profiling query language. */
public final class OtlpPathParser {

  private OtlpPathParser() {}

  /**
   * Parses an OTLP samples query string.
   *
   * @param input the query string
   * @return parsed {@link SamplesPath.Query}
   * @throws OtlpPathParseException if the query is syntactically invalid
   */
  public static SamplesPath.Query parse(String input) {
    try {
      return SamplesPathParser.parse(input);
    } catch (SamplesPathParseException e) {
      throw new OtlpPathParseException(e.getMessage());
    }
  }
}

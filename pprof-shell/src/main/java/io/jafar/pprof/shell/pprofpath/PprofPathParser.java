package io.jafar.pprof.shell.pprofpath;

import io.jafar.shell.core.sampling.path.SamplesPath;
import io.jafar.shell.core.sampling.path.SamplesPathParseException;
import io.jafar.shell.core.sampling.path.SamplesPathParser;

/** Thin facade over {@link SamplesPathParser} for the pprof query language. */
public final class PprofPathParser {

  private PprofPathParser() {}

  /**
   * Parses a pprof samples query string.
   *
   * @param input the query string
   * @return parsed {@link SamplesPath.Query}
   * @throws PprofPathParseException if the query is syntactically invalid
   */
  public static SamplesPath.Query parse(String input) {
    try {
      return SamplesPathParser.parse(input);
    } catch (SamplesPathParseException e) {
      throw new PprofPathParseException(e.getMessage());
    }
  }
}

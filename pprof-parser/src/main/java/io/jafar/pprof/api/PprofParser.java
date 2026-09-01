package io.jafar.pprof.api;

import io.jafar.pprof.internal.PprofReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Main entry point for parsing pprof profile files.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * PprofProfile.Profile profile = PprofParser.parse(Path.of("cpu.pb.gz"));
 * profile.samples().forEach(sample -> System.out.println(sample.values()));
 * }</pre>
 */
public final class PprofParser {

  private PprofParser() {}

  /**
   * Parses a gzip-compressed pprof profile file.
   *
   * @param path path to the .pb.gz or .pprof file
   * @return parsed profile
   * @throws IOException if the file cannot be read or has invalid format
   * @throws NullPointerException if path is null
   */
  public static PprofProfile.Profile parse(Path path) throws IOException {
    Objects.requireNonNull(path, "path must not be null");
    return PprofReader.read(path);
  }
}

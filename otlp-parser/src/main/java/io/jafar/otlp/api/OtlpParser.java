package io.jafar.otlp.api;

import io.jafar.otlp.internal.OtlpReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Main entry point for parsing OTLP profiling signal files.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * OtlpProfile.ProfilesData data = OtlpParser.parse(Path.of("profiles.otlp"));
 * data.profiles().forEach(profile -> System.out.println(profile.samples().size()));
 * }</pre>
 */
public final class OtlpParser {

  private OtlpParser() {}

  /**
   * Parses a binary-encoded OTLP {@code ProfilesData} file.
   *
   * @param path path to the OTLP profiling signal file
   * @return parsed profiles data
   * @throws IOException if the file cannot be read or has invalid format
   * @throws NullPointerException if path is null
   */
  public static OtlpProfile.ProfilesData parse(Path path) throws IOException {
    Objects.requireNonNull(path, "path must not be null");
    return OtlpReader.read(path);
  }
}

package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.parser.api.TypedJafarParser;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.file.Paths;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Regression tests for typed-parser robustness against JFR recordings that contain type IDs and
 * metadata elements unknown to the parser.
 *
 * <p>Previously failing symptoms:
 *
 * <ul>
 *   <li>{@code NumberFormatException} in {@code MetadataRegion.onAttribute} for non-numeric {@code
 *       dst}/{@code gmtOffset} values
 *   <li>{@code NullPointerException} in {@code TypedParserContext.getClassTargetType} for metadata
 *       classes with no {@code name} attribute
 *   <li>{@code IOException} in {@code CheckpointEvent.readConstantPools} for constant-pool entries
 *       referencing type IDs absent from the chunk metadata
 *   <li>{@code BufferUnderflowException} in {@code StreamingChunkParser} when event framing is
 *       inconsistent
 * </ul>
 *
 * <p>Fixtures are stripped copies of real recordings (metadata and constant-pool events only; all
 * regular events removed), generated with {@link io.jafar.parser.JfrStripper}.
 */
public class UnknownJfrTypesRegressionTest {

  private static final String[] FIXTURE_NAMES = {
    "dd-trace-java-stripped/AZ0slzG9AADg9z1ELwL5qgAA.jfr",
    "dd-trace-java-stripped/AZ0slzKIAADGHszqgs61zAAA.jfr",
    "dd-trace-java-stripped/AZ0slzMAAAC9yoVJD_6NAwAA.jfr",
    "dd-trace-java-stripped/AZ0slzOAAABztmi8aZLb7QAA.jfr",
    "dd-trace-java-stripped/AZ0slzV0AABhmxhvl2xoaQAA.jfr"
  };

  @Test
  void allFixturesArePresent() {
    long found =
        Stream.of(FIXTURE_NAMES)
            .filter(
                n -> UnknownJfrTypesRegressionTest.class.getClassLoader().getResource(n) != null)
            .count();
    assertEquals(
        FIXTURE_NAMES.length, found, "All regression JFR fixtures must be on the test classpath");
  }

  static Stream<String> fixtures() {
    return Stream.of(FIXTURE_NAMES);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  void typedParserDoesNotThrow(String resourceName) throws Exception {
    URL url = UnknownJfrTypesRegressionTest.class.getClassLoader().getResource(resourceName);
    URI uri = url.toURI();
    String path = Paths.get(new File(uri).getAbsolutePath()).toString();

    try (TypedJafarParser parser = TypedJafarParser.open(path)) {
      parser.run();
    }
  }
}

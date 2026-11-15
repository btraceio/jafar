package io.jafar.parser.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.impl.NodePoolFixture.Shape;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The Go parser's tests read {@link NodePoolFixture}'s recordings from {@code
 * go-parser/jfr/testdata}, so both untyped parsers are tested against the same bytes. This fails
 * when a checked-in file differs from what {@link NodePoolFixture} builds (Verification.md R7: one
 * source of truth, and a test that fails on drift).
 *
 * <p>To regenerate after changing {@link NodePoolFixture} or {@link SyntheticRecording}, run this
 * test with {@code JAFAR_WRITE_FIXTURES=1} in the environment and commit the files:
 *
 * <pre>
 * JAFAR_WRITE_FIXTURES=1 ./gradlew :parser-core:test --tests io.jafar.parser.impl.SharedFixturesTest
 * </pre>
 */
class SharedFixturesTest {
  private static final String WRITE = "JAFAR_WRITE_FIXTURES";

  @ParameterizedTest
  @EnumSource(Shape.class)
  void goTestdataMatchesTheJavaFixture(Shape shape) throws Exception {
    Path file = testdata().resolve(shape.fileName());
    byte[] expected = NodePoolFixture.bytes(shape);
    if ("1".equals(System.getenv(WRITE))) {
      Files.createDirectories(file.getParent());
      Files.write(file, expected);
    }
    assertTrue(
        Files.exists(file),
        file + " is missing; regenerate it with " + WRITE + "=1 (class javadoc)");
    assertArrayEquals(
        expected,
        Files.readAllBytes(file),
        file + " differs from NodePoolFixture." + shape + "; regenerate it with " + WRITE + "=1");
  }

  /** {@code go-parser/jfr/testdata}, found from the working directory upwards. */
  private static Path testdata() {
    for (File dir = new File("").getAbsoluteFile(); dir != null; dir = dir.getParentFile()) {
      File candidate = new File(dir, "go-parser/jfr/testdata");
      if (new File(dir, "go-parser/go.mod").isFile()) {
        return candidate.toPath();
      }
    }
    throw new IllegalStateException(
        "go-parser/go.mod not found above " + new File("").getAbsolutePath());
  }
}

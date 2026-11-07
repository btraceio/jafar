package io.jafar.testrecordings;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.testrecordings.adversarial.AdversarialRecordings;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The adversarial seeds are deterministic and committed (AC-4.7, generation half), so they can be
 * drift-checked exactly the way {@code parser-core}'s {@code SharedFixturesTest} checks its shared
 * fixtures: a committed file that differs from what the generator builds fails, naming the regen
 * command. What each seed's parse outcome is, is pinned by parser-core's adversarial outcome tests;
 * this module only owns the bytes.
 *
 * <p>To regenerate after changing {@link AdversarialRecordings} or {@code RawChunkWriter}:
 *
 * <pre>
 * ./gradlew :test-recordings:generateAdversarialRecordings
 * </pre>
 */
class AdversarialRecordingsTest {
  private static final String REGEN = "./gradlew :test-recordings:generateAdversarialRecordings";

  @ParameterizedTest
  @EnumSource(AdversarialRecordings.class)
  void generationIsDeterministic(AdversarialRecordings recording) {
    // Built from constants only: two builds of the same case must be byte-identical, or the
    // committed-seed check below would be meaningless.
    assertArrayEquals(recording.bytes(), recording.bytes(), recording.fileName());
  }

  @ParameterizedTest
  @EnumSource(AdversarialRecordings.class)
  void committedSeedMatchesTheGenerator(AdversarialRecordings recording) throws Exception {
    Path seed = fuzzInputs().resolve(recording.fileName());
    assertTrue(
        Files.exists(seed),
        seed + " is missing; regenerate it with " + REGEN + " and commit the file");
    assertArrayEquals(
        recording.bytes(),
        Files.readAllBytes(seed),
        seed
            + " differs from AdversarialRecordings."
            + recording.name()
            + "; regenerate with "
            + REGEN);
  }

  /**
   * The Jazzer regression corpus directory ({@code parser-core/src/test/resources/io/jafar/parser/
   * fuzz/UntypedParserFuzzTestInputs/parseUntyped/}), found from the working directory upwards. The
   * repo root is recognised by {@code go-parser/go.mod}, the same marker {@code SharedFixturesTest}
   * walks up to, so this finds the same tree that test runs in.
   */
  private static Path fuzzInputs() {
    for (File dir = new File("").getAbsoluteFile(); dir != null; dir = dir.getParentFile()) {
      if (new File(dir, "go-parser/go.mod").isFile()) {
        return dir.toPath()
            .resolve(
                "parser-core/src/test/resources/io/jafar/parser/fuzz/UntypedParserFuzzTestInputs/parseUntyped");
      }
    }
    throw new IllegalStateException(
        "go-parser/go.mod not found above " + new File("").getAbsolutePath());
  }
}

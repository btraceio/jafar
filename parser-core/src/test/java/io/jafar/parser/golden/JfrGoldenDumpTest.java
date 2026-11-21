package io.jafar.parser.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.testsupport.GoldenFile;
import io.jafar.testsupport.JfrCorpus;
import io.jafar.testsupport.ProcessorCount;
import io.jafar.testsupport.jfr.JfrCanonicalDump;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The JFR golden-value-dump gate (AC-4.1): every {@link JfrCorpus} fixture is rendered through the
 * one canonical format ({@link JfrCanonicalDump} — events, constants and metadata) and its value
 * identity compared against the committed golden in {@code parser-core/src/test/golden}.
 *
 * <p>The golden holds the line count, a digest over the sorted dump lines and the per-event-type
 * counts, plus the fixture sha256 for the committed TCK recording; the two generated fixtures
 * record no fixture sha. Their bytes follow the identity hashes of the JVM that ran the generator
 * (the JMC writer orders constant pools by a hash map whose keys hash an enum constant), so without
 * a fixed identity hash they differed between JDK builds and between ways of starting the same JDK;
 * {@code generateTestRecordings} now fixes it with {@code -XX:hashCode=2}, but not every JVM that
 * differed has been tried with the flag, and a pinned sha would say nothing about parser
 * correctness anyway (see {@code test-recordings/README.md}). The digest, which is JVM-independent,
 * remains the gate; the full dump is materialized only on a mismatch, into {@code
 * build/golden-actual}, next to the new golden, for the review diff (Risk 4: the tck deep-resolved
 * dump is ~454 MB, measured 453,889,122 bytes; a green run never builds it).
 *
 * <p>A change to any parsed value — a seeded decode-path change, a swapped wrapped-constant
 * handling — fails here naming the fixture and the regeneration command (AC-4.2, the DataShapes
 * "shapes that lie" class). A real recording that replaced a generated fixture aborts the test
 * (SKIPPED, D1/AC-4.4) instead of comparing: its bytes drift with every download, so a golden over
 * them is meaningless.
 */
class JfrGoldenDumpTest {

  /** The documented regeneration command, printed in every failure and stored in every golden. */
  private static final String REGEN_COMMAND =
      "./gradlew :parser-core:test --tests '*JfrGoldenDumpTest' -Pjafar.golden.update --rerun";

  /**
   * Matrix wiring (AC-4.3): dumps must be deterministic at {@code ActiveProcessorCount=1} and
   * {@code =16}, so this class asserts that the {@code -Pjafar.activeProcessorCount=N} flag reached
   * its forked JVM (R9) before comparing against the committed digest.
   */
  @BeforeAll
  static void assertProcessorCountMatrixReachedThisJvm() {
    ProcessorCount.assertExpected();
  }

  @ParameterizedTest
  @EnumSource(JfrCorpus.class)
  void goldenDumpMatches(JfrCorpus corpus) throws IOException {
    Path recording = corpus.path();
    if (corpus.isGenerated()) {
      JfrCorpus.requireGenerated(recording);
    }
    GoldenFile.verify(
        corpus.fixtureName(),
        // Generated fixtures pin no fixture sha: their bytes depend on the identity hashes of the
        // JVM that ran the generator (see the class javadoc), while the digest below does not. The
        // committed TCK recording is byte-stable, so it keeps its sha pin.
        JfrCanonicalDump.digest(recording, true, !corpus.isGenerated()),
        () -> fullDump(recording),
        REGEN_COMMAND);
  }

  /**
   * The pin asymmetry is load-bearing, so it is asserted, not just documented: the generated
   * fixtures' goldens keep an empty {@code fixture-sha256} line (their bytes follow the generator
   * JVM's identity hashes), while the committed TCK recording keeps a 64-hex pin. A stray
   * regeneration with the wrong pin would otherwise pass on the machine that produced it and fail
   * nowhere.
   */
  @ParameterizedTest
  @EnumSource(JfrCorpus.class)
  void goldenPinsFixtureShaOnlyForTheCommittedRecording(JfrCorpus corpus) throws IOException {
    String dir = System.getProperty(GoldenFile.GOLDEN_DIR_PROPERTY);
    if (dir == null || dir.isEmpty()) {
      throw new AssertionError(
          "system property " + GoldenFile.GOLDEN_DIR_PROPERTY + " is not set; run via Gradle");
    }
    String golden = Files.readString(Paths.get(dir).resolve(corpus.fixtureName() + ".golden"));
    String shaLine =
        golden
            .lines()
            .filter(l -> l.startsWith("fixture-sha256="))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no fixture-sha256 line in " + golden));
    String sha = shaLine.substring("fixture-sha256=".length());
    if (corpus.isGenerated()) {
      assertEquals("", sha, "generated fixture golden must not pin the JDK-build-dependent bytes");
    } else {
      assertTrue(
          sha.matches("[0-9a-f]{64}"),
          "committed TCK recording lost its fixture-sha256 pin: " + sha);
    }
  }

  /**
   * The mismatch artifact: the full sorted dump of the fixture. Only ever invoked by {@link
   * GoldenFile} after a mismatch was already established, never on a green or a regeneration run.
   */
  private static List<String> fullDump(Path recording) {
    try {
      return JfrCanonicalDump.events(recording);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot produce the golden mismatch dump for " + recording, e);
    }
  }
}

package io.jafar.parser.golden;

import io.jafar.testsupport.GoldenFile;
import io.jafar.testsupport.JfrCorpus;
import io.jafar.testsupport.jfr.JfrCanonicalDump;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The JFR golden-value-dump gate (AC-4.1): every {@link JfrCorpus} fixture is rendered through the
 * one canonical format ({@link JfrCanonicalDump} — events, constants and metadata) and its value
 * identity compared against the committed golden in {@code parser-core/src/test/golden}.
 *
 * <p>The golden holds the fixture sha256, the line count, a digest over the sorted dump lines and
 * the per-event-type counts; the full dump is materialized only on a mismatch, into {@code
 * build/golden-actual}, next to the new golden, for the review diff (Risk 4: the tck deep-resolved
 * dump is ~480 MB; a green run never builds it).
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

  @ParameterizedTest
  @EnumSource(JfrCorpus.class)
  void goldenDumpMatches(JfrCorpus corpus) throws IOException {
    Path recording = corpus.path();
    if (corpus.isGenerated()) {
      JfrCorpus.requireGenerated(recording);
    }
    GoldenFile.verify(
        corpus.fixtureName(),
        JfrCanonicalDump.digest(recording, true),
        () -> fullDump(recording),
        REGEN_COMMAND);
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

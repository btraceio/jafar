package io.jafar.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Assumptions;

/**
 * The single list (R7) of JFR fixtures the golden gate compares. Used by the parser-core golden
 * test, the typed/untyped parity extension and the Java/Go differential — three consumers, one
 * list, so a fixture can never be added to one net and forgotten in another.
 *
 * <p>Fixture paths resolve against the system property {@value #REPO_ROOT_PROPERTY} (the Gradle
 * test wiring sets it to the repo root). A missing property is a harness misconfiguration and fails
 * loudly, never silently resolves against the JVM's working directory (R3).
 *
 * <p>{@code test-dd.jfr} is deliberately NOT a corpus entry: it is a byte-for-byte copy of {@code
 * test-jfr.jfr}, so a golden for it would be a second, permanently identical file with no added
 * coverage.
 */
public enum JfrCorpus {

  /**
   * The committed TCK recording (two chunks, 67,657 events). A real recording, committed via a
   * {@code .gitignore} exception — it carries no {@code .generated} marker, so {@link
   * #requireGenerated(Path)} would (correctly) refuse it; the golden tests never call that guard on
   * this entry.
   */
  TCK_TEST("jfr-shell-tck/src/main/resources/tck-test.jfr", false),

  /** Deterministic recording written by {@code test-recordings}; regenerated, never committed. */
  TEST_AP("parser-core/src/test/resources/test-ap.jfr", true),

  /** Deterministic recording written by {@code test-recordings}; regenerated, never committed. */
  TEST_JFR("parser-core/src/test/resources/test-jfr.jfr", true);

  /** System property holding the repo root, set by the Gradle test wiring (see T20). */
  public static final String REPO_ROOT_PROPERTY = "jafar.repo.root";

  private final String relativePath;
  private final boolean generated;

  JfrCorpus(String relativePath, boolean generated) {
    this.relativePath = relativePath;
    this.generated = generated;
  }

  /** Resolves this entry against {@value #REPO_ROOT_PROPERTY} and checks the file exists. */
  public Path path() {
    String root = System.getProperty(REPO_ROOT_PROPERTY);
    if (root == null || root.isEmpty()) {
      throw new AssertionError(
          "system property "
              + REPO_ROOT_PROPERTY
              + " is not set: JfrCorpus cannot locate the repo's JFR fixtures. It is set by the "
              + "Gradle test wiring, so run the golden tests through Gradle, not a bare JVM (R3).");
    }
    Path resolved = Paths.get(root).resolve(relativePath);
    if (!Files.isRegularFile(resolved)) {
      throw new AssertionError(
          "corpus fixture "
              + relativePath
              + " not found at "
              + resolved
              + " — generate it: ./gradlew :test-recordings:generateTestRecordings");
    }
    return resolved;
  }

  /**
   * The fixture's stable name, used as the golden-file stem ({@code <name>.golden}) and the {@code
   * fixture=} golden line. Deliberately not called {@code name()}: that is {@link Enum#name()},
   * which returns the constant id ({@code TCK_TEST}), not the file name.
   */
  public String fixtureName() {
    return Paths.get(relativePath).getFileName().toString();
  }

  /**
   * Whether {@code test-recordings} generates this fixture (as opposed to a committed recording).
   * Only generated fixtures may be passed to {@link #requireGenerated(Path)}; call it before any
   * golden compare so a real recording that replaced a generated one aborts the test (D1) instead
   * of silently producing a golden for different bytes.
   */
  public boolean isGenerated() {
    return generated;
  }

  /** The repo-relative path of this entry, pinned by the corpus tests (R7). */
  String relativePath() {
    return relativePath;
  }

  /**
   * Refuses to golden-compare a real recording (D1, AC-4.4): {@code get_resources.sh} replaces
   * generated recordings with real ones, and the generator marks its output with a sibling {@code
   * <file>.generated} marker. When the marker is absent, the fixture is real — its bytes drift with
   * every download, so a golden over them is meaningless. The test is aborted (SKIPPED) with the
   * remedy; it is never compared and never failed on a mismatch it cannot meaningfully detect.
   *
   * <p>The guard only fires when the fixture itself exists: a missing file is a different failure
   * (and is reported by {@link #path()}), not a "real recording" situation.
   */
  public static void requireGenerated(Path fixture) {
    if (!Files.isRegularFile(fixture)) {
      return;
    }
    Path marker = fixture.resolveSibling(fixture.getFileName().toString() + ".generated");
    if (!Files.exists(marker)) {
      Assumptions.abort(
          fixture
              + " is a real recording (no .generated marker): golden compare refused. "
              + "Fix: delete it, then ./gradlew :test-recordings:generateTestRecordings");
    }
  }
}

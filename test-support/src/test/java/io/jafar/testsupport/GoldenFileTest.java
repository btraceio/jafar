package io.jafar.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

/**
 * Permanent proofs of every {@link GoldenFile} and {@link JfrCorpus} outcome (AC-4.2, AC-4.4): the
 * golden format is exactly one format, a match passes without ever materializing the full dump, and
 * every fire path — a changed dump line, a changed count, a changed fixture sha, a missing golden,
 * an empty golden — fails loudly naming everything needed to act on it. Every case drives the real
 * {@code verify()} through temp directories, so the tested surface is exactly the one the golden
 * tests of the parser and format modules use.
 */
class GoldenFileTest {

  private static final String FIXTURE = "tck-test.jfr";
  private static final String REGEN_COMMAND =
      "./gradlew :parser-core:test --tests '*JfrGoldenDumpTest' -Pjafar.golden.update --rerun";
  private static final String FIXTURE_SHA_A =
      "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111";
  private static final String FIXTURE_SHA_B =
      "bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222";

  /** Lines of the "recorded" dump; sorted, as every producer guarantees. */
  private static final List<String> LINES_A =
      Arrays.asList("jdk.ExecutionSample sampledThread=main", "jdk.ObjectAllocationInNewTLAB t=1");

  /** Same dump with one changed value — the seeded-decode-mutation shape. */
  private static final List<String> LINES_B =
      Arrays.asList("jdk.ExecutionSample sampledThread=main", "jdk.ObjectAllocationInNewTLAB t=2");

  @TempDir Path dir;

  private Path goldenDir() {
    return dir.resolve("goldens");
  }

  private Path actualDir() {
    return dir.resolve("build").resolve("golden-actual");
  }

  private Path goldenPath() {
    return goldenDir().resolve(FIXTURE + ".golden");
  }

  // --- happy paths --------------------------------------------------------

  /** (1) A match passes, never invokes the dump supplier, and writes nothing anywhere. */
  @Test
  void matchPassesWithoutInvokingTheDumpSupplier() {
    writeGolden(digestA());
    final boolean[] invoked = new boolean[1];
    withGoldenWiring(
        goldenDir(),
        actualDir(),
        null,
        () -> {
          GoldenFile.verify(FIXTURE, digestA(), () -> marked(invoked, LINES_A), REGEN_COMMAND);
          assertFalse(
              invoked[0],
              "a matching golden must not materialize the full dump — the tck dump is ~480 MB "
                  + "(Risk 4)");
          assertFalse(
              Files.exists(actualDir()),
              "a green run must not write mismatch artifacts into the build dir");
        });
  }

  /**
   * (2) Regen mode ({@code jafar.golden.update=true}, behind {@code -Pjafar.golden.update}) writes
   * the golden and passes, without invoking the dump supplier — the golden never contains dump
   * lines.
   */
  @Test
  void updateModeWritesTheGoldenAndPasses() throws IOException {
    writeGolden(digestA());
    final boolean[] invoked = new boolean[1];
    withGoldenWiring(
        goldenDir(),
        actualDir(),
        "true",
        () -> {
          try (StdoutCapture captured = captureStdout()) {
            GoldenFile.verify(FIXTURE, digestB(), () -> marked(invoked, LINES_B), REGEN_COMMAND);
            String printed = captured.text();
            assertTrue(
                printed.contains("golden written: " + goldenPath()),
                "expected the regen mode to print the written path, got: " + printed);
          }
          assertFalse(invoked[0], "regen mode must not materialize the full dump");
        });
    assertEquals(
        digestB().digest(),
        readGoldenLine(goldenPath(), "digest="),
        "the regenerated golden must carry the new digest");
  }

  /**
   * (3) The golden format is exactly the one format (R7): header, fixture, fixture-sha256, lines,
   * digest, then counts sorted by type — the counts are handed over unsorted and must still come
   * out sorted.
   */
  @Test
  void goldenFormatIsExact() throws IOException {
    writeGolden(digestA());
    String expected =
        "# golden v1 — regenerate: "
            + REGEN_COMMAND
            + "\n"
            + "fixture="
            + FIXTURE
            + "\n"
            + "fixture-sha256="
            + FIXTURE_SHA_A
            + "\n"
            + "lines="
            + LINES_A.size()
            + "\n"
            + "digest="
            + digestA().digest()
            + "\n"
            + "count.jdk.ExecutionSample=1\n"
            + "count.jdk.ObjectAllocationInNewTLAB=1\n";
    assertEquals(
        expected,
        new String(Files.readAllBytes(goldenPath()), StandardCharsets.UTF_8),
        "the golden text must be exactly the v1 format");
  }

  /** (4) A null fixture sha (the hdump case: unstable generator bytes) writes an empty field. */
  @Test
  void nullFixtureShaWritesAnEmptyFieldAndStillVerifies() throws IOException {
    DumpDigest noSha = DumpDigest.of(null, LINES_A, countsA());
    withGoldenWiring(
        goldenDir(),
        actualDir(),
        "true",
        () -> GoldenFile.verify(FIXTURE, noSha, never(), REGEN_COMMAND));
    assertTrue(
        readGoldenRaw().contains("fixture-sha256=\n"),
        "a null fixture sha must render as an empty fixture-sha256 line");
    withGoldenWiring(
        goldenDir(),
        actualDir(),
        null,
        () -> GoldenFile.verify(FIXTURE, noSha, never(), REGEN_COMMAND));
  }

  // --- fire paths ---------------------------------------------------------

  /**
   * (5) A changed dump line fails naming the fixture, the first differing golden line, both
   * digests, both artifact paths and the regen command — and writes the full dump plus the new
   * golden beside it for the review diff (AC-4.2).
   */
  @Test
  void changedDumpLineFailsNamingEverythingAndWritesTheArtifacts() throws IOException {
    writeGolden(digestA());
    final boolean[] invoked = new boolean[1];
    withGoldenWiring(
        goldenDir(),
        actualDir(),
        null,
        () -> {
          AssertionError failure =
              verifyFails(FIXTURE, digestB(), () -> marked(invoked, LINES_B), REGEN_COMMAND);
          String message = failure.getMessage();
          assertTrue(message.contains(FIXTURE), "must name the fixture, got: " + message);
          assertTrue(
              message.contains("first differing golden line"),
              "must point at the first differing line, got: " + message);
          assertTrue(
              message.contains("'digest=" + digestA().digest() + "'"),
              "the first differing line must quote the golden digest line, got: " + message);
          assertTrue(
              message.contains("'digest=" + digestB().digest() + "'"),
              "the first differing line must quote the new digest line, got: " + message);
          assertTrue(
              message.contains(digestA().digest()) && message.contains(digestB().digest()),
              "must name both digests, got: " + message);
          assertTrue(
              message.contains(goldenPath().toString()),
              "must name the golden path, got: " + message);
          assertTrue(
              message.contains(actualDir().resolve(FIXTURE + ".dump").toString()),
              "must name the dump path, got: " + message);
          assertTrue(
              message.contains(actualDir().resolve(FIXTURE + ".golden").toString()),
              "must name the new-golden path, got: " + message);
          assertTrue(
              message.contains(REGEN_COMMAND), "must name the regen command, got: " + message);
        });
    assertTrue(invoked[0], "a mismatch must materialize the full dump for the artifact");
    assertArtifactWritten(LINES_B, digestB());
  }

  /**
   * (6) A count change fails listing every differing count in both directions — golden-only and
   * actual-only types included (R9: never "all lines differ").
   */
  @Test
  void countChangeFailsNamingTheDifferingCounts() {
    Map<String, Long> goldenCounts = new HashMap<String, Long>();
    goldenCounts.put("jdk.ExecutionSample", 1L);
    goldenCounts.put("jdk.ObjectAllocationInNewTLAB", 1L);
    goldenCounts.put("jdk.GoldenOnly", 5L);
    writeGolden(DumpDigest.of(FIXTURE_SHA_A, LINES_A, goldenCounts));
    Map<String, Long> actualCounts = new HashMap<String, Long>();
    actualCounts.put("jdk.ExecutionSample", 1L);
    actualCounts.put("jdk.ObjectAllocationInNewTLAB", 2L);
    actualCounts.put("jdk.ActualOnly", 7L);
    withGoldenWiring(
        goldenDir(),
        actualDir(),
        null,
        () -> {
          AssertionError failure =
              verifyFails(
                  FIXTURE,
                  DumpDigest.of(FIXTURE_SHA_A, LINES_A, actualCounts),
                  () -> LINES_A,
                  REGEN_COMMAND);
          String message = failure.getMessage();
          assertTrue(
              message.contains("count.jdk.ObjectAllocationInNewTLAB: golden=1 actual=2"),
              "must list the changed count, got: " + message);
          assertTrue(
              message.contains("count.jdk.GoldenOnly: golden=5 actual=missing"),
              "must list the golden-only count, got: " + message);
          assertTrue(
              message.contains("count.jdk.ActualOnly: golden=missing actual=7"),
              "must list the actual-only count, got: " + message);
          assertFalse(
              message.contains("count.jdk.ExecutionSample:"),
              "an unchanged count must not be listed, got: " + message);
        });
  }

  /**
   * (7) A changed fixture sha is NOT a parser value change: the failure says so, names both shas,
   * and never materializes the dump — a dump of different fixture bytes cannot be diffed against
   * this golden anyway.
   */
  @Test
  void fixtureShaChangeFailsAsFixtureBytesChangedWithoutWritingTheDump() {
    writeGolden(digestA());
    withGoldenWiring(
        goldenDir(),
        actualDir(),
        null,
        () -> {
          AssertionError failure =
              verifyFails(
                  FIXTURE,
                  DumpDigest.of(FIXTURE_SHA_B, LINES_A, countsA()),
                  never(),
                  REGEN_COMMAND);
          String message = failure.getMessage();
          assertTrue(
              message.contains("fixture bytes changed (generator/builder output)"),
              "must say the fixture bytes changed, got: " + message);
          assertTrue(
              message.contains("not a parser value change"),
              "must say this is not a parser value change, got: " + message);
          assertTrue(
              message.contains(FIXTURE_SHA_A) && message.contains(FIXTURE_SHA_B),
              "must name both fixture shas, got: " + message);
          assertTrue(
              message.contains(REGEN_COMMAND), "must name the regen command, got: " + message);
        });
    assertFalse(
        Files.exists(actualDir()),
        "a fixture-bytes change must not write mismatch artifacts (the dump would describe "
            + "different fixture bytes)");
  }

  /** (8) A missing golden fails naming the path and the regen command. */
  @Test
  void missingGoldenFailsNamingThePathAndTheRegenCommand() {
    withGoldenWiring(
        goldenDir(),
        actualDir(),
        null,
        () -> {
          AssertionError failure = verifyFails(FIXTURE, digestA(), never(), REGEN_COMMAND);
          String message = failure.getMessage();
          assertTrue(
              message.contains(goldenPath().toString()),
              "must name the golden path, got: " + message);
          assertTrue(
              message.contains(REGEN_COMMAND), "must name the regen command, got: " + message);
        });
  }

  /**
   * (9) An empty golden fails loudly, naming both digests and the counts that differ (R9) — the
   * failure class of a stale or interrupted regeneration, never "all lines differ".
   */
  @Test
  void emptyGoldenFailsNamingBothDigestsAndTheDifferingCounts() throws IOException {
    Files.createDirectories(goldenDir());
    Files.write(goldenPath(), new byte[0]);
    withGoldenWiring(
        goldenDir(),
        actualDir(),
        null,
        () -> {
          AssertionError failure = verifyFails(FIXTURE, digestA(), () -> LINES_A, REGEN_COMMAND);
          String message = failure.getMessage();
          assertTrue(
              message.contains("the golden file is empty"),
              "must say the golden is empty, got: " + message);
          assertTrue(
              message.contains(digestA().digest()), "must name the actual digest, got: " + message);
          assertTrue(
              message.contains("no digest in golden"),
              "must say the golden has no digest, got: " + message);
          assertTrue(
              message.contains("count.jdk.ExecutionSample: golden=missing actual=1"),
              "must list the differing counts, got: " + message);
        });
    assertArtifactWritten(LINES_A, digestA());
  }

  // --- misconfiguration guards (R3) ---------------------------------------

  /** (10) A missing {@code jafar.golden.dir} fails naming the property, never guesses a default. */
  @Test
  void missingGoldenDirPropertyFails() {
    withGoldenWiring(
        null,
        actualDir(),
        null,
        () -> {
          AssertionError failure = verifyFails(FIXTURE, digestA(), never(), REGEN_COMMAND);
          assertTrue(
              failure.getMessage().contains("jafar.golden.dir"),
              "must name the missing property, got: " + failure.getMessage());
        });
  }

  /** (11) A missing {@code jafar.golden.actualDir} fails when a mismatch needs it, naming it. */
  @Test
  void missingActualDirPropertyFailsOnMismatch() {
    writeGolden(digestA());
    withGoldenWiring(
        goldenDir(),
        null,
        null,
        () -> {
          AssertionError failure = verifyFails(FIXTURE, digestB(), () -> LINES_B, REGEN_COMMAND);
          assertTrue(
              failure.getMessage().contains("jafar.golden.actualDir"),
              "must name the missing property, got: " + failure.getMessage());
        });
  }

  // --- JfrCorpus -----------------------------------------------------------

  /**
   * (12) The corpus is the pinned R7 list: three entries, exact repo-relative paths, exact
   * generated flags — and no {@code test-dd.jfr}, which is a byte copy of {@code test-jfr.jfr}.
   */
  @Test
  void corpusIsThePinnedListOfFixtures() throws IOException {
    Map<String, Boolean> expected = new LinkedHashMap<String, Boolean>();
    expected.put("jfr-shell-tck/src/main/resources/tck-test.jfr", false);
    expected.put("parser-core/src/test/resources/test-ap.jfr", true);
    expected.put("parser-core/src/test/resources/test-jfr.jfr", true);
    Path root = dir.resolve("repo-root");
    for (Map.Entry<String, Boolean> entry : expected.entrySet()) {
      Path fixture = root.resolve(entry.getKey());
      Files.createDirectories(fixture.getParent());
      Files.write(fixture, new byte[] {1});
    }
    withRepoRoot(
        root,
        () -> {
          assertEquals(
              expected.size(),
              JfrCorpus.values().length,
              "the corpus must contain exactly the pinned fixtures");
          for (JfrCorpus fixture : JfrCorpus.values()) {
            assertTrue(
                expected.containsKey(fixture.relativePath()),
                "unexpected corpus entry: " + fixture.relativePath());
            assertEquals(root.resolve(fixture.relativePath()), fixture.path());
            assertEquals(
                expected.get(fixture.relativePath()).booleanValue(), fixture.isGenerated());
            assertEquals(
                fixture.relativePath().substring(fixture.relativePath().lastIndexOf('/') + 1),
                fixture.fixtureName());
          }
        });
  }

  /** (13) A missing {@code jafar.repo.root} fails naming the property (R3). */
  @Test
  void missingRepoRootFailsNamingTheProperty() {
    String saved = System.clearProperty(JfrCorpus.REPO_ROOT_PROPERTY);
    try {
      for (JfrCorpus fixture : JfrCorpus.values()) {
        AssertionError failure = assertThrows(AssertionError.class, fixture::path);
        assertTrue(
            failure.getMessage().contains("jafar.repo.root"),
            "must name the missing property, got: " + failure.getMessage());
      }
    } finally {
      restoreProperty(JfrCorpus.REPO_ROOT_PROPERTY, saved);
    }
  }

  /**
   * (14) {@code requireGenerated} aborts (SKIPPED, D1, AC-4.4) on an unmarked real recording with
   * the exact remedy, and passes silently once the marker is beside the fixture.
   */
  @Test
  void requireGeneratedAbortsOnAnUnmarkedRealRecording() throws IOException {
    Path fixture = dir.resolve("test-ap.jfr");
    Files.write(fixture, new byte[] {1});
    TestAbortedException aborted =
        assertThrows(TestAbortedException.class, () -> JfrCorpus.requireGenerated(fixture));
    String message = aborted.getMessage();
    assertTrue(
        message.contains(fixture + " is a real recording (no .generated marker)"),
        "must name the file and the missing marker, got: " + message);
    assertTrue(
        message.contains("golden compare refused"), "must refuse the compare, got: " + message);
    assertTrue(
        message.contains("Fix: delete it, then ./gradlew :test-recordings:generateTestRecordings"),
        "must give the remedy, got: " + message);
    // With the marker present the guard passes — and a missing fixture is not its business.
    Files.write(fixture.resolveSibling("test-ap.jfr.generated"), new byte[] {0});
    JfrCorpus.requireGenerated(fixture);
    JfrCorpus.requireGenerated(dir.resolve("never-generated.jfr"));
  }

  // --- helpers -------------------------------------------------------------

  private static Map<String, Long> countsA() {
    Map<String, Long> counts = new HashMap<String, Long>();
    counts.put("jdk.ExecutionSample", 1L);
    counts.put("jdk.ObjectAllocationInNewTLAB", 1L);
    return counts;
  }

  private static DumpDigest digestA() {
    return DumpDigest.of(FIXTURE_SHA_A, LINES_A, countsA());
  }

  private static DumpDigest digestB() {
    return DumpDigest.of(FIXTURE_SHA_A, LINES_B, countsA());
  }

  private static List<String> marked(boolean[] invoked, List<String> lines) {
    invoked[0] = true;
    return lines;
  }

  /**
   * A supplier whose invocation fails the test — used where the dump must never be materialized.
   */
  private static Supplier<List<String>> never() {
    return () -> {
      throw new AssertionError("the full dump must not be materialized here (Risk 4)");
    };
  }

  /**
   * Writes the golden through the production regen mode, not through test-side reimplementation.
   */
  private void writeGolden(DumpDigest digest) {
    withGoldenWiring(
        goldenDir(),
        actualDir(),
        "true",
        () -> GoldenFile.verify(FIXTURE, digest, never(), REGEN_COMMAND));
  }

  private String readGoldenRaw() throws IOException {
    return new String(Files.readAllBytes(goldenPath()), StandardCharsets.UTF_8);
  }

  private String readGoldenLine(Path golden, String prefix) throws IOException {
    for (String line : Files.readAllLines(golden, StandardCharsets.UTF_8)) {
      if (line.startsWith(prefix)) {
        return line.substring(prefix.length());
      }
    }
    throw new AssertionError("no " + prefix + " line in " + golden);
  }

  /** The mismatch artifacts: the full dump and the new golden beside it (AC-4.2). */
  private void assertArtifactWritten(List<String> expectedDump, DumpDigest expectedNewGolden)
      throws IOException {
    Path dump = actualDir().resolve(FIXTURE + ".dump");
    Path newGolden = actualDir().resolve(FIXTURE + ".golden");
    StringBuilder expected = new StringBuilder();
    for (String line : expectedDump) {
      expected.append(line).append('\n');
    }
    assertEquals(
        expected.toString(),
        new String(Files.readAllBytes(dump), StandardCharsets.UTF_8),
        "the artifact dump must be the full sorted dump");
    assertEquals(
        expectedNewGolden.digest(),
        readGoldenLine(newGolden, "digest="),
        "the golden beside the dump must describe the actual digest");
  }

  /**
   * Runs {@link GoldenFile#verify} expecting an {@link AssertionError}; used where the abort
   * assertion cannot wrap a lambda that also returns something.
   */
  private static AssertionError verifyFails(
      String fixture, DumpDigest digest, Supplier<List<String>> dump, String regenCommand) {
    try {
      GoldenFile.verify(fixture, digest, dump, regenCommand);
    } catch (AssertionError failure) {
      return failure;
    }
    throw new AssertionError("expected verify(\"" + fixture + "\", …) to fail, but it passed");
  }

  /** Sets the golden wiring properties, runs {@code body}, restores the previous values. */
  private static void withGoldenWiring(
      Path goldenDir, Path actualDir, String update, Runnable body) {
    Map<String, String> saved = new LinkedHashMap<String, String>();
    setProperty(
        GoldenFile.GOLDEN_DIR_PROPERTY,
        goldenDir == null ? null : goldenDir.toAbsolutePath().toString(),
        saved);
    setProperty(
        GoldenFile.ACTUAL_DIR_PROPERTY,
        actualDir == null ? null : actualDir.toAbsolutePath().toString(),
        saved);
    setProperty(GoldenFile.UPDATE_PROPERTY, update, saved);
    try {
      body.run();
    } finally {
      for (Map.Entry<String, String> entry : saved.entrySet()) {
        restoreProperty(entry.getKey(), entry.getValue());
      }
    }
  }

  private static void withRepoRoot(Path root, Runnable body) {
    String saved =
        System.setProperty(JfrCorpus.REPO_ROOT_PROPERTY, root.toAbsolutePath().toString());
    try {
      body.run();
    } finally {
      restoreProperty(JfrCorpus.REPO_ROOT_PROPERTY, saved);
    }
  }

  private static void setProperty(String name, String value, Map<String, String> saved) {
    saved.put(name, System.getProperty(name));
    if (value == null) {
      System.clearProperty(name);
    } else {
      System.setProperty(name, value);
    }
  }

  private static void restoreProperty(String name, String saved) {
    if (saved == null) {
      System.clearProperty(name);
    } else {
      System.setProperty(name, saved);
    }
  }

  /** Captures {@code System.out} so the regen mode's printed path is asserted, not assumed (R9). */
  private static StdoutCapture captureStdout() {
    return new StdoutCapture(System.out);
  }

  private static final class StdoutCapture implements AutoCloseable {
    private final PrintStream original;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    StdoutCapture(PrintStream original) {
      this.original = original;
      System.setOut(new PrintStream(out));
    }

    String text() {
      try {
        return out.toString(StandardCharsets.UTF_8.name());
      } catch (UnsupportedEncodingException e) {
        throw new AssertionError("UTF-8 is somehow unsupported", e);
      }
    }

    @Override
    public void close() {
      System.out.flush();
      System.setOut(original);
    }
  }
}

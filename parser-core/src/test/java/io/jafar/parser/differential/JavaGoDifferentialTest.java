package io.jafar.parser.differential;

import io.jafar.testsupport.JfrCorpus;
import io.jafar.testsupport.jfr.JfrCanonicalDump;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The end-to-end Java/Go differential (safety-harness step 6; AC-6.1 and AC-6.2): every fixture of
 * {@link JfrCorpus} plus the shared {@code go-parser/jfr/testdata} node-pool recordings is dumped
 * twice — once by {@link JfrCanonicalDump#writeEventDump} ({@code side=java}, in this JVM) and once
 * by {@code go run ./internal/differential/cmd/jfrdump} ({@code side=go}, in a child process inside
 * the Go module) — and the two dumps are compared by {@link DumpComparator#compare} under the
 * committed allowlist. The comparator itself, and every way it must fire, is pinned by {@code
 * DumpComparatorTest}; this class proves the two real dumpers feed it agreeing dumps.
 *
 * <p>Unlike the golden tests, this net applies no fixture-stability guard ({@code
 * JfrCorpus.requireGenerated}): both sides read the same file within one run and both headers carry
 * its sha256, so a real recording replacing a generated one is still a perfectly valid differential
 * fixture — the compare never depends on the bytes being the ones a golden was recorded over.
 *
 * <p>Failures are loud by construction, never skips (R3): a {@code go} missing from PATH fails
 * naming PATH and the attempted command; a Go run that writes no dump at all fails inside {@code
 * DumpComparator.Dump.read} with "dump file missing"; and the child process's combined output is
 * always relayed into the test log (kb/probe-pitfalls 2: a failed dump run whose stderr was
 * discarded is indistinguishable from an empty recording). A <em>node-pool</em> fixture that both
 * parsers refuse passes through the comparator's both-sides-{@code error=} path: refusing the same
 * bytes for the same reason is agreement, not divergence. The one node-pool fixture whose refusal
 * is one-sided is deliberately not part of this corpus — see {@link #EXCLUDED_FIXTURE}.
 */
class JavaGoDifferentialTest {

  /** The Gradle wiring ({@code differentialTest}) sets this to the {@code go-parser} module dir. */
  private static final String GO_DIR_PROPERTY = "jafar.go.dir";

  /** The Go dumper, as a package path relative to the Go module (mirrors the plan's command). */
  private static final String JFRDUMP_PACKAGE = "./internal/differential/cmd/jfrdump";

  /**
   * Generous bound on one {@code go run} (compile plus parse of the largest corpus fixture). It is
   * a hang guard only — the differential compares values, never timings.
   */
  private static final long GO_TIMEOUT_MINUTES = 10;

  /**
   * The shared node-pool fixtures, read by both parsers' own test suites (go-parser/jfr/testdata).
   */
  private static final String TESTDATA_DIR = "go-parser/jfr/testdata";

  /**
   * The one shared node-pool fixture the differential must not compare. {@code
   * node-pool-dangling-name.jfr} is refused outright by the Java parser — resolving its undecodable
   * constant-pool entry throws from {@code getValue()} (Values.resolvedDeep has no per-entry catch)
   * — while the Go parser delivers all 7 events, rendering the entry as {@code null}. That is a
   * deliberate, documented divergence (go-parser/README.md, "Differences from the Java parser": "An
   * undecodable constant pool entry resolves to nil ... The Java parser throws from getValue()
   * instead"), not a defect this harness run fixes, and the differential allowlist cannot express
   * it: its entries name per-field paths, and this is a whole-recording one-sided refusal. The
   * comparator would therefore fire on it forever with no allowlist remedy. Only this exact fixture
   * name is skipped — any other recording that lands in testdata is compared — and the divergence
   * stays pinned by the parsers' own suites (UnindexedPoolReferenceTest.DANGLING_NAME in
   * parser-core and TestUndecodableEntryFailsOnResolutionTheSameWayEachTime in go-parser/jfr).
   */
  private static final String EXCLUDED_FIXTURE = "node-pool-dangling-name.jfr";

  /** The fixture list: the corpus entries, then the shared node-pool recordings, sorted. */
  static List<Arguments> fixtures() throws IOException {
    List<Arguments> out = new ArrayList<>();
    for (JfrCorpus fixture : JfrCorpus.values()) {
      out.add(Arguments.of(stem(fixture.fixtureName()), fixture.path()));
    }
    List<Path> recordings = new ArrayList<>();
    try (DirectoryStream<Path> jfrs = Files.newDirectoryStream(testDataDir(), "*.jfr")) {
      jfrs.forEach(recordings::add);
    }
    Collections.sort(recordings);
    for (Path recording : recordings) {
      if (recording.getFileName().toString().equals(EXCLUDED_FIXTURE)) {
        // visible in every run's log, never a silent gap (R3)
        System.out.println(
            "differential: "
                + EXCLUDED_FIXTURE
                + " is not compared — a deliberate Go/Java divergence (see "
                + "JavaGoDifferentialTest.EXCLUDED_FIXTURE)");
        continue;
      }
      out.add(Arguments.of(stem(recording.getFileName().toString()), recording.toAbsolutePath()));
    }
    return out;
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("fixtures")
  void javaAndGoAgree(String name, Path fixture) throws Exception {
    Path outDir = repoRoot().resolve("parser-core/build/differential");
    Files.createDirectories(outDir);
    Path javaDump = outDir.resolve(name + ".java.dump");
    Path goDump = outDir.resolve(name + ".go.dump");
    try {
      JfrCanonicalDump.writeEventDump(fixture, javaDump);
    } catch (IOException javaParseFailure) {
      // A recording the Java parser refuses must still get a dump, or the comparator could not
      // see that both sides refuse it (the node-pool error fixtures): write the error form of the
      // header, exactly the shape the Go dumper writes for its own failures. The message may
      // reveal the cause; the comparator only checks that both sides error.
      writeJavaErrorHeader(javaDump, javaParseFailure);
    }
    runJfrdump(fixture, goDump);
    DumpComparator.compare(javaDump, goDump, allowlist());
  }

  // --- the Go side ----------------------------------------------------------------------

  /**
   * Runs {@code go run ./internal/differential/cmd/jfrdump -o <goDump> <fixture>} in the Go module
   * and relays its output. A non-zero exit is not itself the failure: the fixtures both parsers
   * refuse exit 1 <em>with</em> an {@code error=} header, which the comparator must see; a non-zero
   * exit that produced no dump (a compile error, a usage error) fails loudly in {@code
   * DumpComparator.Dump.read}.
   */
  private static void runJfrdump(Path fixture, Path out) throws IOException, InterruptedException {
    Path goDir = goDir();
    Path relay = out.resolveSibling(out.getFileName() + ".jfrdump.log");
    Files.deleteIfExists(relay);
    ProcessBuilder builder =
        new ProcessBuilder(
                "go",
                "run",
                JFRDUMP_PACKAGE,
                "-o",
                out.toAbsolutePath().toString(),
                fixture.toAbsolutePath().toString())
            .directory(goDir.toFile())
            // Merged and redirected to a file: relaying a live pipe could deadlock on a chatty
            // run, and discarding the output turns a failed dump into an empty file
            // (kb/probe-pitfalls 2).
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(relay.toFile()));
    final Process go;
    try {
      go = builder.start();
    } catch (IOException goMissing) {
      // R3: the missing toolchain is a misconfiguration to report, never a condition to skip on —
      // a skipped differential is a green differential.
      throw new AssertionError(
          "go is not on PATH: the Java/Go differential shells out to `go run "
              + JFRDUMP_PACKAGE
              + "`, which needs the Go toolchain. Install Go or extend PATH. Attempted: `"
              + String.join(" ", builder.command())
              + "` in "
              + goDir,
          goMissing);
    }
    if (!go.waitFor(GO_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
      go.destroyForcibly();
      throw new AssertionError(
          "go dump run did not finish within "
              + GO_TIMEOUT_MINUTES
              + " minutes (command: `"
              + String.join(" ", builder.command())
              + "`); relayed output so far:\n"
              + readRelay(relay));
    }
    System.out.println(
        "jfrdump for "
            + out.getFileName()
            + " (exit "
            + go.exitValue()
            + "), output relayed:\n"
            + readRelay(relay));
  }

  /** The Go module directory; failing loudly when the wiring or the harness is missing (R3). */
  private static Path goDir() {
    String property = System.getProperty(GO_DIR_PROPERTY);
    if (property == null || property.isEmpty()) {
      throw new AssertionError(
          "system property "
              + GO_DIR_PROPERTY
              + " is not set: the differential runs through the differentialTest Gradle task,"
              + " which sets it to the go-parser module (R3)");
    }
    Path goDir = Paths.get(property);
    if (!Files.isDirectory(goDir.resolve("internal/differential/cmd/jfrdump"))) {
      throw new AssertionError(
          "the Go dump harness is missing at "
              + goDir.resolve("internal/differential/cmd/jfrdump")
              + " — go-parser/internal/differential landed in step 6 (T32)");
    }
    return goDir;
  }

  // --- paths, names and headers ----------------------------------------------------------

  private static Path repoRoot() {
    String root = System.getProperty(JfrCorpus.REPO_ROOT_PROPERTY);
    if (root == null || root.isEmpty()) {
      throw new AssertionError(
          "system property "
              + JfrCorpus.REPO_ROOT_PROPERTY
              + " is not set: it is provided by the Gradle test wiring, so run the differential"
              + " through ./gradlew :parser-core:differentialTest (R3)");
    }
    return Paths.get(root);
  }

  private static Path testDataDir() {
    Path dir = repoRoot().resolve(TESTDATA_DIR);
    if (!Files.isDirectory(dir)) {
      throw new AssertionError("shared node-pool fixture directory not found: " + dir);
    }
    return dir;
  }

  private static Path allowlist() {
    Path file = repoRoot().resolve("parser-core/src/test/resources/differential/allowlist.txt");
    if (!Files.isRegularFile(file)) {
      throw new AssertionError("differential allowlist not found: " + file);
    }
    return file;
  }

  /** The dump-file stem of a fixture name: {@code tck-test.jfr} → {@code tck-test}. */
  private static String stem(String fixtureName) {
    return fixtureName.endsWith(".jfr")
        ? fixtureName.substring(0, fixtureName.length() - ".jfr".length())
        : fixtureName;
  }

  /**
   * The Java-side {@code error=} header: the same one-line shape the Go dumper writes, with the
   * message's newlines escaped the same way, so an error message can never spill past the header
   * line the comparator validates.
   */
  private static void writeJavaErrorHeader(Path out, Exception failure) throws IOException {
    String message = failure.getMessage();
    if (message == null || message.isEmpty()) {
      message = failure.toString();
    }
    message = message.replace("\n", "\\n").replace("\r", "\\r");
    Files.write(
        out,
        (JfrCanonicalDump.DUMP_FORMAT + " side=java error=" + message + "\n")
            .getBytes(StandardCharsets.UTF_8));
  }

  private static String readRelay(Path relay) throws IOException {
    if (!Files.isRegularFile(relay)) {
      return "<no output>";
    }
    return new String(Files.readAllBytes(relay), StandardCharsets.UTF_8);
  }
}

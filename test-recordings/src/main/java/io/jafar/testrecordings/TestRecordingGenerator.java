package io.jafar.testrecordings;

import io.jafar.testrecordings.Shapes.Shape;
import io.jafar.testrecordings.adversarial.AdversarialRecordings;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * Generates the JFR recordings the test suites and benchmarks read, so that neither depends on
 * downloading them.
 *
 * <pre>
 * tests &lt;demo-resources-dir&gt; &lt;parser-core-resources-dir&gt; [test-dd-source.jfr]
 *     test-ap.jfr, test-jfr.jfr, test-dd.jfr in the first directory, and the copies parser-core's
 *     tests read in the second (its test-dd.jfr is a copy of test-jfr.jfr, as get_resources.sh
 *     has always made it). With a source, test-dd.jfr is a copy of it rather than generated: the
 *     build passes the checked-in tck-test.jfr, a real Datadog profiler recording whose shape
 *     jfr-mcp's end-to-end tests pin (204 types, two chunks, two jdk.JVMInformation events).
 *
 * benchmark &lt;output.jfr&gt; &lt;chunks&gt; [samples-per-chunk] [ap|jfr|dd]
 *     One recording of any size, written a chunk at a time.
 *
 * adversarial &lt;output-dir&gt;
 *     The adversarial fuzz-corpus seeds (adv-&lt;case&gt;), one file per case of
 *     io.jafar.testrecordings.adversarial.AdversarialRecordings, written with no .jfr extension
 *     because .gitignore ignores *.jfr and the seeds must be committed for Jazzer's regression
 *     corpus to replay.
 * </pre>
 *
 * <p>Every generated file gets a {@code .generated} marker next to it. A recording without a marker
 * is a real one (downloaded by {@code get_resources.sh}): it is left as it is and reported, so a
 * developer who fetched the real recordings keeps testing against them. CI never downloads, so it
 * always tests against generated ones.
 */
public final class TestRecordingGenerator {
  static final String MARKER_SUFFIX = ".generated";

  /** Execution samples per chunk in the test recordings, and chunks per recording. */
  private static final int TEST_SAMPLES_AP = 8_000;

  private static final int TEST_SAMPLES_JFR = 2_000;
  private static final int TEST_SAMPLES_DD = 6_000;

  private TestRecordingGenerator() {}

  public static void main(String[] args) throws IOException {
    if (args.length >= 3 && args[0].equals("tests")) {
      tests(Paths.get(args[1]), Paths.get(args[2]), args.length > 3 ? Paths.get(args[3]) : null);
    } else if (args.length >= 3 && args[0].equals("benchmark")) {
      Path out = Paths.get(args[1]);
      int chunks = Integer.parseInt(args[2]);
      int samples = args.length > 3 ? Integer.parseInt(args[3]) : 200_000;
      Shape shape = args.length > 4 ? Shape.valueOf(args[4].toUpperCase()) : Shape.AP;
      long t0 = System.nanoTime();
      write(out, shape, chunks, samples);
      System.out.printf(
          "Generated %s: %d chunk(s) of %s, %d bytes, %.1f s%n",
          out, chunks, shape, Files.size(out), (System.nanoTime() - t0) / 1e9);
    } else if (args.length >= 2 && args[0].equals("adversarial")) {
      adversarial(Paths.get(args[1]));
    } else {
      System.err.println(
          "usage: tests <demo-resources-dir> <parser-core-resources-dir> [test-dd-source.jfr]\n"
              + "       benchmark <output.jfr> <chunks> [samples-per-chunk] [ap|jfr|dd]\n"
              + "       adversarial <output-dir>\n"
              + "got: "
              + Arrays.toString(args));
      System.exit(2);
    }
  }

  /**
   * Writes every adversarial case as {@code adv-<case>} into {@code outDir}, overwriting what is
   * there: the committed seeds must always equal the generator's output.
   */
  private static void adversarial(Path outDir) throws IOException {
    Files.createDirectories(outDir);
    for (AdversarialRecordings recording : AdversarialRecordings.values()) {
      Path out = outDir.resolve(recording.fileName());
      Files.write(out, recording.bytes());
      System.out.println("Generated " + out + " (" + Files.size(out) + " bytes)");
    }
  }

  private static void tests(Path demoDir, Path parserDir, Path ddSource) throws IOException {
    Files.createDirectories(demoDir);
    Files.createDirectories(parserDir);
    Path ap = demoDir.resolve("test-ap.jfr");
    Path jfr = demoDir.resolve("test-jfr.jfr");
    Path dd = demoDir.resolve("test-dd.jfr");

    // One chunk each, like the recordings the suites were written against. Several tests assume a
    // single chunk (abort stopping all delivery, constant-pool ids being unique across the file),
    // and jfr-shell's constant-pool source races across parallel chunks; multi-chunk output is
    // covered by the benchmark mode.
    provide(ap, Shape.AP, 1, TEST_SAMPLES_AP);
    provide(jfr, Shape.JFR, 1, TEST_SAMPLES_JFR);
    if (ddSource != null) {
      provideCopy(dd, ddSource);
    } else {
      provide(dd, Shape.DD, 1, TEST_SAMPLES_DD);
    }

    mirror(ap, parserDir.resolve("test-ap.jfr"));
    mirror(jfr, parserDir.resolve("test-jfr.jfr"));
    mirror(jfr, parserDir.resolve("test-dd.jfr"));
  }

  /** Generates {@code target} unless a real recording is already there. */
  private static void provide(Path target, Shape shape, int chunks, int samples)
      throws IOException {
    if (isReal(target)) {
      System.out.println("Using real recording " + target + " (no " + MARKER_SUFFIX + " marker)");
      return;
    }
    write(target, shape, chunks, samples);
    markGenerated(target, shape + ", " + chunks + " chunk(s)");
    System.out.println("Generated " + target + " (" + Files.size(target) + " bytes)");
  }

  /** Copies {@code source} to {@code target} unless a real recording is already there. */
  private static void provideCopy(Path target, Path source) throws IOException {
    if (isReal(target)) {
      System.out.println("Using real recording " + target + " (no " + MARKER_SUFFIX + " marker)");
      return;
    }
    if (!isJfr(source)) {
      throw new IOException("Not a JFR recording: " + source);
    }
    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    markGenerated(target, "copy of " + source.getFileName());
    System.out.println(
        "Copied " + source + " to " + target + " (" + Files.size(target) + " bytes)");
  }

  /**
   * Makes {@code copy} the same recording as {@code source}, real or generated, unless {@code copy}
   * is itself a real recording.
   */
  private static void mirror(Path source, Path copy) throws IOException {
    if (isReal(copy)) {
      System.out.println("Using real recording " + copy + " (no " + MARKER_SUFFIX + " marker)");
      return;
    }
    Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
    Path sourceMarker = marker(source);
    if (Files.exists(sourceMarker)) {
      Files.copy(sourceMarker, marker(copy), StandardCopyOption.REPLACE_EXISTING);
    } else {
      Files.deleteIfExists(marker(copy));
    }
  }

  /** Writes {@code chunks} chunks to a temporary file, then moves it into place. */
  private static void write(Path target, Shape shape, int chunks, int samples) throws IOException {
    Path parent = target.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Path tmp = target.resolveSibling(target.getFileName() + ".part");
    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 20)) {
      for (int i = 0; i < chunks; i++) {
        Shapes.writeChunk(out, shape, i, samples);
      }
    }
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
  }

  private static boolean isReal(Path file) throws IOException {
    return Files.exists(file) && !Files.exists(marker(file)) && isJfr(file);
  }

  static boolean isJfr(Path file) throws IOException {
    byte[] magic = new byte[4];
    try (InputStream in = Files.newInputStream(file)) {
      return in.read(magic) == 4
          && magic[0] == 'F'
          && magic[1] == 'L'
          && magic[2] == 'R'
          && magic[3] == 0;
    }
  }

  static Path marker(Path file) {
    return file.resolveSibling(file.getFileName() + MARKER_SUFFIX);
  }

  private static void markGenerated(Path file, String what) throws IOException {
    Files.write(
        marker(file),
        ("Generated by the test-recordings module ("
                + what
                + ").\n"
                + "Run ./get_resources.sh to replace it with the real recording.\n")
            .getBytes(StandardCharsets.UTF_8));
  }
}

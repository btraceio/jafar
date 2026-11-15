package io.jafar.testsupport;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Compares a {@link DumpDigest} against the committed golden file of one fixture (AC-4.2). The
 * golden text format is exactly:
 *
 * <pre>{@code
 * # golden v1 — regenerate: <regenCommand>
 * fixture=<fixture>
 * fixture-sha256=<hex or empty>
 * lines=<n>
 * digest=<hex>
 * count.<type>=<n>        (sorted by type)
 * }</pre>
 *
 * <p>Golden files store the digest and per-type counts only, never dump lines, so they stay small
 * even for the ~480 MB tck-test.jfr deep-resolved dump (Risk 4). The full dump is produced by the
 * caller's supplier and written to the build dir only when a golden mismatches, for the review diff
 * — a green run never materializes it.
 *
 * <p>No outcome is silent (R9: assert payloads, not exit statuses): a missing, empty or stale
 * golden fails loudly, naming both digests and the per-type counts that differ; a changed fixture
 * sha256 is distinguished from a parser value change, because the remedy differs (regenerate the
 * fixture, not the golden).
 *
 * <p>System properties (set by the Gradle test wiring, T20):
 *
 * <ul>
 *   <li>{@value #GOLDEN_DIR_PROPERTY} — the committed golden directory; missing → AssertionError
 *       (R3);
 *   <li>{@value #ACTUAL_DIR_PROPERTY} — the build-dir mismatch artifacts ({@code
 *       build/golden-actual}); required only when a mismatch writes artifacts;
 *   <li>{@value #UPDATE_PROPERTY} — {@code true} regenerates the golden and passes (the regen mode
 *       behind {@code -Pjafar.golden.update}).
 * </ul>
 */
public final class GoldenFile {

  public static final String GOLDEN_DIR_PROPERTY = "jafar.golden.dir";
  public static final String ACTUAL_DIR_PROPERTY = "jafar.golden.actualDir";
  public static final String UPDATE_PROPERTY = "jafar.golden.update";

  private static final String HEADER_PREFIX = "# golden v1 — regenerate: ";

  private GoldenFile() {}

  /**
   * Verifies {@code digest} against {@code <jafar.golden.dir>/<fixture>.golden}.
   *
   * <p>{@code fullSortedDump} produces the entire dump, sorted, only for the mismatch artifact — it
   * is never invoked when the golden matches, when it is regenerated, or when the fixture bytes
   * changed (in the last case the dump would describe different fixture bytes and only mislead).
   *
   * @param fixture the fixture name (also the golden stem, e.g. {@code tck-test.jfr})
   * @param digest the dump's value identity
   * @param fullSortedDump lazy supplier of the full sorted dump lines
   * @param regenCommand the documented command that regenerates the golden, printed in every
   *     failure and stored in the golden header
   */
  public static void verify(
      String fixture,
      DumpDigest digest,
      Supplier<List<String>> fullSortedDump,
      String regenCommand) {
    noNewline("golden fixture name", fixture);
    noNewline("golden regenerate command", regenCommand);
    for (String type : digest.counts().keySet()) {
      noNewline("golden count key '" + type + "'", type);
    }
    String newGolden = renderGolden(fixture, digest, regenCommand);
    Path goldenPath = goldenDir().resolve(fixture + ".golden");
    if (Boolean.parseBoolean(System.getProperty(UPDATE_PROPERTY, "false"))) {
      writeText(goldenPath, newGolden);
      System.out.println("golden written: " + goldenPath);
      return;
    }
    if (!Files.isRegularFile(goldenPath)) {
      fail(
          "golden missing for fixture '"
              + fixture
              + "': "
              + goldenPath
              + " — regenerate with: "
              + regenCommand);
    }
    List<String> goldenLines = readGolden(goldenPath);
    Map<String, String> golden = parseKeyValues(goldenLines);
    String goldenSha = golden.get("fixture-sha256");
    String actualSha = digest.fixtureSha256();
    if (actualSha != null
        && goldenSha != null
        && !goldenSha.isEmpty()
        && !goldenSha.equals(actualSha)) {
      fail(
          "fixture bytes changed (generator/builder output), not a parser value change — "
              + "regenerate: "
              + regenCommand
              + "\n  fixture '"
              + fixture
              + "': golden fixture-sha256="
              + goldenSha
              + ", actual fixture-sha256="
              + actualSha
              + "\n  golden: "
              + goldenPath);
    }
    List<String> newLines = splitLines(newGolden);
    if (goldenLines.equals(newLines)) {
      return;
    }
    // Mismatch: only now is the full dump materialized (Risk 4 — a green run never builds it).
    List<String> dump = fullSortedDump.get();
    Path actualDir = actualDir();
    Path dumpPath = actualDir.resolve(fixture + ".dump");
    Path newGoldenPath = actualDir.resolve(fixture + ".golden");
    try {
      Files.createDirectories(actualDir);
      writeText(dumpPath, joinLines(dump));
      writeText(newGoldenPath, newGolden);
    } catch (IOException e) {
      throw new UncheckedIOException(
          "cannot write the golden mismatch artifacts to " + actualDir, e);
    }
    fail(
        mismatchMessage(
            fixture,
            goldenLines,
            newLines,
            golden,
            digest,
            goldenPath,
            dumpPath,
            newGoldenPath,
            regenCommand));
  }

  /**
   * The mismatch failure: names the fixture, the first differing golden line (with both texts, so
   * the diff is readable from the log alone), both digests and every per-type count that differs
   * (R9 — never "all lines differ", which is what a stale or empty dump claims), both artifact
   * paths, and the regeneration command.
   */
  private static String mismatchMessage(
      String fixture,
      List<String> goldenLines,
      List<String> newLines,
      Map<String, String> golden,
      DumpDigest digest,
      Path goldenPath,
      Path dumpPath,
      Path newGoldenPath,
      String regenCommand) {
    StringBuilder message = new StringBuilder();
    message.append("golden mismatch for fixture '").append(fixture).append("':");
    int index = firstDiffIndex(goldenLines, newLines);
    message.append("\n  first differing golden line ").append(index + 1);
    if (goldenLines.isEmpty()) {
      message.append(": the golden file is empty (stale or interrupted regeneration?)");
    } else {
      message.append(": golden '");
      message.append(index < goldenLines.size() ? goldenLines.get(index) : "<end of golden>");
      message.append("' vs actual '");
      message.append(index < newLines.size() ? newLines.get(index) : "<end of new golden>");
      message.append("'");
    }
    message
        .append("\n  golden digest: ")
        .append(golden.containsKey("digest") ? golden.get("digest") : "<no digest in golden>");
    message.append("\n  actual digest: ").append(digest.digest());
    message.append("\n  differing counts:");
    Map<String, String> goldenCounts = countsOf(golden);
    TreeSet<String> types = new TreeSet<String>(goldenCounts.keySet());
    types.addAll(digest.counts().keySet());
    if (types.isEmpty()) {
      message.append(" <none>");
    }
    for (String type : types) {
      message.append(countDifference(type, goldenCounts.get(type), digest.counts().get(type)));
    }
    message.append("\n  golden file: ").append(goldenPath);
    message.append("\n  actual dump: ").append(dumpPath);
    message.append(" (new golden beside it: ").append(newGoldenPath).append(")");
    message.append("\n  regenerate with: ").append(regenCommand);
    return message.toString();
  }

  /** One "count.<type>: golden=<g> actual=<a>" line, omitted when the counts agree. */
  private static String countDifference(String type, String goldenValue, Long actualValue) {
    Long golden = null;
    if (goldenValue != null) {
      try {
        golden = Long.parseLong(goldenValue.trim());
      } catch (NumberFormatException ignored) {
        // A non-numeric golden count falls through as a difference, shown raw (R9).
      }
    }
    if (golden != null && actualValue != null && golden.equals(actualValue)) {
      return "";
    }
    return "\n    count."
        + type
        + ": golden="
        + (goldenValue == null ? "missing" : goldenValue)
        + " actual="
        + (actualValue == null ? "missing" : String.valueOf(actualValue));
  }

  /**
   * Renders the golden text for {@code fixture}; the format above is the only golden format (R7).
   */
  static String renderGolden(String fixture, DumpDigest digest, String regenCommand) {
    StringBuilder golden = new StringBuilder();
    golden.append(HEADER_PREFIX).append(regenCommand).append('\n');
    golden.append("fixture=").append(fixture).append('\n');
    golden
        .append("fixture-sha256=")
        .append(digest.fixtureSha256() == null ? "" : digest.fixtureSha256())
        .append('\n');
    golden.append("lines=").append(digest.lines()).append('\n');
    golden.append("digest=").append(digest.digest()).append('\n');
    for (Map.Entry<String, Long> count : digest.counts().entrySet()) {
      golden
          .append("count.")
          .append(count.getKey())
          .append('=')
          .append(count.getValue())
          .append('\n');
    }
    return golden.toString();
  }

  private static Path goldenDir() {
    String dir = System.getProperty(GOLDEN_DIR_PROPERTY);
    if (dir == null || dir.isEmpty()) {
      throw new AssertionError(
          "system property "
              + GOLDEN_DIR_PROPERTY
              + " is not set: GoldenFile cannot locate the committed goldens. It is set by the "
              + "Gradle test wiring, so run the golden tests through Gradle, not a bare JVM (R3).");
    }
    return Paths.get(dir);
  }

  private static Path actualDir() {
    String dir = System.getProperty(ACTUAL_DIR_PROPERTY);
    if (dir == null || dir.isEmpty()) {
      throw new AssertionError(
          "system property "
              + ACTUAL_DIR_PROPERTY
              + " is not set: GoldenFile cannot write the mismatch artifacts (the full dump and "
              + "the new golden) for the review diff (R3).");
    }
    return Paths.get(dir);
  }

  private static List<String> readGolden(Path goldenPath) {
    List<String> lines;
    try {
      lines = Files.readAllLines(goldenPath, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new AssertionError("golden unreadable: " + goldenPath, e);
    }
    return lines;
  }

  private static void writeText(Path file, String text) {
    try {
      Files.createDirectories(file.getParent());
      Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write " + file, e);
    }
  }

  /**
   * Parses the golden's {@code key=value} lines, skipping the {@code #} header. The count lines are
   * returned with their {@code count.} prefix intact.
   */
  private static Map<String, String> parseKeyValues(List<String> lines) {
    Map<String, String> values = new LinkedHashMap<String, String>();
    for (String line : lines) {
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      int split = line.indexOf('=');
      if (split <= 0) {
        continue;
      }
      values.put(line.substring(0, split), line.substring(split + 1));
    }
    return values;
  }

  private static Map<String, String> countsOf(Map<String, String> golden) {
    Map<String, String> counts = new LinkedHashMap<String, String>();
    for (Map.Entry<String, String> entry : golden.entrySet()) {
      if (entry.getKey().startsWith("count.")) {
        counts.put(entry.getKey().substring("count.".length()), entry.getValue());
      }
    }
    return counts;
  }

  private static int firstDiffIndex(List<String> goldenLines, List<String> newLines) {
    int limit = Math.min(goldenLines.size(), newLines.size());
    for (int i = 0; i < limit; i++) {
      if (!goldenLines.get(i).equals(newLines.get(i))) {
        return i;
      }
    }
    return limit;
  }

  private static List<String> splitLines(String text) {
    List<String> lines = new ArrayList<String>();
    int start = 0;
    while (start < text.length()) {
      int end = text.indexOf('\n', start);
      if (end < 0) {
        lines.add(text.substring(start));
        break;
      }
      lines.add(text.substring(start, end));
      start = end + 1;
    }
    return lines;
  }

  private static String joinLines(List<String> lines) {
    StringBuilder joined = new StringBuilder();
    for (String line : lines) {
      joined.append(line).append('\n');
    }
    return joined.toString();
  }

  /**
   * A newline in a field would silently corrupt the one-line-per-record golden format (the
   * embedded-newline pitfall: a naive line count then reports phantom records). Fail loudly
   * instead.
   */
  private static void noNewline(String what, String value) {
    if (value != null && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)) {
      throw new AssertionError(
          what + " must not contain a newline — it would corrupt the golden format: " + value);
    }
  }
}

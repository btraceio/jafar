package io.jafar.shell.unified;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.shell.core.OpenArguments;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * This shell's {@code open} takes a path with spaces, and reads a quoted one as jfr-shell does, so
 * a script either shell writes (an {@code ask} transcript quotes such a path) opens here (#233).
 */
class OpenPathWithSpacesTest {
  private static final Path RECORDING =
      Path.of("..", "parser-core", "src", "test", "resources", "test-ap.jfr")
          .toAbsolutePath()
          .normalize();

  /** Runs the lines in a fresh shell and returns everything it printed. */
  private static String run(String... lines) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream print = new PrintStream(out, true);
    try (Shell shell = Shell.forScript(print, print)) {
      for (String line : lines) {
        shell.execute(line);
      }
    }
    return out.toString();
  }

  private static String count(String output) {
    Matcher m = Pattern.compile("count\\D*?(\\d+)").matcher(output);
    assertTrue(m.find(), "a count in: " + output);
    return m.group(1);
  }

  @Test
  void aPathWithSpacesOpensQuotedOrNot(@TempDir Path temp) throws Exception {
    String query = "show events/jdk.ExecutionSample | count()";
    Path plain = Files.copy(RECORDING, temp.resolve("plain.jfr"));
    Path spaced =
        Files.copy(
            RECORDING, Files.createDirectories(temp.resolve("dir with spaces")).resolve("a b.jfr"));
    String expected = count(run("open " + plain, query));
    assertTrue(Long.parseLong(expected) > 0, "a nonzero count from " + plain);

    assertEquals(expected, count(run("open " + spaced, query)), "unquoted");
    String quoted = "open " + OpenArguments.quote(spaced.toString());
    assertTrue(quoted.contains("\""), quoted);
    assertEquals(expected, count(run(quoted, query)), quoted);
  }

  @Test
  void anAliasIsRefusedRatherThanReadAsPartOfThePath(@TempDir Path temp) throws Exception {
    Path plain = Files.copy(RECORDING, temp.resolve("plain.jfr"));
    String output = run("open " + plain + " --alias x");
    assertTrue(output.contains("--alias is not supported in this shell"), output);
  }
}

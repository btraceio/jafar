package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.shell.core.OpenArguments;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * {@code show <path> <query>} rebuilt an {@code open <path>} line for the dispatcher, which split
 * it at the first space and opened a file that does not exist (#233).
 */
class PathWithSpacesTest {
  private static final Path RECORDING =
      Paths.get("..", "parser-core", "src", "test", "resources", "test-ap.jfr")
          .toAbsolutePath()
          .normalize();
  private static final String QUERY = "events/jdk.ExecutionSample | count()";

  @TempDir Path temp;

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();
  private PrintStream originalOut;
  private PrintStream originalErr;
  private InputStream originalIn;

  @BeforeEach
  void captureOutput() {
    originalOut = System.out;
    originalErr = System.err;
    originalIn = System.in;
    System.setOut(new PrintStream(out, true));
    System.setErr(new PrintStream(err, true));
  }

  @AfterEach
  void restoreOutput() {
    System.setOut(originalOut);
    System.setErr(originalErr);
    System.setIn(originalIn);
  }

  /** Runs {@code show <recording> <query> --format json} and returns its stdout. */
  private String show(Path recording) {
    out.reset();
    err.reset();
    int exit =
        new CommandLine(new Main())
            .execute("show", recording.toString(), QUERY, "--format", "json");
    assertEquals(0, exit, "exit code for " + recording + "; stderr: " + err);
    return out.toString();
  }

  @Test
  void aPathWithSpacesOpensTheSameRecording() throws Exception {
    Path plain = Files.copy(RECORDING, temp.resolve("sample.jfr"));
    Path spacedDir = Files.createDirectories(temp.resolve("dir with spaces"));
    Path spaced = Files.copy(RECORDING, spacedDir.resolve("sample input.jfr"));

    String expected = show(plain);
    assertTrue(
        expected.matches("(?s).*\"count\"\\s*:\\s*[1-9][0-9]*.*"),
        "a nonzero count from the plain path: " + expected);

    // One-shot show prints the "Opened session" line only when verbose (#266); it names the path
    // that was opened, which is what this test checks
    String actual;
    String verbose = System.getProperty("jfr.shell.verbose");
    System.setProperty("jfr.shell.verbose", "true");
    try {
      actual = show(spaced);
    } finally {
      if (verbose == null) {
        System.clearProperty("jfr.shell.verbose");
      } else {
        System.setProperty("jfr.shell.verbose", verbose);
      }
    }
    // the spaced run prints its path in its "Opened session" line; compare the result only
    assertEquals(
        expected.substring(expected.indexOf('[')),
        actual.substring(actual.indexOf('[')),
        "same result for " + spaced);
    assertTrue(actual.contains(spaced.toString()), "opened the spaced path: " + actual);
  }

  /** Runs {@code script -} with the given lines on stdin and returns its stdout. */
  private String script(String... lines) {
    int exit = runScript(lines);
    assertEquals(0, exit, "script exit code; stderr: " + err + "; stdout: " + out);
    return out.toString();
  }

  /**
   * Runs {@code script -} with the given lines on stdin; output lands in {@code out}/{@code err}.
   */
  private int runScript(String... lines) {
    out.reset();
    err.reset();
    System.setIn(
        new ByteArrayInputStream(
            (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8)));
    return new CommandLine(new Main()).execute("script", "-");
  }

  /**
   * Before #233 open read only its first word, so trailing text was ignored ("open rec.jfr #
   * main"). That still opens the file when the whole text names none, with a note, not silently.
   */
  @Test
  void trailingWordsAfterAnExistingFileStillOpenItWithANote() throws Exception {
    Path plain = Files.copy(RECORDING, temp.resolve("plain.jfr"));
    String expected = count(show(plain));
    String output = script("open " + plain + " # main recording", QUERY);
    assertEquals(expected, count(output), output);
    assertTrue(output.contains("Note: no file '" + plain + " # main recording'"), output);
    assertTrue(output.contains("ignoring '# main recording'"), output);
  }

  /** A usage problem is reported as one, not as "Error: Usage: ...". */
  @Test
  void aMalformedOpenLineIsAUsageMessage() {
    assertEquals(1, runScript("open \"\""));
    assertTrue(err.toString().contains("Usage: open <path> [--alias NAME]"), err.toString());
    assertTrue(!err.toString().contains("Error: Usage"), err.toString());
  }

  /**
   * The {@code open} command read only the first word of its line, so a script, a transcript of
   * {@code ask} replayed as one, or a typed command could not open a path with a space.
   */
  @Test
  void theOpenCommandTakesAPathWithSpaces() throws Exception {
    Path spacedDir = Files.createDirectories(temp.resolve("dir with spaces"));
    Path spaced = Files.copy(RECORDING, spacedDir.resolve("sample input.jfr"));
    String expected = count(show(Files.copy(RECORDING, temp.resolve("plain.jfr"))));

    // quoted, as the ask transcript writes it (OpenArguments.quote)
    String quoted = "open " + OpenArguments.quote(spaced.toString());
    assertTrue(quoted.contains("\""), quoted);
    assertEquals(expected, count(script(quoted, QUERY)), quoted);
    // unquoted
    assertEquals(expected, count(script("open " + spaced, QUERY)), "unquoted");
    // with an alias after it
    String withAlias = script("open " + spaced + " --alias spaced", "sessions");
    assertTrue(withAlias.contains("spaced - " + spaced), withAlias);
  }

  /** The count from JSON or table output: the first number after the word {@code count}. */
  private static String count(String output) {
    var m = java.util.regex.Pattern.compile("count\\D*?(\\d+)").matcher(output);
    assertTrue(m.find(), "a count in: " + output);
    return m.group(1);
  }
}

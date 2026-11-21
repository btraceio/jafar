package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
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

  @BeforeEach
  void captureOutput() {
    originalOut = System.out;
    originalErr = System.err;
    System.setOut(new PrintStream(out, true));
    System.setErr(new PrintStream(err, true));
  }

  @AfterEach
  void restoreOutput() {
    System.setOut(originalOut);
    System.setErr(originalErr);
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

    String actual = show(spaced);
    // the plain run prints the plain path in its "Opened session" line; compare the result only
    assertEquals(
        expected.substring(expected.indexOf('[')),
        actual.substring(actual.indexOf('[')),
        "same result for " + spaced);
    assertTrue(actual.contains(spaced.toString()), "opened the spaced path: " + actual);
  }
}

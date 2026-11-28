package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.jafar.shell.backend.BackendRegistry;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * #268: {@code jfr-shell -b jdk script q.jfrs} ran on the default backend without a word: the
 * root's {@code -b} was set on the root command, and the subcommand read only its own.
 */
class BackendOptionPlacementTest {

  private static final Path RECORDING =
      Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr")
          .toAbsolutePath()
          .normalize();

  @TempDir Path dir;

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();
  private PrintStream originalOut;
  private PrintStream originalErr;
  private String originalBackend;

  @BeforeEach
  void setUp() {
    // JFRSHELL_BACKEND overrides -b; the cases below are about -b alone
    assumeTrue(System.getenv("JFRSHELL_BACKEND") == null, "JFRSHELL_BACKEND is set");
    originalOut = System.out;
    originalErr = System.err;
    originalBackend = System.getProperty("jfr.shell.backend");
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    // The registry picks its backend once per JVM: start every run from a fresh choice
    System.clearProperty("jfr.shell.backend");
    BackendRegistry.getInstance().rediscover();
  }

  @AfterEach
  void tearDown() {
    System.setOut(originalOut);
    System.setErr(originalErr);
    if (originalBackend == null) {
      System.clearProperty("jfr.shell.backend");
    } else {
      System.setProperty("jfr.shell.backend", originalBackend);
    }
    BackendRegistry.getInstance().rediscover();
  }

  @Test
  void aBackendBeforeScriptIsTheOneTheScriptRunsWith() throws Exception {
    // jdk refuses chunk queries (an error, so the script exits 1), jafar answers them: the output
    // says which one ran
    int exit = run("-b", "jdk", "script", script("chunks").toString());

    assertEquals(1, exit, "stdout: " + out + "; stderr: " + err);
    assertEquals("jdk", BackendRegistry.getInstance().getCurrent().getId());
    assertTrue(
        err.toString(StandardCharsets.UTF_8).contains("Backend 'jdk' does not support chunk"),
        err.toString());
  }

  @Test
  void aBackendBeforeShowIsTheOneShowRunsWith() {
    int exit =
        run(
            "--backend",
            "jdk",
            "show",
            RECORDING.toString(),
            "events/jdk.ExecutionSample | count()");

    assertEquals(0, exit, "stdout: " + out + "; stderr: " + err);
    assertEquals("jdk", BackendRegistry.getInstance().getCurrent().getId());
  }

  @Test
  void theSameBackendOnBothSidesIsFine() throws Exception {
    int exit =
        run(
            "-b",
            "jdk",
            "script",
            "-b",
            "JDK",
            script("show events/jdk.ExecutionSample | count()").toString());

    assertEquals(0, exit, "stderr: " + err);
    assertEquals("jdk", BackendRegistry.getInstance().getCurrent().getId());
  }

  @Test
  void twoDifferentBackendsAreAUsageError() throws Exception {
    int exit = run("-b", "jdk", "script", "-b", "jafar", script("chunks").toString());

    assertEquals(2, exit, "stdout: " + out);
    assertTrue(
        err.toString(StandardCharsets.UTF_8)
            .startsWith("Error: --backend is 'jdk' before 'script' and 'jafar' after it"),
        err.toString());
  }

  @Test
  void aBackendBeforeACommandThatUsesNoneIsAUsageError() {
    for (String command : new String[] {"ps", "tail"}) {
      err.reset();
      int exit = command.equals("tail") ? run("-b", "jdk", "tail", "1") : run("-b", "jdk", "ps");

      assertEquals(2, exit, command + ": " + err);
      assertTrue(
          err.toString(StandardCharsets.UTF_8)
              .contains("--backend does not apply to '" + command + "'"),
          err.toString());
    }
  }

  @Test
  void aBackendBeforeCompareIsTheOneCompareRunsWith() {
    int exit = run("-b", "jdk", "compare", RECORDING.toString(), RECORDING.toString());

    assertEquals(0, exit, "stdout: " + out + "; stderr: " + err);
    assertEquals("jdk", BackendRegistry.getInstance().getCurrent().getId());
  }

  @Test
  void twoDifferentBackendsAroundCompareAreAUsageError() {
    int exit =
        run("-b", "jdk", "compare", "-b", "jafar", RECORDING.toString(), RECORDING.toString());

    assertEquals(2, exit, "stdout: " + out);
    assertTrue(
        err.toString(StandardCharsets.UTF_8)
            .startsWith("Error: --backend is 'jdk' before 'compare' and 'jafar' after it"),
        err.toString());
  }

  @Test
  void theCommandIsNamedAsItWasTyped() {
    // 'cp' is an alias of 'constants'
    int exit = run("-b", "jdk", "cp", "-b", "jafar", RECORDING.toString());

    assertEquals(2, exit, "stdout: " + out);
    assertTrue(
        err.toString(StandardCharsets.UTF_8).contains("before 'cp' and 'jafar' after it"),
        err.toString());
  }

  @Test
  void aBackendTheEnvironmentOverridesIsNamedAsSuch() {
    int exit = Main.validateBackend("jdk", "jafar");

    assertEquals(2, exit);
    assertTrue(
        err.toString(StandardCharsets.UTF_8)
            .contains("--backend is 'jdk' but JFRSHELL_BACKEND is 'jafar', which takes precedence"),
        err.toString());
    assertEquals(0, (int) Main.validateBackend("jdk", "JDK"), err.toString());
    // A variable naming no installed backend is passed over by the registry, so -b stands
    assertEquals(0, (int) Main.validateBackend("jdk", "nosuch"), err.toString());
  }

  private Path script(String command) throws Exception {
    return Files.writeString(dir.resolve("q.jfrs"), "open " + RECORDING + "\n" + command + "\n");
  }

  private static int run(String... args) {
    return new CommandLine(new Main()).execute(args);
  }
}

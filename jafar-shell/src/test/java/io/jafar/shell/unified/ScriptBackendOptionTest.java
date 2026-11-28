package io.jafar.shell.unified;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * #268 in the unified shell: {@code jafar-shell script -b <backend>} set the backend without
 * checking it, so an unknown one fell back to the default in silence, and {@code -b} before {@code
 * script} sent the script to jfr-shell's JFR-only runner instead.
 */
class ScriptBackendOptionTest {

  private static final Path RECORDING =
      Path.of("../jfr-shell-tck/src/main/resources/tck-test.jfr").toAbsolutePath().normalize();

  @TempDir Path dir;

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();
  private PrintStream originalOut;
  private PrintStream originalErr;
  private String originalBackend;

  @BeforeEach
  void setUp() {
    assumeTrue(System.getenv("JFRSHELL_BACKEND") == null, "JFRSHELL_BACKEND is set");
    originalOut = System.out;
    originalErr = System.err;
    originalBackend = System.getProperty("jfr.shell.backend");
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
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
  void anUnknownBackendIsAnError() throws Exception {
    int exit = new CommandLine(new Main.ScriptCommand()).execute("-b", "nosuch", script());

    assertNotEquals(0, exit, "stdout: " + out);
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("nosuch"), err.toString());
  }

  @Test
  void aBackendBeforeScriptReachesTheUnifiedScriptCommand() throws Exception {
    String[] rest = Main.scriptArguments(new String[] {"-b", "jdk", "script", script()});

    int exit = new CommandLine(new Main.ScriptCommand()).execute(rest);

    assertEquals(0, exit, "stdout: " + out + "; stderr: " + err);
    assertEquals("jdk", BackendRegistry.getInstance().getCurrent().getId());
  }

  @Test
  void theSameBackendBeforeAndAfterScriptIsFine() throws Exception {
    String[] rest =
        Main.scriptArguments(new String[] {"-b", "jdk", "script", "-b", "JDK", script()});

    int exit = new CommandLine(new Main.ScriptCommand()).execute(rest);

    assertEquals(0, exit, "stdout: " + out + "; stderr: " + err);
    assertEquals("jdk", BackendRegistry.getInstance().getCurrent().getId());
  }

  @Test
  void differentBackendsBeforeAndAfterScriptAreAUsageError() throws Exception {
    String[] rest =
        Main.scriptArguments(new String[] {"-b", "jdk", "script", "-b", "jafar", script()});

    int exit = new CommandLine(new Main.ScriptCommand()).execute(rest);

    assertEquals(2, exit, "stdout: " + out);
    assertTrue(
        err.toString(StandardCharsets.UTF_8)
            .startsWith("Error: --backend is 'jdk' before 'script' and 'jafar' after it"),
        err.toString());
  }

  private String script() throws Exception {
    return Files.writeString(
            dir.resolve("q.jfrs"),
            "open " + RECORDING + "\nshow events/jdk.ExecutionSample | count()\n")
        .toString();
  }
}

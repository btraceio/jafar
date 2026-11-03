package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the fat jar the way users and the jlinkDist launchers do: {@code java -jar}.
 *
 * <p>jline-terminal-jna loads JNA, and {@code System.load} is a restricted method (JEP 472).
 * Without {@code Enable-Native-Access: ALL-UNNAMED} in the jar's manifest, JDK 24+ printed four
 * lines on every start: "WARNING: A restricted method in java.lang.System has been called", "...
 * System::load has been called by com.sun.jna.Native ...", and two more.
 */
class JarLaunchTest {

  @Test
  void startsWithoutNativeAccessWarnings(@TempDir Path home) throws Exception {
    // The warning exists only from JDK 24; on an older JVM this test could not fail
    assertTrue(
        Runtime.version().feature() >= 24, "needs JDK 24+, was " + Runtime.version().feature());
    Path recording =
        Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr")
            .toAbsolutePath()
            .normalize();
    Path out = home.resolve("out.txt");
    List<String> command =
        List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Duser.home=" + home, // no installed plugins, no history
            "-Djfr.shell.backend.jars=" + System.getProperty("jfr.shell.jafar.backend.jar"),
            "-jar",
            System.getProperty("jfr.shell.jar"));
    Process process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(out.toFile()).start();
    process
        .getOutputStream()
        .write(("open " + recording + "\nbackend\nexit\n").getBytes(StandardCharsets.UTF_8));
    process.getOutputStream().close();
    assertTrue(process.waitFor(120, TimeUnit.SECONDS), "shell did not exit");
    String output = Files.readString(out);
    assertEquals(0, process.exitValue(), output);

    // It really started, with the backend, and opened the recording
    assertTrue(output.contains("Opened session #1"), output);
    assertTrue(output.contains("Current backend: Jafar Parser (jafar)"), output);
    assertFalse(output.contains("restricted method"), output);
    assertFalse(output.contains("com.sun.jna.Native"), output);
    assertFalse(output.contains("--enable-native-access"), output);
  }
}

package io.jafar.shell.unified;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the fat jar against a real heap dump, the way {@code doc/hdump-shell-quickstart.md} does.
 */
class ShellArtifactTest {

  @Test
  void opensHeapDumpAndAnswersBareQuery(@TempDir Path dir) throws Exception {
    Path dump = dir.resolve("dump.hprof");
    ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
        .dumpHeap(dump.toString(), true);

    String output = runJar(dir, "objects | count\nexit\n", dump.toString());

    assertTrue(output.contains("Session 1 opened: dump.hprof"), output);
    assertFalse(output.contains("Invalid JFR Magic Number"), output);
    assertFalse(output.contains("Unknown command"), output);
    assertTrue(output.contains("(1 rows)"), output);
  }

  @Test
  void keepsJfrCommandLine(@TempDir Path dir) throws Exception {
    Path recording = Path.of("../jfr-shell-tck/src/main/resources/tck-test.jfr").toAbsolutePath();

    String output = runJar(dir, "", "chunks", recording.toString(), "--summary");

    assertTrue(output.toLowerCase().contains("chunks"), output);
  }

  @Test
  void hasOneLoggingProvider(@TempDir Path dir) throws Exception {
    assertFalse(runJar(dir, "", "--version").contains("multiple SLF4J providers"));
  }

  @Test
  void recognisesQueryRoots() {
    List<String> roots = List.of("objects", "events");

    assertTrue(Shell.startsWithRoot("objects | count", roots));
    assertTrue(Shell.startsWithRoot("objects/java.util.ArrayList", roots));
    assertTrue(Shell.startsWithRoot("objects[shallow > 1]", roots));
    assertTrue(Shell.startsWithRoot("events", roots));
    assertFalse(Shell.startsWithRoot("objectsx", roots));
    assertFalse(Shell.startsWithRoot("bogus | count", roots));
  }

  @Test
  void routesOnlyJfrInvocationsToJfrCommandLine() {
    assertTrue(Main.isJfrCommandLine(new String[] {"script", "-"}));
    assertTrue(Main.isJfrCommandLine(new String[] {"-q", "show", "x.jfr", "events"}));
    assertTrue(Main.isJfrCommandLine(new String[] {"--backend=jdk", "x.jfr"}));
    assertTrue(Main.isJfrCommandLine(new String[] {"--install-plugin", "p.jar"}));
    assertFalse(Main.isJfrCommandLine(new String[] {"dump.hprof"}));
    assertFalse(Main.isJfrCommandLine(new String[] {"-q", "--tui"}));
    assertFalse(Main.isJfrCommandLine(new String[] {}));
  }

  private static String runJar(Path dir, String stdin, String... args) throws Exception {
    Path out = dir.resolve("out.txt");
    List<String> command =
        new java.util.ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + dir,
                "-jar",
                System.getProperty("jafar.shell.jar")));
    command.addAll(List.of(args));
    Process process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(out.toFile()).start();
    process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
    process.getOutputStream().close();
    assertTrue(process.waitFor(120, TimeUnit.SECONDS), "shell did not exit");
    String output = Files.readString(out);
    assertEquals(0, process.exitValue(), output);
    return output;
  }
}

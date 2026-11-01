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
  void setReachesLlmStatus(@TempDir Path dir) throws Exception {
    String output =
        runJar(dir, "set llm.backend = ollama\nset llm.max-rows = 20\nllm status\nexit\n");

    assertFalse(output.contains("Unknown command"), output);
    assertTrue(output.contains("backend     : ollama"), output);
    assertTrue(output.contains("max rows    : 20"), output);
  }

  /**
   * Scripting across formats in the jar users get: a script with positional parameters opens a JFR
   * recording and a heap dump, holds a query-valued variable per format, a map, a merge, and
   * branches on both. The JFR variable is global and is read while the heap session is current.
   */
  @Test
  void scriptsAcrossJfrAndHeapDump(@TempDir Path dir) throws Exception {
    Path recording = Path.of("../jfr-shell-tck/src/main/resources/tck-test.jfr").toAbsolutePath();
    Path dump = dir.resolve("dump.hprof");
    ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
        .dumpHeap(dump.toString(), true);
    Path script = dir.resolve("cross.jfrs");
    Files.writeString(
        script,
        String.join(
            "\n",
            "# $1 recording, $2 heap dump, $3 minimum shallow size",
            "set --global cfg = {\"min\": ${3:-1000}, \"label\": \"cross\"}",
            "set --global opts = merge(${cfg}, {\"label\": \"merged\"})",
            "open $1",
            "set --global starts = events/jdk.ThreadStart | count()",
            "open $2",
            "set big = objects[shallow > ${cfg.min}] | count",
            "echo LABEL ${opts.label} MIN ${opts.min}",
            "echo STARTS ${starts} BIG ${big}",
            "if ${starts} > 0 && ${big} > 0",
            "echo VERDICT both",
            "elif ${starts} > 0",
            "echo VERDICT jfr-only",
            "else",
            "echo VERDICT neither",
            "endif",
            "vars --info big",
            ""));

    String output =
        runJar(dir, "script " + script + " " + recording + " " + dump + " 64\nshow ${big}\nexit\n");

    assertFalse(output.contains("Error"), output);
    assertTrue(output.contains("LABEL merged MIN 64"), output); // an integer, not 64.0
    assertTrue(output.contains("STARTS 17 BIG "), output);
    assertTrue(output.contains("VERDICT both"), output);
    assertFalse(output.contains("VERDICT jfr-only"), output);
    assertTrue(output.contains("Source: objects[shallow > 64] | count"), output);
    assertTrue(output.contains("Session: #2 (hdump)"), output);
    assertTrue(output.contains("Script executed successfully: 16 commands"), output);
  }

  @Test
  void queryVariablesAreLazyAndInvalidatable(@TempDir Path dir) throws Exception {
    Path recording = Path.of("../jfr-shell-tck/src/main/resources/tck-test.jfr").toAbsolutePath();

    String output =
        runJar(
            dir,
            String.join(
                "\n",
                "open " + recording,
                "set starts = events/jdk.ThreadStart",
                "vars",
                "echo N=${starts.size}",
                "vars",
                "invalidate starts",
                "vars",
                "set n = bogus | count()",
                "exit",
                ""));

    assertTrue(output.contains("starts = lazy[events/jdk.ThreadStart] (not evaluated)"), output);
    assertTrue(output.contains("N=17"), output);
    assertTrue(output.contains("starts = lazy[events/jdk.ThreadStart] (cached: 17 rows)"), output);
    assertTrue(output.contains("Invalidated cache for starts"), output);
    assertTrue(output.contains("Error: Invalid query:"), output);
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

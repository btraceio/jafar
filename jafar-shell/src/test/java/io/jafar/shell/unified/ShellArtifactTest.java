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

  /**
   * A script that runs itself used to end the shell with a StackOverflowError; a script that ended
   * inside a false if-block left the shell skipping every command typed after it.
   */
  @Test
  void runawayAndUnbalancedScriptsLeaveTheShellUsable(@TempDir Path dir) throws Exception {
    Path self = dir.resolve("self.jfrs");
    Files.writeString(self, "script " + self + "\n");
    Path open = dir.resolve("open.jfrs");
    Files.writeString(open, "if 1 == 2\necho never\n");

    String output =
        runJar(
            dir, "script " + open + "\necho AFTER-OPEN\nscript " + self + "\necho ALIVE\nexit\n");

    assertTrue(output.contains("open.jfrs left 1 if-block(s) open; closed them"), output);
    assertTrue(output.contains("AFTER-OPEN"), output);
    assertFalse(output.contains("never"), output);
    assertTrue(output.contains("Scripts nested more than 16 deep"), output);
    assertFalse(output.contains("StackOverflowError"), output);
    assertTrue(output.contains("ALIVE"), output);
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

  /**
   * The JFR commands at the jafar-shell prompt. {@code metadata --summary} used to be parsed as a
   * JfrPath query ({@code metadata} is a root) and fail with "Trailing characters at position 9".
   */
  @Test
  void runsJfrCommandsOnJfrSession(@TempDir Path dir) throws Exception {
    Path recording = Path.of("../jfr-shell-tck/src/main/resources/tck-test.jfr").toAbsolutePath();

    String output =
        runJar(
            dir,
            String.join(
                "\n",
                "open " + recording,
                "metadata --summary",
                "metadata class jdk.ThreadStart --fields",
                "chunks --summary",
                "chunk 0 show",
                "cp --summary",
                "constants --summary",
                "backend",
                "exit",
                ""));

    assertFalse(output.contains("Error"), output);
    assertFalse(output.contains("Unknown command"), output);
    assertTrue(output.contains("totalChunks"), output);
    assertTrue(output.contains("Current backend:"), output);
    assertTrue(output.contains("eventThread"), output); // a field of jdk.ThreadStart
  }

  @Test
  void jfrCommandsSayTheyNeedJfrSession(@TempDir Path dir) throws Exception {
    Path dump = dir.resolve("dump.hprof");
    ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
        .dumpHeap(dump.toString(), true);

    String output = runJar(dir, "chunks\nopen " + dump + "\nmetadata --summary\nexit\n");

    assertTrue(
        output.contains(
            "Error: 'chunks' needs a JFR session; open a .jfr recording or switch to one with"),
        output);
    assertTrue(
        output.contains("Error: 'metadata' needs a JFR session; session #1 is hdump."), output);
    assertFalse(output.contains("Unknown command"), output);
  }

  /** The example shipped with jfr-shell, run by jafar-shell's own {@code script}. */
  @Test
  void runsBasicAnalysisExample(@TempDir Path dir) throws Exception {
    Path recording = Path.of("../jfr-shell-tck/src/main/resources/tck-test.jfr").toAbsolutePath();
    Path script =
        Path.of("../jfr-shell/src/main/resources/examples/basic-analysis.jfrs").toAbsolutePath();

    String output = runJar(dir, "script " + script + " " + recording + "\nexit\n");

    assertFalse(output.contains("Error"), output);
    assertFalse(output.contains("error"), output);
    assertTrue(output.contains("=== Metadata Summary ==="), output);
    assertTrue(output.contains("Script executed successfully"), output);
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
  void routesJfrCommandsButNotQueries() {
    assertTrue(Shell.isJfrCommand("metadata"));
    assertTrue(Shell.isJfrCommand("metadata --summary"));
    assertTrue(Shell.isJfrCommand("metadata class jdk.ThreadStart"));
    assertTrue(Shell.isJfrCommand("chunks --range 0-1"));
    assertTrue(Shell.isJfrCommand("chunk 0 show"));
    assertTrue(Shell.isJfrCommand("cp jdk.types.Symbol"));
    assertTrue(Shell.isJfrCommand("constants --summary"));
    assertTrue(Shell.isJfrCommand("backend list"));
    assertFalse(Shell.isJfrCommand("metadata/jdk.ExecutionSample"));
    assertFalse(Shell.isJfrCommand("constants/jdk.types.Symbol | count()"));
    assertFalse(Shell.isJfrCommand("metadata | count()"));
    assertFalse(Shell.isJfrCommand("metadata |count()"));
    assertFalse(Shell.isJfrCommand("events/jdk.ThreadStart"));
    assertFalse(Shell.isJfrCommand("objects | count"));
    assertFalse(Shell.isJfrCommand("metadata|count()"));
    assertFalse(Shell.isJfrCommand("metadatax"));
    assertFalse(Shell.isJfrCommand(""));
  }

  @Test
  void matchesJfrCommandWithoutRegardToCaseOrSpacing() {
    assertEquals("metadata", Shell.jfrCommandOf("METADATA --summary"));
    assertEquals("chunk", Shell.jfrCommandOf("  chunk\t0 show"));
    assertEquals("cp", Shell.jfrCommandOf("cp"));
    assertEquals("backend", Shell.jfrCommandOf("Backend list "));
    assertEquals(null, Shell.jfrCommandOf("chunks \t| count()"));
  }

  /**
   * jfr-shell's completer, which completes JFR sessions here, reads global variable names from the
   * dispatcher's store; it must be the shell's own, not a private empty one.
   */
  @Test
  void jfrDispatcherSharesTheShellsGlobalStore(@TempDir Path dir) throws Exception {
    String home = System.getProperty("user.home");
    System.setProperty("user.home", dir.toString());
    try (Shell shell = new Shell()) {
      shell.execute("set --global threshold = 5");
      assertTrue(shell.jfrDispatcher().getGlobalStore().contains("threshold"));
    } finally {
      System.setProperty("user.home", home);
    }
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

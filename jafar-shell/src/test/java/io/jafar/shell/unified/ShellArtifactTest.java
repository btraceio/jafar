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

  /**
   * {@code jafar-shell script <file> <args>} on the command line runs in the unified shell: before,
   * it went to jfr-shell, which parsed the heap dump as a recording and answered {@code Invalid
   * query: Unknown root: objects}.
   */
  @Test
  void scriptCommandLineRunsInUnifiedShell(@TempDir Path dir) throws Exception {
    Path recording = Path.of("../jfr-shell-tck/src/main/resources/tck-test.jfr").toAbsolutePath();
    Path dump = dir.resolve("dump.hprof");
    ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
        .dumpHeap(dump.toString(), true);
    Path script = dir.resolve("heap.jfrs");
    Files.writeString(
        script,
        String.join(
            "\n",
            "open $1",
            "set --global n = objects | count",
            "open $2",
            "set starts = events/jdk.ThreadStart | count()",
            "echo OBJECTS ${n} STARTS ${starts}",
            ""));

    Result result =
        runJarWithStatus(
            dir, "", "script", script.toString(), dump.toString(), recording.toString());

    assertEquals(0, result.status(), result.output());
    assertFalse(result.output().contains("Unknown root"), result.output());
    assertFalse(result.output().contains("Error"), result.output());
    assertTrue(
        result.output().matches("(?s).*OBJECTS [1-9][0-9]* STARTS 17\\R.*"), result.output());
    assertTrue(
        result.output().contains("Script executed successfully: 5 commands"), result.output());
    // script mode leaves out the informational lines and the banner, as jfr-shell's does
    assertFalse(result.output().contains("Session 1 opened"), result.output());
    assertFalse(result.output().contains("Set n ="), result.output());
    assertFalse(result.output().contains("Jafar Shell"), result.output());
    // and never touches the interactive history
    assertFalse(Files.exists(dir.resolve(".jafar-shell")), "history directory created");
  }

  /** {@code script -} reads the script from stdin, the form shebang lines use. */
  @Test
  void scriptCommandLineReadsStdin(@TempDir Path dir) throws Exception {
    Path recording = Path.of("../jfr-shell-tck/src/main/resources/tck-test.jfr").toAbsolutePath();

    Result result =
        runJarWithStatus(
            dir,
            "open $1\nset s = events/jdk.ThreadStart | count()\necho STARTS ${s}\n",
            "script",
            "-",
            recording.toString());

    assertEquals(0, result.status(), result.output());
    assertTrue(result.output().contains("STARTS 17"), result.output());
  }

  /**
   * A line that reports an error fails the script: it stops there, is listed, is not counted as
   * successful, and the exit status is 1. Before, the error was printed and the script went on to
   * report "Script executed successfully".
   */
  @Test
  void scriptCommandLineStopsAtReportedError(@TempDir Path dir) throws Exception {
    Path recording = Path.of("../jfr-shell-tck/src/main/resources/tck-test.jfr").toAbsolutePath();
    Path script = dir.resolve("bad.jfrs");
    Files.writeString(
        script, "open $1\nset n = bogus | count()\necho AFTER\nnosuchcommand\necho LAST\n");

    Result failFast = runJarWithStatus(dir, "", "script", script.toString(), recording.toString());

    assertEquals(1, failFast.status(), failFast.output());
    assertTrue(failFast.output().contains("Script completed with errors:"), failFast.output());
    assertTrue(failFast.output().contains("Line 2: Invalid query:"), failFast.output());
    assertTrue(
        failFast.output().contains("Executed 1/2 commands successfully."), failFast.output());
    assertFalse(failFast.output().contains("AFTER"), failFast.output());
    assertFalse(failFast.output().contains("executed successfully"), failFast.output());

    Result carryOn =
        runJarWithStatus(
            dir, "", "script", script.toString(), recording.toString(), "--continue-on-error");

    assertEquals(1, carryOn.status(), carryOn.output());
    assertTrue(carryOn.output().contains("AFTER"), carryOn.output());
    assertTrue(carryOn.output().contains("LAST"), carryOn.output());
    assertTrue(carryOn.output().contains("Line 4: Unknown command"), carryOn.output());
    assertTrue(carryOn.output().contains("Executed 3/5 commands successfully."), carryOn.output());
  }

  /** The same contract at the prompt: a failing line inside {@code script} is reported. */
  @Test
  void interactiveScriptReportsErrors(@TempDir Path dir) throws Exception {
    Path script = dir.resolve("bad.jfrs");
    Files.writeString(script, "echo FIRST\nuse 42\necho AFTER\n");

    String output = runJar(dir, "script " + script + "\nexit\n");

    assertTrue(output.contains("FIRST"), output);
    assertTrue(output.contains("Line 2: No such session: 42"), output);
    assertFalse(output.contains("AFTER"), output);
    assertFalse(output.contains("Script executed successfully"), output);
  }

  /**
   * jfr-shell's own {@code script} command, run the way the {@code jfr-shell} jbang alias runs it:
   * its launcher names {@code io.jafar.shell.Main} as the main class of this same artifact, so the
   * shebang line {@code #!/usr/bin/env -S jbang jfr-shell@btraceio script -} never reaches the
   * unified {@link Main}. Its reported errors now fail the script too.
   */
  @Test
  void jfrShellScriptCommandCountsReportedErrors(@TempDir Path dir) throws Exception {
    Path recording = Path.of("../jfr-shell-tck/src/main/resources/tck-test.jfr").toAbsolutePath();

    Result ok =
        runClassWithStatus(
            dir,
            "io.jafar.shell.Main",
            "open $1\nevents/jdk.ThreadStart | count()\n",
            "script",
            "-",
            recording.toString());

    assertEquals(0, ok.status(), ok.output());
    assertTrue(ok.output().contains("17"), ok.output());
    assertTrue(ok.output().contains("Script executed successfully: 2 commands"), ok.output());

    Result bad =
        runClassWithStatus(
            dir,
            "io.jafar.shell.Main",
            "open $1\nevents/jdk.NoSuchEvent[bogus | count()\nevents/jdk.ThreadStart | count()\n",
            "script",
            "-",
            recording.toString());

    assertEquals(1, bad.status(), bad.output());
    assertTrue(bad.output().contains("Line 2: Expected operator after field path"), bad.output());
    assertTrue(bad.output().contains("Executed 1/2 commands successfully."), bad.output());
    assertFalse(bad.output().contains("executed successfully"), bad.output());
  }

  /**
   * The argv the kernel builds for an executable script whose first line is {@code #!/usr/bin/env
   * -S jbang jfr-shell@btraceio script -}, run as {@code ./analyze.jfrs app.jfr}: {@code script -
   * ./analyze.jfrs app.jfr}, with stdin left to the caller (empty here). Before, {@code -} read the
   * empty stdin and ran nothing, or with the script piped in, {@code open $1} opened the script
   * itself as a recording. Checked for jfr-shell's entry point, which the {@code jfr-shell} alias
   * launches, and for this jar's, which the {@code jafar-shell} alias launches.
   */
  @Test
  void shebangInvocationRunsTheScript(@TempDir Path dir) throws Exception {
    Path recording = Path.of("../jfr-shell-tck/src/main/resources/tck-test.jfr").toAbsolutePath();
    Path script = dir.resolve("analyze.jfrs");
    Files.writeString(
        script,
        "#!/usr/bin/env -S jbang jfr-shell@btraceio script -\n"
            + "open $1\nevents/jdk.ThreadStart | count()\n");

    Result jfrShell =
        runClassWithStatus(
            dir, "io.jafar.shell.Main", "", "script", "-", script.toString(), recording.toString());
    Result jafarShell =
        runJarWithStatus(dir, "", "script", "-", script.toString(), recording.toString());

    for (Result result : List.of(jfrShell, jafarShell)) {
      assertEquals(0, result.status(), result.output());
      assertFalse(result.output().contains("Invalid JFR Magic Number"), result.output());
      assertTrue(result.output().contains("17"), result.output());
      assertTrue(
          result.output().contains("Script executed successfully: 2 commands"), result.output());
    }
  }

  /**
   * A failing {@code ask}/{@code as-query}/{@code explain} line fails the script, in both shells'
   * script commands. The backend is pinned to an id that does not exist, so no request can leave
   * the machine (see {@code LlmCommandsTest}).
   */
  @Test
  void failingLlmCommandFailsTheScript(@TempDir Path dir) throws Exception {
    Path recording = Path.of("../jfr-shell-tck/src/main/resources/tck-test.jfr").toAbsolutePath();
    String script =
        "set llm.backend = test-nonexistent\nopen $1\nexplain\nask why\nas-query why\necho DONE\n";

    for (Result result :
        List.of(
            runJarWithStatus(
                dir, script, "script", "-", recording.toString(), "--continue-on-error"),
            runClassWithStatus(
                dir,
                "io.jafar.shell.Main",
                script,
                "script",
                "-",
                recording.toString(),
                "--continue-on-error"))) {
      assertEquals(1, result.status(), result.output());
      assertTrue(result.output().contains("Line 3: Nothing to explain yet"), result.output());
      assertTrue(result.output().contains("Line 4: "), result.output());
      assertTrue(result.output().contains("Line 5: "), result.output());
      assertTrue(result.output().contains("DONE"), result.output());
      assertTrue(result.output().contains("Executed 3/6 commands successfully."), result.output());
      assertFalse(result.output().contains("executed successfully"), result.output());
    }
  }

  @Test
  void routesOnlyJfrInvocationsToJfrCommandLine() {
    assertFalse(Main.isJfrCommandLine(new String[] {"script", "-"}));
    assertFalse(Main.isJfrCommandLine(new String[] {"script", "f.jfrs", "--continue-on-error"}));
    assertTrue(Main.isScriptCommandLine(new String[] {"script", "f.jfrs", "x.hprof"}));
    assertTrue(Main.isScriptCommandLine(new String[] {"-q", "script", "-"}));
    // jfr-shell's script command is the one with a backend
    assertTrue(Main.isJfrCommandLine(new String[] {"script", "--backend", "jdk", "f.jfrs"}));
    assertTrue(Main.isJfrCommandLine(new String[] {"script", "-b=jdk", "f.jfrs"}));
    assertFalse(Main.isScriptCommandLine(new String[] {"script", "--backend", "jdk", "f.jfrs"}));
    assertFalse(Main.isScriptCommandLine(new String[] {"dump.hprof", "script"}));
    assertTrue(Main.isJfrCommandLine(new String[] {"-q", "show", "x.jfr", "events"}));
    assertTrue(Main.isJfrCommandLine(new String[] {"--backend=jdk", "x.jfr"}));
    assertTrue(Main.isJfrCommandLine(new String[] {"--install-plugin", "p.jar"}));
    assertFalse(Main.isJfrCommandLine(new String[] {"dump.hprof"}));
    assertFalse(Main.isJfrCommandLine(new String[] {"-q", "--tui"}));
    assertFalse(Main.isJfrCommandLine(new String[] {}));
  }

  private record Result(int status, String output) {}

  private static String runJar(Path dir, String stdin, String... args) throws Exception {
    Result result = runJarWithStatus(dir, stdin, args);
    assertEquals(0, result.status(), result.output());
    return result.output();
  }

  private static Result runJarWithStatus(Path dir, String stdin, String... args) throws Exception {
    return run(dir, stdin, List.of("-jar", System.getProperty("jafar.shell.jar")), args);
  }

  /** Runs a main class from the jar, as a jbang launcher naming {@code //MAIN} does. */
  private static Result runClassWithStatus(Path dir, String mainClass, String stdin, String... args)
      throws Exception {
    return run(dir, stdin, List.of("-cp", System.getProperty("jafar.shell.jar"), mainClass), args);
  }

  private static Result run(Path dir, String stdin, List<String> launch, String... args)
      throws Exception {
    Path out = dir.resolve("out-" + System.nanoTime() + ".txt");
    List<String> command =
        new java.util.ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + dir));
    command.addAll(launch);
    command.addAll(List.of(args));
    Process process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(out.toFile()).start();
    process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
    process.getOutputStream().close();
    assertTrue(process.waitFor(120, TimeUnit.SECONDS), "shell did not exit");
    return new Result(process.exitValue(), Files.readString(out));
  }
}

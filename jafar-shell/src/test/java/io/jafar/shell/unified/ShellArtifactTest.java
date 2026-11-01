package io.jafar.shell.unified;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    // A stray endif in a script is the script's error; it must not close the caller's block
    Path stray = dir.resolve("stray.jfrs");
    Files.writeString(stray, "endif\n");
    String strayOutput =
        runJar(
            dir,
            String.join(
                "\n",
                "if 1 == 2",
                "echo never",
                "else",
                "script " + stray,
                "echo STILL-IN-ELSE",
                "endif",
                "echo AFTER-BLOCK",
                "exit",
                ""));
    assertTrue(strayOutput.contains("Line 1: Error: endif without if"), strayOutput);
    assertFalse(strayOutput.contains("Script executed successfully"), strayOutput);
    assertTrue(strayOutput.contains("STILL-IN-ELSE"), strayOutput);
    assertTrue(strayOutput.contains("AFTER-BLOCK"), strayOutput);
    // the caller's own endif still matches its if: no error once its block resumes
    assertFalse(
        strayOutput.substring(strayOutput.indexOf("STILL-IN-ELSE")).contains("without if"),
        strayOutput);
  }

  /**
   * Review findings: script errors stop the script; flags of 'show ${var}' use variables; closing a
   * session drops the cached rows of copies in other sessions' stores.
   */
  @Test
  void scriptStopsOnErrorAndCopiesForgetAClosedSession(@TempDir Path dir) throws Exception {
    Path recording = Path.of("../jfr-shell-tck/src/main/resources/tck-test.jfr").toAbsolutePath();
    Path dump = dir.resolve("dump.hprof");
    ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
        .dumpHeap(dump.toString(), true);
    Path failing = dir.resolve("failing.jfrs");
    Files.writeString(failing, "echo BEFORE\nset x = $missing\necho AFTER-THE-ERROR\n");

    String output =
        runJar(
            dir,
            String.join(
                "\n",
                "script " + failing,
                "open " + recording,
                "set --global starts = events/jdk.ThreadStart",
                "set --global n = 2",
                "show ${starts} --limit ${n} --format json",
                "open " + dump,
                "set copy = starts",
                "vars --info copy",
                "close 1",
                "vars --info copy",
                "exit",
                ""));

    assertTrue(output.contains("BEFORE"), output);
    assertFalse(output.contains("AFTER-THE-ERROR"), output);
    assertTrue(output.contains("Script completed with errors"), output);
    assertTrue(output.contains("Variable not found: missing"), output);
    // --limit ${n} applied: two JSON rows, not seventeen
    assertEquals(2, output.split("\"startTime\"", -1).length - 1, output);
    int afterClose = output.indexOf("Session closed: 1");
    assertTrue(afterClose > 0, output);
    assertTrue(output.substring(0, afterClose).contains("Cached: yes"), output);
    assertTrue(output.substring(afterClose).contains("Cached: no"), output);
  }

  /**
   * 'jafar-shell script file' on the command line used to go to jfr-shell, which reads only JFR: a
   * script opening a heap dump failed with "Unknown root: objects".
   */
  @Test
  void commandLineScriptRunsInThisShellAcrossFormats(@TempDir Path dir) throws Exception {
    Path recording = Path.of("../jfr-shell-tck/src/main/resources/tck-test.jfr").toAbsolutePath();
    Path dump = dir.resolve("dump.hprof");
    ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
        .dumpHeap(dump.toString(), true);
    Path script = dir.resolve("cross.jfrs");
    Files.writeString(
        script,
        String.join(
            "\n",
            "open $1",
            "set --global starts = events/jdk.ThreadStart | count()",
            "metadata --summary",
            "open $2",
            "set big = objects[shallow > ${3:-1000}] | count",
            "if ${starts} > 0 && ${big} > 0",
            "echo VERDICT both",
            "endif",
            ""));

    Run ok = runJarRaw(dir, "", "script", script.toString(), recording.toString(), dump.toString());

    assertEquals(0, ok.exit(), ok.out() + ok.err());
    assertEquals("", ok.err());
    assertTrue(ok.out().contains("VERDICT both"), ok.out());
    assertTrue(ok.out().contains("Types Summary:"), ok.out()); // jfr-shell's metadata command
    assertTrue(ok.out().contains("Script executed successfully: 8 commands"), ok.out());
    // Quiet, like jfr-shell's script mode: results, not confirmations
    assertFalse(ok.out().contains("Set starts"), ok.out());
    assertFalse(ok.out().contains("Opening "), ok.out());

    // A failing line: error on stderr, exit 1, the rest not run; '-' reads the script from stdin
    Run bad = runJarRaw(dir, "echo one\nset x = $missing\necho two\n", "script", "-");
    assertEquals(1, bad.exit(), bad.out() + bad.err());
    assertTrue(bad.out().contains("one"), bad.out());
    assertFalse(bad.out().contains("two"), bad.out());
    assertTrue(bad.err().contains("Variable not found: missing"), bad.err());
    assertTrue(bad.err().contains("Script completed with errors"), bad.err());

    Run cont =
        runJarRaw(
            dir, "echo one\nset x = $missing\necho two\n", "script", "-", "--continue-on-error");
    assertEquals(1, cont.exit());
    assertTrue(cont.out().contains("two"), cont.out());
  }

  /** 'record' saves what is typed as a script that 'jafar-shell script' replays. */
  @Test
  void recordsASessionThatReplaysAsAScript(@TempDir Path dir) throws Exception {
    Path recording = Path.of("../jfr-shell-tck/src/main/resources/tck-test.jfr").toAbsolutePath();
    Path saved = dir.resolve("session.jfrs");
    Path unfinished = dir.resolve("unfinished.jfrs");

    String output =
        runJar(
            dir,
            String.join(
                "\n",
                "record status",
                "record start " + saved,
                "open " + recording,
                "set n = events/jdk.ThreadStart | count()",
                "echo REPLAYED n=${n}",
                "record status",
                "record stop",
                "echo NOT-RECORDED",
                "record start " + unfinished,
                "echo LEFT-RUNNING",
                "exit",
                ""));

    assertTrue(output.contains("Not currently recording"), output);
    assertTrue(output.contains("Recording started: " + saved), output);
    assertTrue(output.contains("Recording to: " + saved), output);
    assertTrue(output.contains("Recording stopped: " + saved), output);
    assertTrue(output.contains("Recording saved to: " + unfinished), output); // saved at exit

    String script = Files.readString(saved);
    assertTrue(script.startsWith("# Jafar Shell Recording"), script);
    assertTrue(script.contains("\nopen " + recording + "\n"), script);
    assertTrue(script.contains("\necho REPLAYED n=${n}\n"), script);
    assertFalse(script.contains("record "), script);
    assertFalse(script.contains("NOT-RECORDED"), script);
    assertTrue(Files.readString(unfinished).contains("echo LEFT-RUNNING"));

    Run replay = runJarRaw(dir, "", "script", saved.toString());
    assertEquals(0, replay.exit(), replay.out() + replay.err());
    assertTrue(replay.out().contains("REPLAYED n=17"), replay.out());
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
    // 'script' runs in this shell now, so a script can open any format
    assertFalse(Main.isJfrCommandLine(new String[] {"script", "-"}));
    assertFalse(Main.isJfrCommandLine(new String[] {"script", "x.jfrs", "--continue-on-error"}));
    assertTrue(Main.isJfrCommandLine(new String[] {"--backend", "jdk", "script", "-"}));
    assertArrayEquals(
        new String[] {"x.jfrs", "a"}, Main.scriptArguments(new String[] {"script", "x.jfrs", "a"}));
    assertNull(Main.scriptArguments(new String[] {"x.jfr"}));
    assertTrue(Main.isJfrCommandLine(new String[] {"-q", "show", "x.jfr", "events"}));
    assertTrue(Main.isJfrCommandLine(new String[] {"--backend=jdk", "x.jfr"}));
    assertTrue(Main.isJfrCommandLine(new String[] {"--install-plugin", "p.jar"}));
    assertFalse(Main.isJfrCommandLine(new String[] {"dump.hprof"}));
    assertFalse(Main.isJfrCommandLine(new String[] {"-q", "--tui"}));
    assertFalse(Main.isJfrCommandLine(new String[] {}));
  }

  /** Exit status, stdout and stderr of one run, kept apart. */
  private record Run(int exit, String out, String err) {}

  private static Run runJarRaw(Path dir, String stdin, String... args) throws Exception {
    Path out = dir.resolve("raw-out.txt");
    Path err = dir.resolve("raw-err.txt");
    List<String> command =
        new java.util.ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + dir,
                "-jar",
                System.getProperty("jafar.shell.jar")));
    command.addAll(List.of(args));
    Process process =
        new ProcessBuilder(command)
            .directory(dir.toFile())
            .redirectOutput(out.toFile())
            .redirectError(err.toFile())
            .start();
    process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
    process.getOutputStream().close();
    assertTrue(process.waitFor(120, TimeUnit.SECONDS), "shell did not exit");
    // The JVM may print 'Picked up JAVA_TOOL_OPTIONS' on stderr in some environments
    String errText =
        Files.readAllLines(err).stream()
            .filter(l -> !l.startsWith("Picked up JAVA_TOOL_OPTIONS"))
            .collect(java.util.stream.Collectors.joining("\n"));
    return new Run(process.exitValue(), Files.readString(out), errText);
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

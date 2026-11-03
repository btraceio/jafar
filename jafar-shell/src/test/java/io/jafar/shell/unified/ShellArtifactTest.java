package io.jafar.shell.unified;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
    // listed as every reported error is, without the printed "Error: " prefix
    assertTrue(strayOutput.contains("Line 1: endif without if"), strayOutput);
    assertFalse(strayOutput.contains("Script executed successfully"), strayOutput);
    assertTrue(strayOutput.contains("STILL-IN-ELSE"), strayOutput);
    assertTrue(strayOutput.contains("AFTER-BLOCK"), strayOutput);
    // the caller's own endif still matches its if: no error once its block resumes
    assertFalse(
        strayOutput.substring(strayOutput.indexOf("STILL-IN-ELSE")).contains("without if"),
        strayOutput);
  }

  /**
   * {@code -f script} opens a file named {@code script}, as {@code -f} does any file; before, the
   * value was taken for the script command.
   */
  @Test
  void fileOptionValueIsNotTheScriptCommand(@TempDir Path dir) throws Exception {
    Result result = runJarWithStatus(dir, "", "-f", "script");

    assertEquals(1, result.status(), result.output());
    assertTrue(result.output().contains("Error: File not found: script"), result.output());
  }

  /**
   * {@code JFR_SHELL_VERBOSE} brings back the lines script mode leaves out, as it does for {@code
   * jfr-shell script}.
   */
  @Test
  void verboseSwitchAppliesToScriptMode(@TempDir Path dir) throws Exception {
    Path recording = Path.of("../jfr-shell-tck/src/main/resources/tck-test.jfr").toAbsolutePath();
    String script = "open $1\nset n = events/jdk.ThreadStart | count()\necho N=${n}\n";

    Result quiet = runJarWithStatus(dir, script, "script", "-", recording.toString());
    Result verbose =
        run(
            dir,
            script,
            java.util.Map.of("JFR_SHELL_VERBOSE", "true"),
            List.of("-jar", System.getProperty("jafar.shell.jar")),
            "script",
            "-",
            recording.toString());

    assertEquals(0, quiet.status(), quiet.output());
    assertFalse(quiet.output().contains("Session 1 opened"), quiet.output());
    assertFalse(quiet.output().contains("Set n ="), quiet.output());
    assertEquals(0, verbose.status(), verbose.output());
    assertTrue(verbose.output().contains("Session 1 opened"), verbose.output());
    assertTrue(verbose.output().contains("Set n ="), verbose.output());
    assertTrue(verbose.output().contains("N=17"), verbose.output());
  }

  /** The same guards on the command line, where a script that leaves an if open has failed. */
  @Test
  void scriptCommandLineClosesOpenIfAndFails(@TempDir Path dir) throws Exception {
    Path open = dir.resolve("open.jfrs");
    Files.writeString(open, "echo START\nif 1 == 2\necho never\n");

    Result result = runJarWithStatus(dir, "", "script", open.toString());

    assertEquals(1, result.status(), result.output());
    assertTrue(result.output().contains("START"), result.output());
    assertFalse(result.output().contains("never"), result.output());
    assertTrue(result.output().contains("open.jfrs left 1 if-block(s) open"), result.output());
    assertTrue(result.output().contains("Line 3: left 1 if-block(s) open"), result.output());
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
    // stderr may carry the heap dump's parse progress ("Parsing... 40%"): HeapSession prints it
    // only once parsing outlasts a delay, so on a slow machine. Nothing else may be there.
    assertTrue(onlyProgress(ok.err()), ok.err());
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

  /**
   * A condition that cannot be evaluated is an error, not false: no branch runs — not the if's, and
   * not the else, which used to run as though the condition had been false.
   */
  @Test
  void failedConditionRunsNoBranch(@TempDir Path dir) throws Exception {
    String lines = "if 1 / 0 > 1\necho THEN\nelse\necho ELSE\nendif\necho AFTER\n";

    Run cont = runJarRaw(dir, lines, "script", "-", "--continue-on-error");
    assertEquals(1, cont.exit(), cont.out() + cont.err());
    assertFalse(cont.out().contains("THEN"), cont.out());
    assertFalse(cont.out().contains("ELSE"), cont.out());
    assertTrue(cont.out().contains("AFTER"), cont.out());
    assertTrue(cont.err().contains("Condition evaluation failed: Division by zero"), cont.err());

    // Typed at the prompt: the error is shown, the block stays open to its endif, no branch runs
    String typed = runJar(dir, lines + "exit\n");
    assertTrue(typed.contains("skipping every branch of this if-block"), typed);
    assertFalse(typed.contains("THEN"), typed);
    assertFalse(typed.contains("ELSE"), typed);
    assertTrue(typed.contains("AFTER"), typed);
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

  /**
   * Lines that start with a command word but are queries: jfr-shell's handlers would print every
   * chunk for {@code | count()} and ignore {@code --limit}. {@code cp <type> | crossref()} is the
   * one pipeline jfr-shell's handler takes; it runs the {@code crossref()} query, so it prints the
   * same row as the query forms the unified shell evaluates itself.
   */
  @Test
  void keepsPipedAndLimitedLinesAsQueries(@TempDir Path dir) throws Exception {
    Path recording = Path.of("../jfr-shell-tck/src/main/resources/tck-test.jfr").toAbsolutePath();

    String output =
        runJar(
            dir,
            String.join(
                "\n",
                "open " + recording,
                "echo [count]",
                "chunks --format json | count()",
                "echo [limit]",
                "chunks --limit 1",
                "echo [crossref]",
                "cp jdk.types.Symbol | crossref()",
                "echo [crossref-query]",
                "constants/jdk.types.Symbol | crossref()",
                "echo [crossref-show]",
                "show constants/jdk.types.Symbol | crossref()",
                "echo [regex]",
                "metadata --regex ^jdk\\.Thread(Start|End)$",
                "exit",
                ""));

    assertFalse(output.contains("Error"), output);
    String count = between(output, "[count]", "[limit]");
    assertTrue(count.contains("\"count\""), count); // the count, not the chunk rows
    assertFalse(count.contains("startTicks"), count);
    assertTrue(between(output, "[limit]", "[crossref]").contains("(1 rows)"), output);
    // The query forms printed "Query error: Unknown pipeline function: crossref", which the
    // "Error" check above does not catch; each form is checked on its own.
    String[][] forms = {
      {"[crossref]", "[crossref-query]"},
      {"[crossref-query]", "[crossref-show]"},
      {"[crossref-show]", "[regex]"}
    };
    org.junit.jupiter.api.Assertions.assertAll(
        java.util.Arrays.stream(forms)
            .map(
                m ->
                    () -> {
                      String crossref = between(output, m[0], m[1]);
                      assertFalse(crossref.toLowerCase().contains("error"), crossref);
                      assertTrue(crossref.contains("referencingEventTypes"), crossref);
                      assertTrue(crossref.contains("jdk.types.Symbol"), crossref);
                    }));
    String regex = output.substring(output.indexOf("[regex]"));
    assertTrue(regex.contains("jdk.ThreadStart") && regex.contains("jdk.ThreadEnd"), regex);
  }

  private static String between(String s, String from, String to) {
    int a = s.indexOf(from);
    int b = s.indexOf(to, a + from.length());
    assertTrue(a >= 0 && b > a, "markers " + from + ".." + to + " in " + s);
    return s.substring(a, b);
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

  /**
   * JfrPath values that are constant-pool wrappers ({@code {name=G1New}}, {@code {cause=...}},
   * {@code {string=java/lang/Object}}) print as their plain value, as jfr-shell prints them, in
   * every output format. See {@code doc/agents/DataShapes.md}.
   */
  @Test
  void printsJfrConstantsAsPlainValues(@TempDir Path dir) throws Exception {
    Path recording = dir.resolve("gc.jfr");
    try (jdk.jfr.Recording r = new jdk.jfr.Recording()) {
      r.enable("jdk.GarbageCollection");
      r.enable("jdk.JavaMonitorWait").withThreshold(java.time.Duration.ZERO);
      r.start();
      System.gc();
      Object lock = new Object();
      synchronized (lock) {
        lock.wait(20);
      }
      r.stop();
      r.dump(recording);
    }
    Path script = dir.resolve("constants.jfrs");
    Files.writeString(
        script,
        String.join(
            "\n",
            "open " + recording,
            "echo TABLE",
            "events/jdk.GarbageCollection | groupBy(cause)",
            "events/jdk.JavaMonitorWait | groupBy(monitorClass/name)",
            "echo CSV",
            "show events/jdk.GarbageCollection --limit 1 --format csv",
            "echo JSON",
            "show events/jdk.GarbageCollection --limit 1 --format json",
            "echo META",
            "show metadata/jdk.types.Symbol --format json",
            ""));

    assertPlainConstants(runJar(dir, "", "script", script.toString()));
    // jfr-shell's own entry point, from the same jar, prints them the same way
    Result jfrShell =
        runClassWithStatus(dir, "io.jafar.shell.Main", "", "script", script.toString());
    assertEquals(0, jfrShell.status(), jfrShell.output());
    assertPlainConstants(jfrShell.output());
  }

  private static void assertPlainConstants(String output) {
    String data = output.substring(0, output.indexOf("META"));
    assertFalse(data.contains("{cause="), output);
    assertFalse(data.contains("{name="), output);
    assertFalse(data.contains("{string="), output);
    assertFalse(data.contains("\"cause\": {"), output);
    String table = output.substring(output.indexOf("TABLE"), output.indexOf("CSV"));
    assertTrue(table.contains("| System.gc()"), output);
    assertTrue(table.contains("| java/lang/Object"), output);
    String csv = output.substring(output.indexOf("CSV"), output.indexOf("JSON"));
    assertTrue(csv.contains(",System.gc(),"), output);
    String json = output.substring(output.indexOf("JSON"), output.indexOf("META"));
    assertTrue(json.contains("\"cause\": \"System.gc()\""), output);
    // metadata is structure: a one-field type's fieldsByName is data and keeps its key
    String meta = output.substring(output.indexOf("META"));
    assertTrue(meta.matches("(?s).*\"fieldsByName\": \\{\\s*\"string\": \\{.*"), output);
  }

  /**
   * A projected path — {@code events/<type>/<path>}, {@code metadata/<type>/fields}, {@code
   * metadata/<type>/<field>}, {@code constants/<type>/<path>}, {@code chunks/<n>/<path>} — prints
   * the values it names, and the same values in both shells. The JSON of the two entry points is
   * compared whole, so a projection that drifts in either fails here.
   */
  @Test
  void projectedPathsMatchJfrShell(@TempDir Path dir) throws Exception {
    Path recording = dir.resolve("gc.jfr");
    try (jdk.jfr.Recording r = new jdk.jfr.Recording()) {
      r.enable("jdk.GarbageCollection");
      r.start();
      System.gc();
      r.stop();
      r.dump(recording);
    }
    Path script = dir.resolve("projections.jfrs");
    Files.writeString(
        script,
        String.join(
            "\n",
            "open " + recording,
            "echo P1",
            "show events/jdk.GarbageCollection/cause --format json",
            "echo P2",
            "show metadata/jdk.GarbageCollection/fields --format json",
            "echo P3",
            "show metadata/jdk.GarbageCollection/cause --format json",
            "echo P4",
            "show constants/jdk.types.GCName/name --format json",
            "echo P5",
            "show chunks/0/size --format json",
            "echo TABLE",
            "show events/jdk.GarbageCollection/cause --limit 1",
            "echo END",
            ""));

    String unified = runJar(dir, "", "script", script.toString());
    Result jfrShell =
        runClassWithStatus(dir, "io.jafar.shell.Main", "", "script", script.toString());
    assertEquals(0, jfrShell.status(), jfrShell.output());

    String json = between(unified, "P1", "TABLE");
    assertTrue(between(json, "P1", "P2").contains("\"System.gc()\""), unified);
    assertFalse(between(json, "P1", "P2").contains("duration"), unified);
    assertTrue(between(json, "P2", "P3").contains("\"cause\""), unified);
    assertTrue(between(json, "P3", "P4").contains("\"type\": \"jdk.types.GCCause\""), unified);
    assertTrue(between(json, "P4", "P5").contains("\"G1New\""), unified);
    // one chunk, so chunk 0 is as long as the file
    assertTrue(
        between(unified, "P5", "TABLE").contains(String.valueOf(Files.size(recording))), unified);
    String table = between(unified, "TABLE", "END");
    assertTrue(table.contains("value"), unified);
    assertTrue(table.contains("System.gc()"), unified);
    assertFalse(table.contains("duration"), unified);
    // last, so that a missing projection fails above on what it lost, not on a diff
    assertEquals(between(jfrShell.output(), "P1", "TABLE"), json, unified);
  }

  @Test
  void unwrapsOnlyJfrRecordingValues() {
    List<Map<String, Object>> rows = List.of(Map.of("value", Map.of("name", "kept")));

    assertSame(rows, Shell.forDisplay(rows, "hdump", "objects"));
    assertSame(rows, Shell.forDisplay(rows, "pprof", "samples"));
    assertSame(rows, Shell.forDisplay(rows, "otlp", "samples"));
    assertSame(rows, Shell.forDisplay(rows, "jfr", "metadata/jdk.types.Symbol"));
    assertSame(rows, Shell.forDisplay(rows, "jfr", "chunks"));
    assertEquals(List.of(Map.of("value", "kept")), Shell.forDisplay(rows, "jfr", "events/x"));
    assertEquals(List.of(Map.of("value", "kept")), Shell.forDisplay(rows, "jfr", null));
    // the parsed query, as handleShow passes it, decides the same way as its text
    assertSame(
        rows,
        Shell.forDisplay(
            rows, "jfr", io.jafar.shell.jfrpath.JfrPathParser.parse("metadata/jdk.types.Symbol")));
    assertEquals(
        List.of(Map.of("value", "kept")),
        Shell.forDisplay(rows, "jfr", io.jafar.shell.jfrpath.JfrPathParser.parse("events/x")));
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
    // A pipeline or --limit anywhere makes it a query: jfr-shell's handlers would ignore them
    assertFalse(Shell.isJfrCommand("chunks --format json | count()"));
    assertFalse(Shell.isJfrCommand("chunks --format json |count()"));
    assertFalse(Shell.isJfrCommand("chunks --limit 1"));
    assertFalse(Shell.isJfrCommand("constants --limit 2"));
    assertFalse(Shell.isJfrCommand("constants --format json | count()"));
    assertFalse(Shell.isJfrCommand("cp jdk.types.Symbol | count()"));
    assertFalse(Shell.isJfrCommand("cp jdk.types.Symbol | crossref() | count()"));
    assertFalse(Shell.isJfrCommand("cp jdk.types.Symbol |crossref()"));
    assertFalse(Shell.isJfrCommand("chunks | crossref()"));
    // ...except the pipeline cmdCp takes, and a '|' inside an argument
    assertTrue(Shell.isJfrCommand("cp jdk.types.Symbol | crossref()"));
    assertTrue(Shell.isJfrCommand("CONSTANTS jdk.types.Symbol |  CrossRef() "));
    assertTrue(Shell.isJfrCommand("metadata --regex ^(jdk|datadog)\\.Thread.*"));
    assertTrue(Shell.isJfrCommand("chunks --format json"));
    assertTrue(Shell.isJfrCommand("metadata class jdk.ThreadStart --tree --depth 2"));
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
    assertFalse(Main.isJfrCommandLine(new String[] {"script", "-"}));
    assertFalse(Main.isJfrCommandLine(new String[] {"script", "f.jfrs", "--continue-on-error"}));
    assertTrue(Main.isScriptCommandLine(new String[] {"script", "f.jfrs", "x.hprof"}));
    assertTrue(Main.isScriptCommandLine(new String[] {"-q", "script", "-"}));
    // after 'script', -b/--backend is the script command's own: it runs in this shell
    assertFalse(Main.isJfrCommandLine(new String[] {"script", "--backend", "jdk", "f.jfrs"}));
    assertFalse(Main.isJfrCommandLine(new String[] {"script", "-b=jdk", "f.jfrs"}));
    assertTrue(Main.isScriptCommandLine(new String[] {"script", "--backend", "jdk", "f.jfrs"}));
    // before the command word, a JFR option still means jfr-shell's command line
    assertTrue(Main.isJfrCommandLine(new String[] {"--backend", "jdk", "script", "-"}));
    assertFalse(Main.isScriptCommandLine(new String[] {"dump.hprof", "script"}));
    // an option's value is not the command: '-f script' opens a file named script
    assertFalse(Main.isScriptCommandLine(new String[] {"-f", "script"}));
    assertFalse(Main.isScriptCommandLine(new String[] {"--file", "script"}));
    assertFalse(Main.isScriptCommandLine(new String[] {"-qf", "script"}));
    assertFalse(Main.isScriptCommandLine(new String[] {"--file=script"}));
    assertFalse(Main.isJfrCommandLine(new String[] {"-f", "script"}));
    assertFalse(Main.isJfrCommandLine(new String[] {"-f", "show"}));
    assertTrue(Main.isScriptCommandLine(new String[] {"-q", "script", "f.jfrs"}));
    assertTrue(Main.isJfrCommandLine(new String[] {"-b", "jdk", "script", "f.jfrs"}));
    assertEquals(2, Main.firstWordIndex(new String[] {"-f", "x.jfr", "script"}));
    assertEquals(-1, Main.firstWordIndex(new String[] {"-qf", "script"}));
    assertTrue(Main.isJfrCommandLine(new String[] {"-q", "show", "x.jfr", "events"}));
    assertTrue(Main.isJfrCommandLine(new String[] {"--backend=jdk", "x.jfr"}));
    assertTrue(Main.isJfrCommandLine(new String[] {"--install-plugin", "p.jar"}));
    assertFalse(Main.isJfrCommandLine(new String[] {"dump.hprof"}));
    assertFalse(Main.isJfrCommandLine(new String[] {"-q", "--tui"}));
    assertFalse(Main.isJfrCommandLine(new String[] {}));
  }

  @Test
  void stderrClassifierAcceptsOnlyProgress() {
    // The stderr of the CI run that failed the old 'stderr is empty' assertion, verbatim
    assertTrue(
        onlyProgress("\rParsing... 40%\rParsing... 80%\rComplete... 100%\rComplete... 100%\n"));
    assertTrue(onlyProgress(""));
    assertFalse(onlyProgress("\rParsing... 40%\nError: Variable not found: x\n"));
    assertFalse(onlyProgress("Exception in thread \"main\" java.lang.IllegalStateException"));
  }

  /** Whether stderr holds nothing but progress lines such as "Parsing... 40%". */
  private static boolean onlyProgress(String err) {
    for (String part : err.split("[\\r\\n]+")) {
      if (!part.isBlank() && !part.trim().matches(".*\\.\\.\\. \\d{1,3}%")) {
        return false;
      }
    }
    return true;
  }

  private record Result(int status, String output) {}

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
    ProcessBuilder builder =
        new ProcessBuilder(command)
            .directory(dir.toFile())
            .redirectOutput(out.toFile())
            .redirectError(err.toFile());
    builder.environment().remove("JFR_SHELL_VERBOSE"); // as in run(): no leak from the machine
    Process process = builder.start();
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
    return run(dir, stdin, java.util.Map.of(), launch, args);
  }

  private static Result run(
      Path dir,
      String stdin,
      java.util.Map<String, String> env,
      List<String> launch,
      String... args)
      throws Exception {
    Path out = dir.resolve("out-" + System.nanoTime() + ".txt");
    List<String> command =
        new java.util.ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + dir));
    command.addAll(launch);
    command.addAll(List.of(args));
    ProcessBuilder builder =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(out.toFile());
    builder.environment().remove("JFR_SHELL_VERBOSE"); // so the machine's setting cannot leak in
    builder.environment().putAll(env);
    Process process = builder.start();
    process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
    process.getOutputStream().close();
    assertTrue(process.waitFor(120, TimeUnit.SECONDS), "shell did not exit");
    return new Result(process.exitValue(), Files.readString(out));
  }
}

package io.jafar.shell.unified;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jline.reader.Candidate;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every first word Tab offers at the jafar-shell prompt must be a command that prompt runs
 * (Verification.md R1). On a JFR session the first-word list comes from jfr-shell's {@code
 * CommandCompleter}, a list this shell does not own; this test reads that list through the shell's
 * own completer rather than copying it (R7), so a name added there and not handled here fails.
 */
class ShellCompletedCommandsTest {
  private static final Path RECORDING =
      Path.of("../jfr-shell-tck/src/main/resources/tck-test.jfr").toAbsolutePath();

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private Terminal terminal;
  private Shell shell;

  @BeforeEach
  void setUp() throws Exception {
    // Not TerminalBuilder.system(false).streams(...): on Linux that builds a PosixPtyTerminal,
    // whose output crosses a pty and reaches 'out' on a pump thread, so what a line printed may
    // arrive after the assertion reads it. A DumbTerminal writes straight to 'out' on this thread.
    terminal =
        new DumbTerminal(
            "test", "dumb", new ByteArrayInputStream(new byte[0]), out, StandardCharsets.UTF_8);
    shell = new Shell(terminal);
  }

  @AfterEach
  void tearDown() throws Exception {
    shell.close();
  }

  @Test
  void everyFirstWordOfferedOnJfrSessionRuns() {
    run("open " + RECORDING);
    List<String> offered = firstWords();
    // The JFR module's list is what is being checked; make sure it is the one we got.
    assertTrue(offered.contains("metadata") && offered.contains("record"), offered.toString());

    // 'close' ends the session and 'exit'/'quit' the shell, so they go last.
    List<String> last = List.of("close", "exit", "quit");
    offered.sort(Comparator.comparing(last::contains));

    List<String> refused = new ArrayList<>();
    for (String word : offered) {
      String output = run(word);
      if (output.contains("Unknown command")) {
        refused.add(word);
      }
      if (word.equals("if")) {
        run("endif"); // a bare 'if' opens a (false) block; close it so later lines still run
      }
    }
    assertEquals(List.of(), refused, "offered by completion, refused by the shell");
  }

  @Test
  void sessionIsAnAliasForUse() {
    run("open " + RECORDING);
    run("open " + RECORDING);

    String output = run("session 1");

    assertTrue(output.contains("Switched to session 1: tck-test.jfr"), output);
    assertTrue(run("session").contains("Error: Usage: session <id|alias>"), "bare 'session'");
    assertTrue(run("use").contains("Error: Usage: use <id|alias>"), "bare 'use'");
    assertTrue(run("session 9").contains("No such session: 9"), "unknown id");
  }

  @Test
  void recordWritesTypedCommandsAsReplayableScript(@TempDir Path dir) throws Exception {
    Path script = dir.resolve("typed.jfrs");

    assertTrue(run("record status").contains("Not currently recording"));
    assertTrue(run("record start " + script).contains("Recording started: " + script));
    assertTrue(run("record status").contains("Recording to: " + script));
    run("open " + RECORDING);
    run("set n = events/jdk.ThreadStart | count()");
    run("echo starts=${n}");
    assertTrue(run("record start").contains("Error: Already recording to: " + script));
    assertTrue(run("record stop").contains("Recording stopped: " + script));
    run("echo after-stop");

    String recorded = Files.readString(script, StandardCharsets.UTF_8);
    List<String> commands =
        recorded.lines().filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
    assertEquals(
        List.of(
            "open " + RECORDING, "set n = events/jdk.ThreadStart | count()", "echo starts=${n}"),
        commands,
        recorded);

    // The recording replays in this shell
    run("close");
    assertTrue(run("script " + script).contains("starts=17"));
  }

  @Test
  void exitSavesRecordingInProgress(@TempDir Path dir) throws Exception {
    Path script = dir.resolve("unsaved.jfrs");
    run("record start " + script);
    run("echo hello");

    String output = run("exit");

    assertTrue(output.contains("Recording saved to: " + script), output);
    String recorded = Files.readString(script, StandardCharsets.UTF_8);
    assertTrue(recorded.contains("echo hello"), recorded);
    assertTrue(recorded.contains("# Recording stopped:"), recorded);
    assertFalse(recorded.contains("exit"), recorded);
  }

  @Test
  void recordWithoutSubcommandPrintsUsage() {
    assertTrue(
        run("record").contains("Error: Usage: record start [path] | record stop | record status"));
    assertTrue(run("record pause").contains("Error: Unknown record command: pause"));
    assertTrue(run("record stop").contains("Error: Not currently recording"));
  }

  /** A {@code record} line in a branch that is not running neither runs nor lands in the file. */
  @Test
  void recordInSkippedBranchIsNeitherRunNorRecorded(@TempDir Path dir) throws Exception {
    Path script = dir.resolve("branch.jfrs");
    run("record start " + script);
    run("if 1 > 2");
    String skipped = run("record stop");
    run("endif");

    assertFalse(skipped.contains("Recording stopped"), skipped);
    assertTrue(run("record status").contains("Recording to: " + script));
    run("record stop");
    String recorded = Files.readString(script, StandardCharsets.UTF_8);
    assertFalse(recorded.contains("record stop"), recorded);
    assertTrue(recorded.contains("if 1 > 2") && recorded.contains("endif"), recorded);
  }

  /** Runs one line as if typed at the prompt, and returns what it printed. */
  private String run(String line) {
    out.reset();
    shell.handleLine(line);
    terminal.flush(); // not every command flushes; the prompt's next read would
    return out.toString(StandardCharsets.UTF_8);
  }

  private List<String> firstWords() {
    List<Candidate> candidates = new ArrayList<>();
    shell.completer().complete(shell.lineReader(), new DefaultParser().parse("", 0), candidates);
    List<String> words = new ArrayList<>();
    for (Candidate c : candidates) {
      if (!words.contains(c.value())) {
        words.add(c.value());
      }
    }
    return words;
  }
}

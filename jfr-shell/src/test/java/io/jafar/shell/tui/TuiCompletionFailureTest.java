package io.jafar.shell.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.cli.ShellCompleter;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The TUI kept the input line when completion failed, but its catch block returned without a word
 * (R3): TAB simply did nothing. The failure must be shown, as a hint: the output pane is written by
 * the command thread while a command runs.
 */
class TuiCompletionFailureTest {

  private static final Path TCK_RECORDING =
      Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr")
          .toAbsolutePath()
          .normalize();

  @TempDir Path tempDir;
  private final ExecutorService pool = Executors.newSingleThreadExecutor();
  private SessionManager<Session> sessions;
  private Path recording;
  private TuiContext ctx;
  private TuiCommandExecutor executor;

  @BeforeEach
  void openThenBreakTheRecording() throws Exception {
    recording = tempDir.resolve("rec.jfr");
    Files.copy(TCK_RECORDING, recording);
    sessions =
        new SessionManager<>(
            (path, c) -> new JFRSession(path, (ParsingContext) c), ParsingContext.create());
    sessions.open(recording, null);
    Files.delete(recording);
    Files.writeString(recording, "<!DOCTYPE html>not a recording", StandardCharsets.US_ASCII);

    ctx = new TuiContext();
    ctx.tabs.add(new TuiContext.ResultTab("main"));
    executor = new TuiCommandExecutor(ctx, pool);
    executor.setCompleter(new ShellCompleter(sessions, null));
  }

  @AfterEach
  void close() throws Exception {
    pool.shutdownNow();
    sessions.closeAll();
  }

  @Test
  void failedCompletionKeepsTheInputAndReportsWhy() {
    type("cp ");
    executor.openCompletionPopup();

    assertEquals("cp ", ctx.inputState.text(), "input kept");
    assertEquals(
        "Completion failed: Failed to discover constant-pool types of "
            + recording
            + ": Error occurred while parsing JFR recording: Invalid JFR Magic Number: 3c21444f",
        ctx.hintMessage);
  }

  /** While a command runs, its thread appends to asyncOutputBuffer; completion must not. */
  @Test
  void failedCompletionDuringACommandLeavesTheCommandOutputAlone() {
    List<String> commandOutput = new ArrayList<>(List.of("  row 1"));
    ctx.asyncOutputBuffer = commandOutput;
    type("cp ");
    executor.openCompletionPopup();

    assertEquals(List.of("  row 1"), commandOutput, "command output untouched");
    assertTrue(
        String.valueOf(ctx.hintMessage).startsWith("Completion failed: "),
        () -> "hint: " + ctx.hintMessage);
  }

  /** File completion has its own null-reader path; it must not fail in the TUI. */
  @Test
  void fileCompletionStillWorks() throws Exception {
    Files.createFile(tempDir.resolve("another.jfr"));
    type("open " + tempDir + "/ano");
    executor.openCompletionPopup();

    assertNull(ctx.hintMessage, "no failure");
    assertEquals("open " + tempDir.resolve("another.jfr"), ctx.inputState.text());
  }

  private void type(String text) {
    ctx.inputState.setText(text);
    ctx.inputState.moveCursorToEnd();
  }
}

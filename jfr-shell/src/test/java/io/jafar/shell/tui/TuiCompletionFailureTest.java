package io.jafar.shell.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.cli.ShellCompleter;
import io.jafar.shell.core.SessionManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The TUI kept the input line when completion failed, but its catch block returned without a word
 * (R3): TAB simply did nothing. The failure must reach the output pane.
 */
class TuiCompletionFailureTest {

  private static final Path TCK_RECORDING =
      Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr")
          .toAbsolutePath()
          .normalize();

  @TempDir Path tempDir;
  private final ExecutorService pool = Executors.newSingleThreadExecutor();
  private SessionManager<JFRSession> sessions;

  @AfterEach
  void close() throws Exception {
    pool.shutdownNow();
    if (sessions != null) sessions.closeAll();
  }

  @Test
  void failedCompletionKeepsTheInputAndReportsWhy() throws Exception {
    Path recording = tempDir.resolve("rec.jfr");
    Files.copy(TCK_RECORDING, recording);
    sessions =
        new SessionManager<>(
            (path, c) -> new JFRSession(path, (ParsingContext) c), ParsingContext.create());
    sessions.open(recording, null);
    Files.delete(recording);
    Files.writeString(recording, "<!DOCTYPE html>not a recording", StandardCharsets.US_ASCII);

    TuiContext ctx = new TuiContext();
    ctx.tabs.add(new TuiContext.ResultTab("main"));
    TuiCommandExecutor executor = new TuiCommandExecutor(ctx, pool);
    executor.setCompleter(new ShellCompleter(sessions, null));
    ctx.inputState.setText("cp ");
    ctx.inputState.moveCursorToEnd();

    executor.openCompletionPopup();

    assertEquals("cp ", ctx.inputState.text(), "input kept");
    String output = String.join("\n", ctx.activeTab().lines);
    assertTrue(
        output.contains("Failed to discover constant-pool types of " + recording),
        () -> "output pane: " + output);
  }
}

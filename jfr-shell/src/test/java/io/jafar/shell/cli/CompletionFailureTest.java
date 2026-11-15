package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Completing chunk ids or constant-pool types re-reads the recording. When that read fails, the
 * user must see why and keep what they typed. JLine (3.21.0, {@code LineReaderImpl.doComplete})
 * catches whatever a completer throws, logs "Error while finding completion candidates" through
 * {@code java.util.logging} and offers nothing: the line survived, but TAB silently did nothing and
 * the cause was never shown.
 */
class CompletionFailureTest {

  private static final Path TCK_RECORDING =
      Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr")
          .toAbsolutePath()
          .normalize();

  @TempDir Path tempDir;
  private SessionManager<Session> sessions;
  private Path recording;

  @BeforeEach
  void openThenBreakTheRecording() throws Exception {
    recording = tempDir.resolve("rec.jfr");
    Files.copy(TCK_RECORDING, recording);
    sessions =
        new SessionManager<>(
            (path, c) -> new JFRSession(path, (ParsingContext) c), ParsingContext.create());
    sessions.open(recording, null);
    // Chunk ids and constant-pool types are discovered lazily, on first completion. Replace the
    // file (a new inode, so what the open session has mapped stays valid) with one that is not a
    // recording.
    Files.delete(recording);
    Files.writeString(recording, "<!DOCTYPE html>not a recording", StandardCharsets.US_ASCII);
  }

  @AfterEach
  void close() throws Exception {
    sessions.closeAll();
  }

  @Test
  void failedChunkIdCompletionKeepsTheLineAndSaysWhy() throws Exception {
    Typed typed = typeWithTab("show chunks/", "1");
    assertEquals("show chunks/1", typed.line);
    assertTrue(
        typed.screen.contains("Failed to list chunks of " + recording),
        () -> "screen: " + typed.screen);
  }

  @Test
  void failedConstantPoolCompletionKeepsTheLineAndSaysWhy() throws Exception {
    Typed typed = typeWithTab("cp ", "jdk.types.Symbol");
    assertEquals("cp jdk.types.Symbol", typed.line);
    assertTrue(
        typed.screen.contains("Failed to discover constant-pool types of " + recording),
        () -> "screen: " + typed.screen);
  }

  private record Typed(String line, String screen) {}

  /**
   * Types {@code before}, presses TAB, types {@code after} and Enter, through a real LineReader.
   */
  private Typed typeWithTab(String before, String after) throws Exception {
    byte[] keys = (before + "\t" + after + "\r").getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream screen = new ByteArrayOutputStream();
    try (Terminal terminal =
        TerminalBuilder.builder()
            .system(false)
            .streams(new ByteArrayInputStream(keys), screen)
            .type("xterm")
            .encoding(StandardCharsets.UTF_8)
            .size(new org.jline.terminal.Size(200, 40))
            .build()) {
      LineReader reader =
          LineReaderBuilder.builder()
              .terminal(terminal)
              .completer(new ShellCompleter(sessions, null))
              .build();
      String line = reader.readLine("jfr> ");
      return new Typed(line, screen.toString(StandardCharsets.UTF_8));
    }
  }
}

package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandRecorderTest {

  @Test
  void testStartStopRecording(@TempDir Path tempDir) throws IOException {
    Path recordingPath = tempDir.resolve("test.jfrs");

    CommandRecorder recorder = new CommandRecorder();
    assertFalse(recorder.isRecording());

    recorder.start(recordingPath);
    assertTrue(recorder.isRecording());
    assertEquals(recordingPath, recorder.getCurrentRecordingPath());

    recorder.recordCommand("command1");
    recorder.recordCommand("command2");

    recorder.stop();
    assertFalse(recorder.isRecording());

    // Verify file contents
    List<String> lines = Files.readAllLines(recordingPath);
    assertTrue(lines.stream().anyMatch(line -> line.contains("JFR Shell Recording")));
    assertTrue(lines.stream().anyMatch(line -> line.equals("command1")));
    assertTrue(lines.stream().anyMatch(line -> line.equals("command2")));
  }

  @Test
  void testAlreadyRecordingError(@TempDir Path tempDir) throws IOException {
    Path path1 = tempDir.resolve("test1.jfrs");
    Path path2 = tempDir.resolve("test2.jfrs");

    CommandRecorder recorder = new CommandRecorder();
    recorder.start(path1);

    assertThrows(IllegalStateException.class, () -> recorder.start(path2));

    recorder.stop();
  }

  @Test
  void testStopWhenNotRecordingError() {
    CommandRecorder recorder = new CommandRecorder();
    assertThrows(IllegalStateException.class, recorder::stop);
  }

  @Test
  void testRecordCommandWhenNotRecording(@TempDir Path tempDir) throws IOException {
    CommandRecorder recorder = new CommandRecorder();

    // Should not throw, just silently ignore
    recorder.recordCommand("test");

    assertFalse(recorder.isRecording());
  }

  @Test
  void testDefaultRecordingPath(@TempDir Path tempDir) throws IOException {
    // Create a recorder with default path
    CommandRecorder recorder = new CommandRecorder();

    // Start with null path (will use default)
    recorder.start(null);

    assertTrue(recorder.isRecording());
    Path recordingPath = recorder.getCurrentRecordingPath();
    assertNotNull(recordingPath);
    assertTrue(recordingPath.toString().contains(".jfr-shell/recordings"));
    assertTrue(recordingPath.toString().endsWith(".jfrs"));

    recorder.stop();

    // Clean up
    Files.deleteIfExists(recordingPath);
  }

  @Test
  void testRecordingFormat(@TempDir Path tempDir) throws IOException {
    Path recordingPath = tempDir.resolve("test.jfrs");

    CommandRecorder recorder = new CommandRecorder();
    recorder.start(recordingPath);
    recorder.recordCommand("open /path/to/file.jfr");
    recorder.recordCommand("show events/jdk.ExecutionSample");
    recorder.stop();

    String content = Files.readString(recordingPath);

    // Verify header
    assertTrue(content.contains("# JFR Shell Recording"));
    assertTrue(content.contains("# Started:"));

    // Verify commands with timestamps
    assertTrue(content.contains("open /path/to/file.jfr"));
    assertTrue(content.contains("show events/jdk.ExecutionSample"));

    // Verify timestamp format [HH:mm:ss]
    assertTrue(content.matches("(?s).*# \\[\\d{2}:\\d{2}:\\d{2}\\].*"));

    // Verify footer
    assertTrue(content.contains("# Recording stopped:"));
  }

  /** Every form both shells' 'set' accepts for the key; the value must not reach the file. */
  @Test
  void apiKeyValueIsNotRecorded(@TempDir Path tempDir) throws IOException {
    Path recordingPath = tempDir.resolve("secret.jfrs");
    List<String> typed =
        List.of(
            "set llm.api-key = sk-SECRET1",
            "set --global llm.api-key = sk-SECRET2",
            "let llm.api-key=sk-SECRET3",
            "SET   LLM.API-KEY   =   \"sk-SECRET4\"",
            "set llm.api-key = sk-SECRET5=tail");

    CommandRecorder recorder = new CommandRecorder();
    recorder.start(recordingPath);
    for (String line : typed) {
      recorder.recordCommand(line);
    }
    recorder.stop();

    String content = Files.readString(recordingPath);
    assertFalse(content.contains("SECRET"), content);
    assertTrue(content.contains("# set llm.api-key = <redacted: not recorded>"), content);
    assertTrue(content.contains("# set --global llm.api-key = <redacted: not recorded>"), content);
    assertTrue(content.contains("# let llm.api-key= <redacted: not recorded>"), content);
    assertTrue(content.contains("# SET   LLM.API-KEY   = <redacted: not recorded>"), content);
  }

  /** Other settings, variables and lines that merely mention the key are recorded as typed. */
  @Test
  void otherLinesAreRecordedAsTyped() {
    for (String line :
        List.of(
            "set llm.backend = ollama",
            "set --global llm.base-url = http://localhost:11434/v1",
            "set key = events/jdk.ThreadStart | count()",
            "set x = llm.api-key",
            "echo llm.api-key = sk-not-a-set",
            "settings llm.api-key = sk",
            "unset llm.api-key",
            "set llm.api-key")) {
      assertSame(line, CommandRecorder.redactSecrets(line), line);
    }
  }
}

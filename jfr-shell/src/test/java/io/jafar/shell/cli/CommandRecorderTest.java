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
}

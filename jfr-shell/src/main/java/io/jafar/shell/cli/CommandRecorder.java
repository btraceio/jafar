package io.jafar.shell.cli;

import io.jafar.shell.core.llm.LlmSettings;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Records interactive shell commands to a script file for later replay.
 *
 * <p>Recorded scripts are in the same format as executable scripts, with timestamp comments added
 * for context.
 *
 * <p>Example recorded script:
 *
 * <pre>
 * # JFR Shell Recording
 * # Started: 2025-12-26T14:30:00Z
 *
 * # [14:30:15]
 * open /path/to/recording.jfr
 *
 * # [14:30:20]
 * show events/jdk.ExecutionSample --limit 10
 * </pre>
 *
 * <p>A {@code set}/{@code let} of a credential setting ({@link LlmSettings#isSecret}) is written as
 * a comment without its value, so the file holds no key and replaying it does not set one.
 */
public final class CommandRecorder {
  private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
  private static final DateTimeFormatter TIMESTAMP_FORMATTER =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

  private Path recordingPath;
  private BufferedWriter writer;
  private boolean recording;
  private final Path defaultRecordingDir;

  /** Creates a command recorder with default recording directory ~/.jfr-shell/recordings. */
  public CommandRecorder() {
    this.defaultRecordingDir =
        Paths.get(System.getProperty("user.home"), ".jfr-shell", "recordings");
    this.recording = false;
  }

  /**
   * Starts recording commands to a file.
   *
   * @param path path to recording file, or null to use default location
   * @throws IOException if the file cannot be created or written
   * @throws IllegalStateException if already recording
   */
  public void start(Path path) throws IOException {
    if (recording) {
      throw new IllegalStateException("Already recording to: " + recordingPath);
    }

    if (path == null) {
      Files.createDirectories(defaultRecordingDir);
      String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
      path = defaultRecordingDir.resolve("session-" + timestamp + ".jfrs");
    }

    this.recordingPath = path;
    this.writer =
        Files.newBufferedWriter(
            path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    this.recording = true;

    // Write header
    writer.write("# JFR Shell Recording\n");
    writer.write("# Started: " + Instant.now() + "\n");
    writer.write("# Session: " + path.getFileName() + "\n\n");
    writer.flush();
  }

  /**
   * Records a command to the script file.
   *
   * @param command command to record
   * @throws IOException if writing fails
   */
  public void recordCommand(String command) throws IOException {
    if (!recording) {
      return;
    }

    // Add timestamp comment for context
    LocalTime time = LocalTime.now();
    writer.write("# [" + time.format(TIME_FORMATTER) + "]\n");
    writer.write(redactSecrets(command) + "\n");
    writer.flush();
  }

  /**
   * {@code set [--global] llm.api-key = sk-...} becomes {@code # set [--global] llm.api-key =
   * <redacted: not recorded>}; any other line is returned as is. It is commented out rather than
   * given a placeholder value: replayed, a placeholder would become the key, and the request would
   * fail as unauthorized instead of using the key the replaying user has configured.
   *
   * <p>Follows both shells' {@code set} parsing: the command word in any case, then the name is the
   * last word before the first {@code =}. Allocates only for {@code set}/{@code let} lines.
   */
  static String redactSecrets(String command) {
    int start = 0;
    while (start < command.length() && Character.isWhitespace(command.charAt(start))) {
      start++;
    }
    if (!isWord(command, start, "set") && !isWord(command, start, "let")) {
      return command;
    }
    int eq = command.indexOf('=', start);
    if (eq < 0) {
      return command;
    }
    int nameEnd = eq;
    while (nameEnd > start && Character.isWhitespace(command.charAt(nameEnd - 1))) {
      nameEnd--;
    }
    int nameStart = nameEnd;
    while (nameStart > start && !Character.isWhitespace(command.charAt(nameStart - 1))) {
      nameStart--;
    }
    if (!LlmSettings.isSecret(command.substring(nameStart, nameEnd))) {
      return command;
    }
    return "# " + command.substring(start, eq + 1) + " <redacted: not recorded>";
  }

  /** Whether {@code word} (ASCII, lower case) is at {@code at}, any case, followed by a space. */
  private static boolean isWord(String s, int at, String word) {
    int end = at + word.length();
    return s.regionMatches(true, at, word, 0, word.length())
        && end < s.length()
        && Character.isWhitespace(s.charAt(end));
  }

  /**
   * Stops recording and closes the file.
   *
   * @throws IOException if closing the file fails
   * @throws IllegalStateException if not currently recording
   */
  public void stop() throws IOException {
    if (!recording) {
      throw new IllegalStateException("Not currently recording");
    }

    writer.write("\n# Recording stopped: " + Instant.now() + "\n");
    writer.close();
    recording = false;
  }

  /**
   * Returns whether currently recording.
   *
   * @return true if recording
   */
  public boolean isRecording() {
    return recording;
  }

  /**
   * Returns the path to the current recording file.
   *
   * @return recording file path, or null if not recording
   */
  public Path getCurrentRecordingPath() {
    return recordingPath;
  }
}

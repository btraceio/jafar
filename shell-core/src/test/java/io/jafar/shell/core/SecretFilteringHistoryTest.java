package io.jafar.shell.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Through a real line reader and history file, as the shells set them up. */
class SecretFilteringHistoryTest {

  @Test
  void typedKeyLineIsNeitherKeptNorSaved(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("history");
    SecretFilteringHistory history = attach(file);

    history.add(Instant.now(), "open app.jfr");
    history.add(Instant.now(), "set --global llm.api-key = sk-TYPED");
    history.add(Instant.now(), "set llm.backend = ollama");
    history.save();

    assertEquals(List.of("open app.jfr", "set llm.backend = ollama"), lines(history));
    String saved = Files.readString(file, StandardCharsets.UTF_8);
    assertFalse(saved.contains("sk-TYPED"), saved);
    assertTrue(saved.contains("set llm.backend = ollama"), saved);
  }

  /** A key line written before this filter existed is dropped on load and removed from the file. */
  @Test
  void keyLineAlreadyInFileIsDroppedAndRemoved(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("history");
    Files.writeString(
        file,
        "1700000000000:open app.jfr\n"
            + "1700000001000:set llm.api-key = sk-OLD\n"
            + "1700000002000:echo after\n",
        StandardCharsets.UTF_8);

    SecretFilteringHistory history = attach(file);

    assertEquals(List.of("open app.jfr", "echo after"), lines(history));
    String rewritten = Files.readString(file, StandardCharsets.UTF_8);
    assertFalse(rewritten.contains("sk-OLD"), rewritten);
    assertTrue(rewritten.contains("open app.jfr") && rewritten.contains("echo after"), rewritten);

    // Appending afterwards keeps the file consistent with what is in memory
    history.add(Instant.now(), "echo next");
    history.save();
    String saved = Files.readString(file, StandardCharsets.UTF_8);
    assertEquals(3, saved.lines().count(), saved);
    assertTrue(saved.contains("echo next"), saved);
  }

  /** A file with no key line is left as it was. */
  @Test
  void cleanFileIsNotRewritten(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("history");
    String content = "1700000000000:open app.jfr\n1700000002000:echo after\n";
    Files.writeString(file, content, StandardCharsets.UTF_8);
    var before = Files.getLastModifiedTime(file);
    Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1_000));

    attach(file);

    assertEquals(content, Files.readString(file, StandardCharsets.UTF_8));
    assertEquals(1_000, Files.getLastModifiedTime(file).toMillis(), "rewritten: " + before);
  }

  private static SecretFilteringHistory attach(Path file) throws Exception {
    DumbTerminal terminal =
        new DumbTerminal(
            "test",
            "dumb",
            new ByteArrayInputStream(new byte[0]),
            new ByteArrayOutputStream(),
            StandardCharsets.UTF_8);
    SecretFilteringHistory history = new SecretFilteringHistory();
    LineReader reader =
        LineReaderBuilder.builder()
            .terminal(terminal)
            .variable(LineReader.HISTORY_FILE, file)
            .history(history)
            .build();
    history.attach(reader); // loads the file, as the first readLine would
    return history;
  }

  private static List<String> lines(History history) {
    List<String> lines = new ArrayList<>();
    for (History.Entry entry : history) {
      lines.add(entry.line());
    }
    return lines;
  }
}

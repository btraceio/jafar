package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code constants} / {@code cp} command took any word it did not know as the type name, so
 * {@code constants --limit 2} listed the entries of a type called "--limit" and printed "(no
 * rows)", with no error.
 */
class ConstantsCommandOptionsTest {

  private static final Path RECORDING =
      Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr")
          .toAbsolutePath()
          .normalize();

  private SessionManager<Session> sessions;
  private CommandDispatcherTest.BufferIO io;
  private CommandDispatcher dispatcher;

  @BeforeEach
  void open() {
    assertTrue(Files.exists(RECORDING), "Missing test recording: " + RECORDING);
    sessions =
        new SessionManager<>(
            (p, c) -> new JFRSession(p, (ParsingContext) c), ParsingContext.create());
    io = new CommandDispatcherTest.BufferIO();
    dispatcher = new CommandDispatcher(sessions, io, r -> {});
    assertTrue(dispatcher.dispatch("open " + RECORDING));
    io.clear();
  }

  @AfterEach
  void close() throws Exception {
    sessions.closeAll();
  }

  private List<String> csvLines(String line) {
    io.clear();
    assertTrue(dispatcher.dispatch(line), line);
    return io.text().lines().filter(l -> !l.isBlank()).toList();
  }

  @Test
  void limitCapsTheSummary() {
    for (String cmd : List.of("constants", "cp")) {
      List<String> lines = csvLines(cmd + " --limit 2 --format csv");
      assertEquals(
          List.of("totalSize,name", "3383,jdk.types.Symbol", "2060,jdk.types.Method"), lines, cmd);
    }
    assertEquals(
        List.of("totalSize,name", "3383,jdk.types.Symbol"),
        csvLines("constants --summary --limit 1 --format csv"));
  }

  @Test
  void limitCapsTheEntriesOfAType() {
    List<String> lines = csvLines("constants jdk.types.Symbol --limit 3 --format csv");
    assertEquals(4, lines.size(), String.join("\n", lines)); // header + 3 entries
    // after --range: entries 10..19, then at most 4 of them
    lines = csvLines("constants jdk.types.Symbol --range 10-19 --limit 4 --format csv");
    assertEquals(5, lines.size(), String.join("\n", lines));
  }

  @Test
  void anUnknownOrIncompleteOptionIsAnErrorNotATypeName() {
    assertError("constants --bogus", "Unknown option for constants: --bogus");
    assertError("constants --limit", "Missing value for --limit");
    assertError("constants jdk.types.Symbol --range", "Missing value for --range");
    assertError("constants --limit two", "Invalid --limit: two");
    assertError("constants --limit -1", "Invalid --limit: -1");
  }

  private void assertError(String line, String message) {
    io.clear();
    assertTrue(dispatcher.dispatch(line), line);
    String out = io.text();
    assertTrue(out.contains(message), line + "\n" + out);
    assertFalse(out.contains("(no rows)"), line + "\n" + out);
  }
}

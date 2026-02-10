package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class ShowConstantPoolEntriesTest {
  private static Path resource(String name) {
    return Paths.get("..", "parser", "src", "test", "resources", name).normalize().toAbsolutePath();
  }

  static class BufferIO implements CommandDispatcher.IO {
    final StringBuilder out = new StringBuilder();

    @Override
    public void println(String s) {
      out.append(s).append('\n');
    }

    @Override
    public void printf(String fmt, Object... args) {
      out.append(String.format(fmt, args));
    }

    @Override
    public void error(String s) {
      out.append(s).append('\n');
    }

    String text() {
      return out.toString();
    }
  }

  @Test
  void listsSymbolConstantPoolEntries() throws Exception {
    Path jfr = resource("test-ap.jfr");
    ParsingContext ctx = ParsingContext.create();
    SessionManager sessions = new SessionManager((path, c) -> new JFRSession(path, (ParsingContext) c), ctx);
    sessions.open(jfr, null);

    BufferIO io = new BufferIO();
    CommandDispatcher dispatcher = new CommandDispatcher(sessions, io, r -> {});

    // Sanity: summary shows type exists
    dispatcher.dispatch("show cp");
    String summary = io.text();
    assertTrue(
        summary.contains("jdk.types.Symbol"),
        "Expected 'jdk.types.Symbol' in CP summary.\n" + summary);

    // Entries for a specific CP type
    io.out.setLength(0);
    dispatcher.dispatch("show cp/jdk.types.Symbol");
    String rows = io.text();
    assertFalse(
        rows.contains("(no rows)"), "Unexpected (no rows) for cp/jdk.types.Symbol.\n" + rows);
    assertTrue(
        rows.contains("| id")
            || rows.contains("| value")
            || rows.contains("| symbol")
            || rows.contains("| string"),
        "Expected a table with entries for cp/jdk.types.Symbol.\n" + rows);
  }
}

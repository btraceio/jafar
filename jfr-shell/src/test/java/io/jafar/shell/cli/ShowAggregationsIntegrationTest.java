package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class ShowAggregationsIntegrationTest {

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

    void clear() {
      out.setLength(0);
    }
  }

  @Test
  void countsEventsFromRecording() throws Exception {
    Path jfr = resource("test-ap.jfr");
    ParsingContext ctx = ParsingContext.create();
    SessionManager sessions = new SessionManager((path, c) -> new JFRSession(path, (ParsingContext) c), ctx);
    sessions.open(jfr, null);

    BufferIO io = new BufferIO();
    CommandDispatcher dispatcher = new CommandDispatcher(sessions, io, r -> {});

    dispatcher.dispatch("show events/jdk.ExecutionSample | count()");
    String output = io.text();
    assertTrue(output.contains("count"), "Expected 'count' column in output.\n" + output);
    // parse a number line in the single-column table
    long val = -1;
    for (String line : output.split("\\R")) {
      if (line.matches("\\|\\s*\\d+\\s*\\|")) {
        String digits = line.replaceAll("[^0-9]", "");
        if (!digits.isEmpty()) {
          val = Long.parseLong(digits);
          break;
        }
      }
    }
    assertTrue(val >= 0, "Expected a numeric count in output.\n" + output);
    assertTrue(val > 0, "Expected count > 0 for ExecutionSample events");
  }

  @Test
  void countsMetadataValues() throws Exception {
    Path jfr = resource("test-ap.jfr");
    ParsingContext ctx = ParsingContext.create();
    SessionManager sessions = new SessionManager((path, c) -> new JFRSession(path, (ParsingContext) c), ctx);
    sessions.open(jfr, null);

    BufferIO io = new BufferIO();
    CommandDispatcher dispatcher = new CommandDispatcher(sessions, io, r -> {});

    dispatcher.dispatch("show metadata/jdk.types.Method/name | count()");
    String output = io.text();
    assertTrue(output.contains("count"), "Expected 'count' column in output.\n" + output);
    long val = -1;
    for (String line : output.split("\\R")) {
      if (line.matches("\\|\\s*\\d+\\s*\\|")) {
        String digits = line.replaceAll("[^0-9]", "");
        if (!digits.isEmpty()) {
          val = Long.parseLong(digits);
          break;
        }
      }
    }
    assertTrue(val >= 0, "Expected a numeric count in output.\n" + output);
    assertTrue(val > 0, "Expected count > 0 for method names in metadata");
  }
}

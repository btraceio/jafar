package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CommandDispatcherSelectTest {
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
  void showRendersTableAndRespectsLimit() throws Exception {
    ParsingContext ctx = ParsingContext.create();
    SessionManager.JFRSessionFactory factory =
        (path, c) -> {
          JFRSession s = Mockito.mock(JFRSession.class);
          when(s.getRecordingPath()).thenReturn(path);
          return s;
        };
    SessionManager sm = new SessionManager(ctx, factory);
    BufferIO io = new BufferIO();
    CommandDispatcher.JfrSelector selector =
        (session, expr) ->
            List.of(
                Map.of("bytes", 1500, "thread", Map.of("name", "main")),
                Map.of("bytes", 500, "thread", Map.of("name", "worker-1")));
    CommandDispatcher disp = new CommandDispatcher(sm, io, r -> {}, selector);

    // Need an open session
    disp.dispatch("open /tmp/one.jfr --alias one");
    io.clear();
    disp.dispatch("show events/jdk.FileRead[bytes>=1000] --limit 1");
    String out = io.text();
    assertTrue(out.contains("bytes"));
    assertTrue(out.contains("thread"));
    // only one row of data after header/separator
    long dataLines = out.lines().filter(l -> l.startsWith("| ")).count() - 1; // subtract header
    assertEquals(1, dataLines);
  }
}

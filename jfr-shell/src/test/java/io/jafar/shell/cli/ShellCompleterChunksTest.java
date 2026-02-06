package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jline.reader.Candidate;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ShellCompleterChunksTest {
  @Test
  void suggestsChunkIds() throws Exception {
    ParsingContext ctx = ParsingContext.create();
    SessionManager.SessionFactory factory =
        (path, c) -> {
          JFRSession s = Mockito.mock(JFRSession.class);
          when(s.getRecordingPath()).thenReturn(path);
          when(s.getAvailableChunkIds()).thenReturn(java.util.List.of(1, 2, 3));
          return s;
        };
    SessionManager sm = new SessionManager(factory, ctx);
    sm.open(Path.of("/tmp/example.jfr"), null);

    ShellCompleter completer = new ShellCompleter(sm, null);
    List<Candidate> cands = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl =
        new ShellCompleterTest.SimpleParsedLine("show chunks/");
    completer.complete(null, pl, cands);
    assertTrue(cands.stream().anyMatch(c -> c.value().equals("chunks/1")));
    assertTrue(cands.stream().anyMatch(c -> c.value().equals("chunks/2")));
    assertTrue(cands.stream().anyMatch(c -> c.value().equals("chunks/3")));
  }
}

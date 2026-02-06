package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jline.reader.Candidate;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ShellCompleterMetadataTest {

  @Test
  void suggestsMetadataTypes() throws Exception {
    ParsingContext ctx = ParsingContext.create();
    SessionManager.SessionFactory factory =
        (path, c) -> {
          JFRSession s = Mockito.mock(JFRSession.class);
          when(s.getRecordingPath()).thenReturn(path);
          when(s.getAllMetadataTypes())
              .thenReturn(Set.of("java.lang.Thread", "jdk.JavaMonitorEnter"));
          when(s.getAvailableEventTypes()).thenReturn(Set.of("jdk.FileRead"));
          return s;
        };
    SessionManager sm = new SessionManager(factory, ctx);
    sm.open(Path.of("/tmp/example.jfr"), null);

    ShellCompleter completer = new ShellCompleter(sm, null);
    List<Candidate> cands = new ArrayList<>();

    // simulate typing "show metadata/"
    ShellCompleterTest.SimpleParsedLine pl =
        new ShellCompleterTest.SimpleParsedLine("show metadata/");
    completer.complete(null, pl, cands);
    assertTrue(cands.stream().anyMatch(c -> c.value().equals("metadata/java.lang.Thread")));
  }
}

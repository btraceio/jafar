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

class ShellCompleterAggregationsCompletionTest {
  @Test
  void suggestsPipelineFunctions() throws Exception {
    ParsingContext ctx = ParsingContext.create();
    SessionManager.JFRSessionFactory factory =
        (path, c) -> {
          JFRSession s = Mockito.mock(JFRSession.class);
          when(s.getRecordingPath()).thenReturn(path);
          when(s.getAvailableEventTypes()).thenReturn(java.util.Set.of("jdk.FileRead"));
          return s;
        };
    SessionManager sm = new SessionManager(ctx, factory);
    sm.open(Path.of("/tmp/example.jfr"), null);

    ShellCompleter completer = new ShellCompleter(sm);
    List<Candidate> cands = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl =
        new ShellCompleterTest.SimpleParsedLine("show events/jdk.FileRead | ");
    completer.complete(null, pl, cands);
    assertTrue(cands.stream().anyMatch(c -> c.value().contains("count()")));
    assertTrue(cands.stream().anyMatch(c -> c.value().contains("stats()")));
    assertTrue(cands.stream().anyMatch(c -> c.value().contains("quantiles")));
  }

  @Test
  void suggestsQuantilesTemplates() throws Exception {
    ParsingContext ctx = ParsingContext.create();
    SessionManager.JFRSessionFactory factory =
        (path, c) -> {
          JFRSession s = Mockito.mock(JFRSession.class);
          when(s.getRecordingPath()).thenReturn(path);
          when(s.getAvailableEventTypes()).thenReturn(java.util.Set.of("jdk.FileRead"));
          return s;
        };
    SessionManager sm = new SessionManager(ctx, factory);
    sm.open(Path.of("/tmp/example.jfr"), null);

    ShellCompleter completer = new ShellCompleter(sm);
    List<Candidate> cands = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl =
        new ShellCompleterTest.SimpleParsedLine("show events/jdk.FileRead | quant");
    completer.complete(null, pl, cands);
    assertTrue(cands.stream().anyMatch(c -> c.value().startsWith("quantiles(0.5,0.9")));
  }
}

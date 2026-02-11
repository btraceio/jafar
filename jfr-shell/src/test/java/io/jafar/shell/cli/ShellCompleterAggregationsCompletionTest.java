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
          when(s.getAvailableTypes()).thenReturn(java.util.Set.of("jdk.FileRead"));
          return s;
        };
    SessionManager sm = new SessionManager(ctx, factory);
    sm.open(Path.of("/tmp/example.jfr"), null);

    ShellCompleter completer = new ShellCompleter(sm, null);
    List<Candidate> cands = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl =
        new ShellCompleterTest.SimpleParsedLine("show events/jdk.FileRead | ");
    completer.complete(null, pl, cands);
    // Check for core aggregation functions (using prefix match since templates include parameters)
    assertTrue(cands.stream().anyMatch(c -> c.value().startsWith("count(")));
    assertTrue(cands.stream().anyMatch(c -> c.value().startsWith("stats(")));
    assertTrue(cands.stream().anyMatch(c -> c.value().startsWith("quantiles(")));
  }

  @Test
  void suggestsQuantilesTemplates() throws Exception {
    ParsingContext ctx = ParsingContext.create();
    SessionManager.JFRSessionFactory factory =
        (path, c) -> {
          JFRSession s = Mockito.mock(JFRSession.class);
          when(s.getRecordingPath()).thenReturn(path);
          when(s.getAvailableTypes()).thenReturn(java.util.Set.of("jdk.FileRead"));
          return s;
        };
    SessionManager sm = new SessionManager(ctx, factory);
    sm.open(Path.of("/tmp/example.jfr"), null);

    ShellCompleter completer = new ShellCompleter(sm, null);
    List<Candidate> cands = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl =
        new ShellCompleterTest.SimpleParsedLine("show events/jdk.FileRead | quant");
    completer.complete(null, pl, cands);
    // Completion value is "quantiles(" to allow parameter completion
    // Display text shows template with example values
    var quantilesCand = cands.stream().filter(c -> c.value().equals("quantiles(")).findFirst();
    assertTrue(quantilesCand.isPresent(), "Should suggest quantiles(");
    assertTrue(
        quantilesCand.get().displ().contains("0.5"),
        "Display should show template with example values");
  }
}

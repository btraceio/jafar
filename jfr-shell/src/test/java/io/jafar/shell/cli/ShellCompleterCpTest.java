package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextAnalyzer;
import io.jafar.shell.cli.completion.CompletionContextType;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jline.reader.Candidate;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ShellCompleterCpTest {
  @Test
  void suggestsCpTypes() throws Exception {
    ParsingContext ctx = ParsingContext.create();
    SessionManager.JFRSessionFactory factory =
        (path, c) -> {
          JFRSession s = Mockito.mock(JFRSession.class);
          when(s.getRecordingPath()).thenReturn(path);
          when(s.getAvailableConstantPoolTypes())
              .thenReturn(Set.of("java.lang.Thread", "java.lang.Class"));
          return s;
        };
    SessionManager sm = new SessionManager(ctx, factory);
    sm.open(Path.of("/tmp/example.jfr"), null);

    ShellCompleter completer = new ShellCompleter(sm);
    List<Candidate> cands = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl = new ShellCompleterTest.SimpleParsedLine("show cp/");
    completer.complete(null, pl, cands);
    assertTrue(cands.stream().anyMatch(c -> c.value().equals("cp/java.lang.Thread")));
  }

  @Test
  void suggestsAllCpTypes() throws Exception {
    ParsingContext ctx = ParsingContext.create();
    Set<String> cpTypes =
        Set.of(
            "jdk.types.StackTrace",
            "jdk.types.Symbol",
            "jdk.types.Method",
            "java.lang.Class",
            "jdk.types.Package",
            "java.lang.Thread",
            "jdk.types.ClassLoader",
            "java.lang.String",
            "jdk.types.Module",
            "jdk.types.ChunkHeader");
    SessionManager.JFRSessionFactory factory =
        (path, c) -> {
          JFRSession s = Mockito.mock(JFRSession.class);
          when(s.getRecordingPath()).thenReturn(path);
          when(s.getAvailableConstantPoolTypes()).thenReturn(cpTypes);
          return s;
        };
    SessionManager sm = new SessionManager(ctx, factory);
    sm.open(Path.of("/tmp/example.jfr"), null);

    ShellCompleter completer = new ShellCompleter(sm);
    List<Candidate> cands = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl = new ShellCompleterTest.SimpleParsedLine("show cp/");
    completer.complete(null, pl, cands);

    // Verify ALL types are suggested, not just one
    assertEquals(cpTypes.size(), cands.size(), "Should suggest all CP types");
    for (String cpType : cpTypes) {
      assertTrue(
          cands.stream().anyMatch(c -> c.value().equals("cp/" + cpType)),
          "Should contain cp/" + cpType);
    }
  }

  @Test
  void suggestsAllCpTypesWithPartialPrefix() throws Exception {
    ParsingContext ctx = ParsingContext.create();
    Set<String> cpTypes =
        Set.of(
            "jdk.types.StackTrace",
            "jdk.types.Symbol",
            "jdk.types.Method",
            "java.lang.Class",
            "jdk.types.Package",
            "java.lang.Thread",
            "jdk.types.ClassLoader",
            "java.lang.String",
            "jdk.types.Module",
            "jdk.types.ChunkHeader");
    SessionManager.JFRSessionFactory factory =
        (path, c) -> {
          JFRSession s = Mockito.mock(JFRSession.class);
          when(s.getRecordingPath()).thenReturn(path);
          when(s.getAvailableConstantPoolTypes()).thenReturn(cpTypes);
          return s;
        };
    SessionManager sm = new SessionManager(ctx, factory);
    sm.open(Path.of("/tmp/example.jfr"), null);

    ShellCompleter completer = new ShellCompleter(sm);

    // Test with partial "jdk"
    List<Candidate> cands = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl = new ShellCompleterTest.SimpleParsedLine("show cp/jdk");
    completer.complete(null, pl, cands);

    // Should suggest all jdk.types.* types (7 total)
    long jdkCount = cpTypes.stream().filter(t -> t.startsWith("jdk")).count();
    assertEquals(jdkCount, cands.size(), "Should suggest all jdk.* CP types");
  }

  @Test
  void detectsCorrectContextForCp() {
    CompletionContextAnalyzer analyzer = new CompletionContextAnalyzer();

    // Test "show cp/" should be EVENT_TYPE with rootType=cp
    var pl = new ShellCompleterTest.SimpleParsedLine("show cp/");
    CompletionContext ctx = analyzer.analyze(pl);

    assertEquals(CompletionContextType.EVENT_TYPE, ctx.type(), "Context should be EVENT_TYPE");
    assertEquals("cp", ctx.rootType(), "Root type should be 'cp'");
    assertEquals("", ctx.partialInput(), "Partial input should be empty");
  }
}

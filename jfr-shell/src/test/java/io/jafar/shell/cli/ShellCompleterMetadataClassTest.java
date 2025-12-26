package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;
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

class ShellCompleterMetadataClassTest {

  @Test
  void suggestsClassSubcommandAndTypesAndFlags() throws Exception {
    ParsingContext ctx = ParsingContext.create();
    SessionManager.JFRSessionFactory factory =
        (path, c) -> {
          JFRSession s = Mockito.mock(JFRSession.class);
          when(s.getRecordingPath()).thenReturn(path);
          when(s.getAllMetadataTypes()).thenReturn(Set.of("jdk.types.Method", "java.lang.Thread"));
          return s;
        };
    SessionManager sm = new SessionManager(ctx, factory);
    sm.open(Path.of("/tmp/example.jfr"), null);

    ShellCompleter completer = new ShellCompleter(sm, null);
    List<Candidate> cands = new ArrayList<>();

    // 1) After 'metadata ' we should see 'class'
    cands.clear();
    completer.complete(null, new ShellCompleterTest.SimpleParsedLine("metadata "), cands);
    assertTrue(cands.stream().anyMatch(c -> c.value().equals("class")));

    // 2) After 'metadata class ' we should see type names from session
    cands.clear();
    completer.complete(null, new ShellCompleterTest.SimpleParsedLine("metadata class "), cands);
    assertTrue(cands.stream().anyMatch(c -> c.value().equals("jdk.types.Method")));

    // 3) After the type, we should see flags suggested (including --depth)
    cands.clear();
    completer.complete(
        null, new ShellCompleterTest.SimpleParsedLine("metadata class jdk.types.Method --"), cands);
    assertTrue(cands.stream().anyMatch(c -> c.value().equals("--tree")));
    assertTrue(cands.stream().anyMatch(c -> c.value().equals("--json")));
    assertTrue(cands.stream().anyMatch(c -> c.value().equals("--fields")));
    assertTrue(cands.stream().anyMatch(c -> c.value().equals("--annotations")));
    assertTrue(cands.stream().anyMatch(c -> c.value().equals("--depth")));
  }
}

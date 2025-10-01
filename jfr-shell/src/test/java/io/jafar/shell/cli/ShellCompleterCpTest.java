package io.jafar.shell.cli;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import org.jline.reader.Candidate;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class ShellCompleterCpTest {
    @Test
    void suggestsCpTypes() throws Exception {
        ParsingContext ctx = ParsingContext.create();
        SessionManager.JFRSessionFactory factory = (path, c) -> {
            JFRSession s = Mockito.mock(JFRSession.class);
            when(s.getRecordingPath()).thenReturn(path);
            when(s.getAvailableConstantPoolTypes()).thenReturn(Set.of("jdk.Thread", "jdk.Class"));
            return s;
        };
        SessionManager sm = new SessionManager(ctx, factory);
        sm.open(Path.of("/tmp/example.jfr"), null);

        ShellCompleter completer = new ShellCompleter(sm);
        List<Candidate> cands = new ArrayList<>();
        ShellCompleterTest.SimpleParsedLine pl = new ShellCompleterTest.SimpleParsedLine("show cp/");
        completer.complete(null, pl, cands);
        assertTrue(cands.stream().anyMatch(c -> c.value().equals("cp/jdk.Thread")));
    }
}

package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ShellCompleterTest {

  static class SimpleParsedLine implements ParsedLine {
    private final String line;
    private final List<String> words;
    private final int wordIndex;

    SimpleParsedLine(String line) {
      this.line = line;
      List<String> w = new java.util.ArrayList<>(Arrays.asList(line.stripLeading().split("\\s+")));
      if (line.endsWith(" ")) {
        w.add("");
      }
      this.words = java.util.Collections.unmodifiableList(w);
      this.wordIndex = words.size() - 1;
    }

    @Override
    public String word() {
      return words.get(wordIndex);
    }

    @Override
    public int wordCursor() {
      return word().length();
    }

    @Override
    public int wordIndex() {
      return wordIndex;
    }

    @Override
    public List<String> words() {
      return words;
    }

    @Override
    public String line() {
      return line;
    }

    @Override
    public int cursor() {
      return line.length();
    }
  }

  @Test
  void suggestsCommandsAndSessions() throws Exception {
    ParsingContext ctx = ParsingContext.create();
    SessionManager.SessionFactory factory =
        (path, c) -> {
          JFRSession s = Mockito.mock(JFRSession.class);
          when(s.getRecordingPath()).thenReturn(path);
          return s;
        };
    SessionManager sm = new SessionManager(factory, ctx);
    sm.open(Path.of("/tmp/one.jfr"), "one");

    ShellCompleter completer = new ShellCompleter(sm, null);
    List<Candidate> cands = new ArrayList<>();

    completer.complete(null, new SimpleParsedLine("o"), cands);
    assertTrue(cands.stream().anyMatch(c -> c.value().equals("open")));

    cands.clear();
    completer.complete(null, new SimpleParsedLine("use "), cands);
    assertTrue(cands.stream().anyMatch(c -> c.value().equals("one")));
    assertTrue(cands.stream().anyMatch(c -> c.value().equals("1")));

    cands.clear();
    completer.complete(null, new SimpleParsedLine("close "), cands);
    assertTrue(cands.stream().anyMatch(c -> c.value().equals("--all")));
  }
}

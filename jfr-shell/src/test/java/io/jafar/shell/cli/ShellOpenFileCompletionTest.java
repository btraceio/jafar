package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jline.reader.Candidate;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class ShellOpenFileCompletionTest {

  static class SimpleParsedLine implements ParsedLine {
    private final String line;
    private final List<String> words;
    private final int wordIndex;

    SimpleParsedLine(String line) {
      this.line = line;
      List<String> w = new java.util.ArrayList<>(Arrays.asList(line.stripLeading().split("\\s+")));
      if (line.endsWith(" ")) w.add("");
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
  void completesFilesystemPathsForOpen(@TempDir Path dir) throws Exception {
    // create some files
    Path jfr = dir.resolve("aaa.jfr");
    Path txt = dir.resolve("bbb.txt");
    Files.writeString(jfr, "x");
    Files.writeString(txt, "x");

    SessionManager sm =
        new SessionManager((p, c) -> Mockito.mock(JFRSession.class), ParsingContext.create());
    ShellCompleter completer = new ShellCompleter(sm, null);
    List<Candidate> cands = new ArrayList<>();
    LineReader reader = Mockito.mock(LineReader.class);

    String prefix = jfr.getParent().toString() + "/a"; // should match aaa.jfr
    completer.complete(reader, new SimpleParsedLine("open " + prefix), cands);
    assertTrue(
        cands.stream().map(Candidate::value).anyMatch(v -> v.contains("aaa.jfr")),
        () -> "Expected completion to contain aaa.jfr, got: " + cands);
  }
}

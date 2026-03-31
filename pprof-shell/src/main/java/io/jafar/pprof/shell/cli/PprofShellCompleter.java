package io.jafar.pprof.shell.cli;

import io.jafar.pprof.shell.PprofSession;
import io.jafar.shell.core.SessionManager;
import java.util.List;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.jline.reader.impl.completer.FileNameCompleter;

/**
 * JLine completer for pprof shell queries.
 *
 * <p>Provides tab completion for the pprof query language: roots, pipeline operators, value type
 * names from the active session, and label names.
 */
public final class PprofShellCompleter implements Completer {

  private static final List<String> ROOTS = List.of("samples");

  private static final List<String> OPERATORS =
      List.of(
          "count()",
          "top(",
          "groupBy(",
          "stats(",
          "head(",
          "tail(",
          "filter(",
          "select(",
          "sortBy(",
          "stackprofile()",
          "distinct(");

  private static final List<String> COMMANDS =
      List.of("show ", "open ", "close", "sessions", "info", "help");

  private final SessionManager<PprofSession> sessions;
  private final FileNameCompleter fileCompleter = new FileNameCompleter();

  @SuppressWarnings("unchecked")
  public PprofShellCompleter(SessionManager<?> sessions) {
    this.sessions = (SessionManager<PprofSession>) sessions;
  }

  @Override
  public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
    String word = line.word();
    String fullLine = line.line().substring(0, line.cursor());
    int wordIndex = line.wordIndex();

    // First word: complete commands
    if (wordIndex == 0) {
      for (String cmd : COMMANDS) {
        if (cmd.startsWith(word)) {
          candidates.add(new Candidate(cmd));
        }
      }
      return;
    }

    // After 'show': complete query roots
    if (wordIndex == 1) {
      String firstWord = line.words().get(0).toLowerCase();
      if ("show".equals(firstWord)) {
        for (String root : ROOTS) {
          if (root.startsWith(word)) {
            candidates.add(new Candidate(root));
          }
        }
        return;
      }
      if ("open".equals(firstWord)) {
        fileCompleter.complete(reader, line, candidates);
        return;
      }
    }

    // After '|': complete pipeline operators
    if (fullLine.contains("|")) {
      String afterPipe = fullLine.substring(fullLine.lastIndexOf('|') + 1).stripLeading();
      for (String op : OPERATORS) {
        String opName = op.replace("(", "").replace(")", "");
        if (opName.startsWith(afterPipe) || op.startsWith(afterPipe)) {
          candidates.add(new Candidate(op));
        }
      }
      // Add value type names from active session
      sessions
          .current()
          .ifPresent(
              ref -> {
                if (ref.session instanceof PprofSession ps) {
                  for (var vt : ps.getProfile().sampleTypes()) {
                    if (vt.type().startsWith(afterPipe)) {
                      candidates.add(new Candidate(vt.type()));
                    }
                  }
                }
              });
      return;
    }

    // Inside query: suggest '|' and value types
    if (fullLine.matches(".*\\bsamples\\b.*")) {
      if ("|".startsWith(word)) {
        candidates.add(new Candidate("| "));
      }
      sessions
          .current()
          .ifPresent(
              ref -> {
                if (ref.session instanceof PprofSession ps) {
                  for (var vt : ps.getProfile().sampleTypes()) {
                    if (vt.type().startsWith(word)) {
                      candidates.add(new Candidate(vt.type()));
                    }
                  }
                }
              });
    }
  }
}

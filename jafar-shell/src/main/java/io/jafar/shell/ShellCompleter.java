package io.jafar.shell;

import io.jafar.shell.core.QueryEvaluator;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.ShellModule;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

/**
 * Context-aware completer for the unified jafar-shell. Shows only relevant completions based on
 * whether a session is open and what type of session it is.
 */
public final class ShellCompleter implements Completer {

  private final SessionManager sessions;
  private final Map<String, ShellModule> moduleById;
  private final org.jline.reader.impl.completer.FileNameCompleter fileCompleter =
      new org.jline.reader.impl.completer.FileNameCompleter();

  // Commands always available
  private static final String[] BASE_COMMANDS = {
    "open", "sessions", "use", "close", "info", "modules", "help", "exit", "quit"
  };

  // Commands only available when session is open
  private static final String[] SESSION_COMMANDS = {"show"};

  public ShellCompleter(SessionManager sessions, Map<String, ShellModule> moduleById) {
    this.sessions = sessions;
    this.moduleById = moduleById;
  }

  @Override
  public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
    List<String> words = line.words();
    int wordIndex = line.wordIndex();

    // First word - complete commands
    if (wordIndex == 0) {
      completeCommands(line, candidates);
      return;
    }

    String cmd = words.get(0).toLowerCase(Locale.ROOT);

    switch (cmd) {
      case "show" -> completeShow(line, candidates);
      case "open" -> completeOpen(reader, line, candidates);
      case "use", "close" -> completeSessionRef(line, candidates);
      case "info" -> completeInfoCommand(line, candidates, wordIndex);
      default -> {
        // No completion for other commands
      }
    }
  }

  private void completeCommands(ParsedLine line, List<Candidate> candidates) {
    String partial = line.word().toLowerCase(Locale.ROOT);

    // Always suggest base commands
    for (String cmd : BASE_COMMANDS) {
      if (cmd.startsWith(partial)) {
        candidates.add(new Candidate(cmd));
      }
    }

    // Suggest session commands only if a session is open
    if (sessions.getCurrent().isPresent()) {
      for (String cmd : SESSION_COMMANDS) {
        if (cmd.startsWith(partial)) {
          candidates.add(new Candidate(cmd));
        }
      }
    }
  }

  private void completeShow(ParsedLine line, List<Candidate> candidates) {
    Optional<SessionManager.SessionRef> currentSession = sessions.getCurrent();
    if (currentSession.isEmpty()) {
      return;
    }

    SessionManager.SessionRef ref = currentSession.get();
    ShellModule module = moduleById.get(ref.session.getType());
    if (module == null) {
      return;
    }

    QueryEvaluator evaluator = module.getQueryEvaluator();
    if (evaluator == null) {
      return;
    }

    String partial = line.word();

    // Check if we're completing a root type or an operator
    String fullLine = line.line();
    int showIndex = fullLine.toLowerCase(Locale.ROOT).indexOf("show");
    if (showIndex < 0) {
      return;
    }

    String queryPart = fullLine.substring(showIndex + 4).trim();

    // If there's a pipe, complete operators
    if (queryPart.contains("|")) {
      completeOperators(evaluator, partial, candidates);
    } else {
      // Complete root types
      completeRootTypes(evaluator, partial, candidates);
    }
  }

  private void completeRootTypes(
      QueryEvaluator evaluator, String partial, List<Candidate> candidates) {
    for (String rootType : evaluator.getRootTypes()) {
      // Add trailing slash for better UX
      String completion = rootType.endsWith("/") ? rootType : rootType + "/";
      if (completion.startsWith(partial)) {
        // Use noSpace=true to allow immediate continuation
        candidates.add(
            new Candidate(
                completion, completion, null, rootType + " queries", null, null, false));
      }
    }
  }

  private void completeOperators(
      QueryEvaluator evaluator, String partial, List<Candidate> candidates) {
    // Get the part after the last pipe
    String afterPipe = partial;
    int lastPipe = partial.lastIndexOf('|');
    if (lastPipe >= 0) {
      afterPipe = partial.substring(lastPipe + 1).trim();
    }

    for (String operator : evaluator.getOperators()) {
      if (operator.startsWith(afterPipe)) {
        String help = evaluator.getOperatorHelp(operator);
        candidates.add(
            new Candidate(operator, operator, null, help != null ? help : operator, null, null, true));
      }
    }
  }

  private void completeOpen(LineReader reader, ParsedLine line, List<Candidate> candidates) {
    if (reader != null) {
      fileCompleter.complete(reader, line, candidates);
    }
  }

  private void completeSessionRef(ParsedLine line, List<Candidate> candidates) {
    String partial = line.word();
    for (SessionManager.SessionRef ref : sessions.list()) {
      String idStr = String.valueOf(ref.id);
      if (idStr.startsWith(partial)) {
        candidates.add(new Candidate(idStr, idStr, null, ref.session.getFilePath().getFileName().toString(), null, null, true));
      }
      if (ref.alias != null && ref.alias.startsWith(partial)) {
        candidates.add(new Candidate(ref.alias, ref.alias, null, ref.session.getFilePath().getFileName().toString(), null, null, true));
      }
    }
  }

  private void completeInfoCommand(ParsedLine line, List<Candidate> candidates, int wordIndex) {
    // info [sessionRef]
    if (wordIndex == 1) {
      completeSessionRef(line, candidates);
    }
  }
}

package io.jafar.shell.cli;

import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextAnalyzer;
import io.jafar.shell.cli.completion.ContextCompleter;
import io.jafar.shell.cli.completion.MetadataService;
import io.jafar.shell.cli.completion.completers.ChunkIdCompleter;
import io.jafar.shell.cli.completion.completers.CommandCompleter;
import io.jafar.shell.cli.completion.completers.EventTypeCompleter;
import io.jafar.shell.cli.completion.completers.FieldPathCompleter;
import io.jafar.shell.cli.completion.completers.FilterFieldCompleter;
import io.jafar.shell.cli.completion.completers.FilterLogicalCompleter;
import io.jafar.shell.cli.completion.completers.FilterOperatorCompleter;
import io.jafar.shell.cli.completion.completers.FunctionParamCompleter;
import io.jafar.shell.cli.completion.completers.MetadataSubpropCompleter;
import io.jafar.shell.cli.completion.completers.OptionCompleter;
import io.jafar.shell.cli.completion.completers.PipelineOperatorCompleter;
import io.jafar.shell.cli.completion.completers.RootCompleter;
import io.jafar.shell.core.SessionManager;
import java.util.List;
import java.util.Locale;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

/**
 * JLine completer for shell commands using the completion framework. Uses Strategy pattern with
 * context-specific completers for clean separation of concerns.
 */
public class ShellCompleter implements Completer {

  private final SessionManager sessions;
  private final CompletionContextAnalyzer analyzer;
  private final MetadataService metadata;
  private final List<ContextCompleter> completers;
  private final org.jline.reader.impl.completer.FileNameCompleter fileCompleter =
      new org.jline.reader.impl.completer.FileNameCompleter();

  public ShellCompleter(SessionManager sessions) {
    this.sessions = sessions;
    this.analyzer = new CompletionContextAnalyzer();
    this.metadata = new MetadataService(sessions);

    // Register completers in priority order
    this.completers =
        List.of(
            new CommandCompleter(),
            new RootCompleter(),
            new ChunkIdCompleter(),
            new MetadataSubpropCompleter(),
            new EventTypeCompleter(),
            new FieldPathCompleter(),
            new FilterFieldCompleter(),
            new FilterOperatorCompleter(),
            new FilterLogicalCompleter(),
            new PipelineOperatorCompleter(),
            new FunctionParamCompleter(),
            new OptionCompleter());
  }

  // Debug flag - set to true to see completion debug output
  private static final boolean DEBUG = Boolean.getBoolean("jfr.shell.completion.debug");

  @Override
  public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
    List<String> words = line.words();
    int wordIndex = line.wordIndex();

    if (DEBUG) {
      System.err.println("[COMPLETION DEBUG] line='" + line.line() + "' cursor=" + line.cursor());
      System.err.println("[COMPLETION DEBUG] words=" + words + " wordIndex=" + wordIndex);
      System.err.println(
          "[COMPLETION DEBUG] word()='" + line.word() + "' wordCursor=" + line.wordCursor());
    }

    // Handle empty line or first word - use framework
    if (wordIndex == 0) {
      completeWithFramework(line, candidates);
      if (DEBUG) {
        System.err.println("[COMPLETION DEBUG] Generated " + candidates.size() + " candidates");
      }
      return;
    }

    String cmd = words.get(0).toLowerCase(Locale.ROOT);

    // For show command, use the new framework
    if ("show".equals(cmd)) {
      completeWithFramework(line, candidates);
      if (DEBUG) {
        System.err.println(
            "[COMPLETION DEBUG] Generated " + candidates.size() + " candidates for 'show'");
        for (Candidate c : candidates) {
          System.err.println(
              "[COMPLETION DEBUG]   candidate: value='"
                  + c.value()
                  + "' display='"
                  + c.displ()
                  + "'");
        }
      }
      return;
    }

    // For other commands, use existing simple completion
    completeOtherCommands(reader, line, candidates, cmd, words, wordIndex);
  }

  /** Complete using the new framework. */
  private void completeWithFramework(ParsedLine line, List<Candidate> candidates) {
    CompletionContext ctx = analyzer.analyze(line);

    // Find a completer that can handle this context
    for (ContextCompleter completer : completers) {
      if (completer.canHandle(ctx)) {
        completer.complete(ctx, metadata, candidates);
        return;
      }
    }

    // No completer found - this is okay for UNKNOWN contexts
  }

  /** Complete commands other than 'show' using simple logic. */
  private void completeOtherCommands(
      LineReader reader,
      ParsedLine line,
      List<Candidate> candidates,
      String cmd,
      List<String> words,
      int wordIndex) {
    switch (cmd) {
      case "help" -> completeHelp(candidates);
      case "open" -> completeOpen(reader, line, candidates);
      case "metadata" -> completeMetadata(line, candidates, words, wordIndex);
      case "use" -> completeUse(candidates);
      case "close" -> completeClose(candidates);
      case "cp" -> completeCp(line, candidates);
      default -> {
        // Default: suggest options
        String partial = line.word();
        if (partial.startsWith("--")) {
          suggestOptions(line, candidates, new String[] {"--help", "--version"});
        }
      }
    }
  }

  private void completeHelp(List<Candidate> candidates) {
    candidates.add(new Candidate("show"));
    candidates.add(new Candidate("metadata"));
    candidates.add(new Candidate("chunks"));
    candidates.add(new Candidate("chunk"));
    candidates.add(new Candidate("cp"));
  }

  private void completeOpen(LineReader reader, ParsedLine line, List<Candidate> candidates) {
    if (reader != null) {
      fileCompleter.complete(reader, line, candidates);
    }
    suggestOptions(line, candidates, new String[] {"--alias"});
  }

  private void completeMetadata(
      ParsedLine line, List<Candidate> candidates, List<String> words, int wordIndex) {
    if (words.size() >= 2 && "class".equalsIgnoreCase(words.get(1))) {
      if (wordIndex == 2) {
        // Suggest type names after 'metadata class '
        if (sessions.getCurrent().isPresent()) {
          String cur = line.word();
          for (String t : sessions.getCurrent().get().session.getAllMetadataTypes()) {
            if (t.startsWith(cur)) {
              candidates.add(new Candidate(t));
            }
          }
        }
        return;
      }
      suggestOptions(
          line,
          candidates,
          new String[] {"--tree", "--json", "--fields", "--annotations", "--depth"});
    } else {
      suggestOptions(
          line,
          candidates,
          new String[] {
            "--search",
            "--regex",
            "--refresh",
            "--events-only",
            "--non-events-only",
            "--primitives",
            "--summary",
            "class"
          });
    }
  }

  private void completeUse(List<Candidate> candidates) {
    if (sessions.getCurrent().isPresent()) {
      // Suggest session names and numbers
      int idx = 1;
      for (var entry : sessions.list()) {
        candidates.add(new Candidate(entry.alias != null ? entry.alias : String.valueOf(idx)));
        candidates.add(new Candidate(String.valueOf(idx)));
        idx++;
      }
    }
  }

  private void completeClose(List<Candidate> candidates) {
    candidates.add(new Candidate("--all"));
    if (sessions.getCurrent().isPresent()) {
      int idx = 1;
      for (var entry : sessions.list()) {
        candidates.add(new Candidate(entry.alias != null ? entry.alias : String.valueOf(idx)));
        candidates.add(new Candidate(String.valueOf(idx)));
        idx++;
      }
    }
  }

  private void completeCp(ParsedLine line, List<Candidate> candidates) {
    // Constant pool type completion
    if (sessions.getCurrent().isPresent()) {
      String cur = line.word();
      for (String t : sessions.getCurrent().get().session.getAvailableConstantPoolTypes()) {
        if (t.startsWith(cur)) {
          candidates.add(new Candidate(t));
        }
      }
    }
    suggestOptions(line, candidates, new String[] {"--limit", "--format", "--tree", "--depth"});
  }

  /** Helper to suggest options that start with the current partial input. */
  private void suggestOptions(ParsedLine line, List<Candidate> candidates, String[] options) {
    String partial = line.word();
    for (String opt : options) {
      if (opt.startsWith(partial)) {
        candidates.add(new Candidate(opt));
      }
    }
  }
}

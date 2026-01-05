package io.jafar.shell.cli;

import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextAnalyzer;
import io.jafar.shell.cli.completion.ContextCompleter;
import io.jafar.shell.cli.completion.MetadataService;
import io.jafar.shell.cli.completion.completers.ChunkIdCompleter;
import io.jafar.shell.cli.completion.completers.CommandCompleter;
import io.jafar.shell.cli.completion.completers.DecoratorFunctionCompleter;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

  private static final Path SCRIPTS_DIR =
      Paths.get(System.getProperty("user.home"), ".jfr-shell", "scripts");

  private final SessionManager sessions;
  private final CommandDispatcher dispatcher;
  private final CompletionContextAnalyzer analyzer;
  private final MetadataService metadata;
  private final List<ContextCompleter> completers;
  private final org.jline.reader.impl.completer.FileNameCompleter fileCompleter =
      new org.jline.reader.impl.completer.FileNameCompleter();

  public ShellCompleter(SessionManager sessions, CommandDispatcher dispatcher) {
    this.sessions = sessions;
    this.dispatcher = dispatcher;
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
            new DecoratorFunctionCompleter(),
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
      System.err.println("=== COMPLETION DEBUG ===");
      System.err.println("  line():       '" + line.line() + "'");
      System.err.println("  cursor():     " + line.cursor());
      System.err.println("  word():       '" + line.word() + "'");
      System.err.println("  wordCursor(): " + line.wordCursor());
      System.err.println("  wordIndex():  " + wordIndex);
      System.err.println("  words():      " + words);
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

    if (DEBUG) {
      System.err.println("  --- Context Analysis ---");
      System.err.println("  detected:     " + ctx.type());
      System.err.println(
          "  eventType:    " + (ctx.eventType() != null ? ctx.eventType() : "(none)"));
      System.err.println("  partial:      '" + ctx.partialInput() + "'");
      System.err.println("  fieldPath:    " + ctx.fieldPath());
      System.err.println("  command:      " + (ctx.command() != null ? ctx.command() : "(none)"));
      System.err.println("========================");
    }

    // Find a completer that can handle this context
    for (ContextCompleter completer : completers) {
      if (completer.canHandle(ctx)) {
        completer.complete(ctx, metadata, candidates);
        if (DEBUG) {
          System.err.println("  Completer:    " + completer.getClass().getSimpleName());
          System.err.println("  Candidates:   " + candidates.size());
          if (!candidates.isEmpty()) {
            System.err.println(
                "  First 5:      " + candidates.stream().limit(5).map(c -> c.value()).toList());
          }
        }
        return;
      }
    }

    // No completer found - this is okay for UNKNOWN contexts
    if (DEBUG) {
      System.err.println("  No completer found for context: " + ctx.type());
    }
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
      case "script" -> completeScript(reader, line, candidates, words, wordIndex);
      case "unset", "invalidate" -> completeVariableName(line, candidates);
      case "vars" -> completeVarsCommand(line, candidates, words, wordIndex);
      case "record" -> completeRecord(reader, line, candidates, wordIndex, words);
      case "set", "let" -> completeSetCommand(line, candidates, words, wordIndex);
      case "echo" -> completeEchoCommand(line, candidates);
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

  private void completeScript(
      LineReader reader,
      ParsedLine line,
      List<Candidate> candidates,
      List<String> words,
      int wordIndex) {
    if (wordIndex == 1) {
      // After "script " - suggest subcommands
      String partial = line.word();
      for (String sub : new String[] {"list", "run"}) {
        if (sub.startsWith(partial)) {
          candidates.add(new Candidate(sub));
        }
      }
      // Also allow file path completion
      if (reader != null) {
        fileCompleter.complete(reader, line, candidates);
      }
    } else if (wordIndex == 2 && "run".equalsIgnoreCase(words.get(1))) {
      // After "script run " - list scripts from ~/.jfr-shell/scripts
      listScriptsFromDirectory(line.word(), candidates);
    } else if (wordIndex >= 2
        && !"list".equalsIgnoreCase(words.get(1))
        && !"run".equalsIgnoreCase(words.get(1))) {
      // After "script <path> " - file completion for args
      if (reader != null) {
        fileCompleter.complete(reader, line, candidates);
      }
    }
  }

  private void listScriptsFromDirectory(String partial, List<Candidate> candidates) {
    if (!Files.exists(SCRIPTS_DIR)) {
      return;
    }
    try (var stream = Files.list(SCRIPTS_DIR)) {
      stream
          .filter(p -> p.toString().endsWith(".jfrs"))
          .map(p -> p.getFileName().toString())
          .map(name -> name.substring(0, name.length() - 5)) // Remove .jfrs
          .filter(name -> name.startsWith(partial))
          .forEach(name -> candidates.add(new Candidate(name)));
    } catch (IOException ignore) {
    }
  }

  /**
   * Completes set/let commands. After "set name = ", uses JfrPath completion for the value
   * expression or suggests map literal syntax.
   */
  private void completeSetCommand(
      ParsedLine line, List<Candidate> candidates, List<String> words, int wordIndex) {
    // "set name = expr" -> words[0]=set, words[1]=name, words[2]=, words[3]=expr
    // After "=" (wordIndex >= 3), check for map literal or use JfrPath completion
    if (wordIndex >= 3 && words.size() > 2 && "=".equals(words.get(2))) {
      String currentWord = line.word();

      // If user is typing a map literal, provide hints
      if (currentWord.startsWith("{")) {
        suggestMapLiteralSyntax(currentWord, candidates);
      } else {
        // Suggest map literal start or use JfrPath completion
        if (currentWord.isEmpty() || "{".startsWith(currentWord)) {
          candidates.add(
              new Candidate(
                  "{", "{", null, "map literal - {\"key\": value, ...}", null, null, false));
        }
        completeWithFramework(line, candidates);
      }
    } else if (wordIndex == 2) {
      // Suggest "=" after variable name
      String partial = line.word();
      if ("".equals(partial) || "=".startsWith(partial)) {
        candidates.add(new Candidate("="));
      }
    }
    // wordIndex 1 is variable name - no completion needed
  }

  /**
   * Suggests map literal syntax completions based on the current partial input.
   *
   * @param partial current partial map literal
   * @param candidates list to add completions to
   */
  private void suggestMapLiteralSyntax(String partial, List<Candidate> candidates) {
    // Don't overwhelm with suggestions - just provide helpful examples
    if (partial.equals("{")) {
      // Just opened brace - suggest common patterns
      candidates.add(
          new Candidate(
              "{\"key\": \"value\"}",
              "{\"key\": \"value\"}",
              null,
              "simple map with string value",
              null,
              null,
              false));
      candidates.add(
          new Candidate(
              "{\"count\": 0}",
              "{\"count\": 0}",
              null,
              "map with numeric value",
              null,
              null,
              false));
      candidates.add(
          new Candidate(
              "{\"enabled\": true}",
              "{\"enabled\": true}",
              null,
              "map with boolean value",
              null,
              null,
              false));
      candidates.add(new Candidate("{}", "{}", null, "empty map", null, null, false));
    }
  }

  /**
   * Completes echo command with variable substitution support. Detects ${var.field} patterns and
   * suggests map keys when completing nested field access.
   */
  private void completeEchoCommand(ParsedLine line, List<Candidate> candidates) {
    String currentWord = line.word();

    // Find the last occurrence of ${ in the current word
    int varStart = currentWord.lastIndexOf("${");
    if (varStart >= 0) {
      // Extract the variable reference part
      String varRef = currentWord.substring(varStart + 2); // Skip "${

      // Check if there's a dot for field access
      int dotIndex = varRef.indexOf('.');
      if (dotIndex > 0) {
        // User is typing ${varName.field...
        String varName = varRef.substring(0, dotIndex);
        String fieldPath = varRef.substring(dotIndex + 1);

        // Try to find the variable and complete its fields
        completeMapFields(varName, fieldPath, currentWord, varStart, candidates);
      } else if (dotIndex == 0) {
        // User typed "${." - invalid, no suggestions
      } else {
        // User is typing ${varName (no dot yet)
        // Suggest variable names
        completeVariableNamesInSubstitution(varRef, currentWord, varStart, candidates);
      }
    }
  }

  /**
   * Completes map field names for a variable in substitution context.
   *
   * @param varName the variable name
   * @param fieldPath the partial field path being typed (after the last dot)
   * @param fullWord the full word being completed
   * @param varStart position where ${ starts in fullWord
   * @param candidates list to add completions to
   */
  private void completeMapFields(
      String varName, String fieldPath, String fullWord, int varStart, List<Candidate> candidates) {
    // Get the variable value from stores
    io.jafar.shell.core.VariableStore.Value value = null;

    // Check session store first
    var currentSession = sessions.getCurrent();
    if (currentSession.isPresent() && currentSession.get().variables.contains(varName)) {
      value = currentSession.get().variables.get(varName);
    }

    // Check global store if not found in session
    if (value == null && dispatcher != null && dispatcher.getGlobalStore().contains(varName)) {
      value = dispatcher.getGlobalStore().get(varName);
    }

    if (value instanceof io.jafar.shell.core.VariableStore.MapValue mapValue) {
      // Find the last dot to determine which level we're completing
      int lastDot = fieldPath.lastIndexOf('.');
      String currentLevel;
      String prefix;

      if (lastDot >= 0) {
        // Nested field access like "db.host" - get the parent map
        String parentPath = fieldPath.substring(0, lastDot);
        currentLevel = fieldPath.substring(lastDot + 1);
        prefix = fullWord.substring(0, varStart + 2) + varName + "." + parentPath + ".";

        // Navigate to the parent map
        Object parent = mapValue.getField(parentPath);
        if (parent instanceof java.util.Map<?, ?> parentMap) {
          suggestMapKeys(parentMap, currentLevel, prefix, candidates);
        }
      } else {
        // Top-level field access
        currentLevel = fieldPath;
        prefix = fullWord.substring(0, varStart + 2) + varName + ".";
        suggestMapKeys(mapValue.value(), currentLevel, prefix, candidates);
      }

      // Always suggest .size property
      if ("size".startsWith(currentLevel) && !currentLevel.equals("size")) {
        candidates.add(
            new Candidate(
                prefix + "size", prefix + "size", null, "map entry count", null, null, false));
      }
    }
  }

  /**
   * Suggests map keys that match the partial input.
   *
   * @param map the map to get keys from
   * @param partial the partial key being typed
   * @param prefix the prefix to add before each key
   * @param candidates list to add completions to
   */
  private void suggestMapKeys(
      java.util.Map<?, ?> map, String partial, String prefix, List<Candidate> candidates) {
    for (Object key : map.keySet()) {
      String keyStr = String.valueOf(key);
      if (keyStr.startsWith(partial)) {
        Object value = map.get(key);
        String description =
            value instanceof java.util.Map
                ? "nested map"
                : value != null ? value.toString() : "null";
        candidates.add(
            new Candidate(prefix + keyStr, prefix + keyStr, null, description, null, null, false));
      }
    }
  }

  /**
   * Completes variable names in substitution context (after ${).
   *
   * @param partial the partial variable name
   * @param fullWord the full word being completed
   * @param varStart position where ${ starts
   * @param candidates list to add completions to
   */
  private void completeVariableNamesInSubstitution(
      String partial, String fullWord, int varStart, List<Candidate> candidates) {
    String prefix = fullWord.substring(0, varStart + 2); // Everything up to and including "${

    // Get variable names from global store
    if (dispatcher != null) {
      for (String name : dispatcher.getGlobalStore().names()) {
        if (name.startsWith(partial)) {
          candidates.add(
              new Candidate(
                  prefix + name, prefix + name, null, "global variable", null, null, false));
        }
      }
    }

    // Also from current session store
    sessions
        .getCurrent()
        .ifPresent(
            ref -> {
              for (String name : ref.variables.names()) {
                if (name.startsWith(partial)) {
                  candidates.add(
                      new Candidate(
                          prefix + name,
                          prefix + name,
                          null,
                          "session variable",
                          null,
                          null,
                          false));
                }
              }
            });
  }

  /**
   * Completes vars command with options and variable names.
   *
   * @param line the parsed line
   * @param candidates list to add completions to
   * @param words the words in the line
   * @param wordIndex the current word index
   */
  private void completeVarsCommand(
      ParsedLine line, List<Candidate> candidates, List<String> words, int wordIndex) {
    // Check if --info flag is present
    boolean hasInfo = words.stream().anyMatch(w -> "--info".equals(w));

    if (hasInfo && wordIndex > words.indexOf("--info")) {
      // After --info, suggest variable names
      completeVariableName(line, candidates);
    } else {
      // Suggest options
      suggestOptions(line, candidates, new String[] {"--global", "--session", "--all", "--info"});
    }
  }

  private void completeVariableName(ParsedLine line, List<Candidate> candidates) {
    String partial = line.word();
    // Get variable names from global store
    if (dispatcher != null) {
      for (String name : dispatcher.getGlobalStore().names()) {
        if (name.startsWith(partial)) {
          candidates.add(new Candidate(name));
        }
      }
    }
    // Also from current session store
    sessions
        .getCurrent()
        .ifPresent(
            ref -> {
              for (String name : ref.variables.names()) {
                if (name.startsWith(partial)) {
                  candidates.add(new Candidate(name));
                }
              }
            });
  }

  private void completeRecord(
      LineReader reader,
      ParsedLine line,
      List<Candidate> candidates,
      int wordIndex,
      List<String> words) {
    if (wordIndex == 1) {
      // Complete subcommands: start, stop, status
      String partial = line.word();
      for (String sub : new String[] {"start", "stop", "status"}) {
        if (sub.startsWith(partial)) {
          candidates.add(new Candidate(sub));
        }
      }
    } else if (wordIndex == 2 && words.size() >= 2 && "start".equalsIgnoreCase(words.get(1))) {
      // After "record start " - provide filesystem completion for the path parameter
      if (reader != null) {
        fileCompleter.complete(reader, line, candidates);
      }
    }
  }
}

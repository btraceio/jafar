package io.jafar.hdump.shell.cli;

import io.jafar.hdump.shell.cli.completion.HdumpCompletionContextAnalyzer;
import io.jafar.hdump.shell.cli.completion.HdumpMetadataService;
import io.jafar.hdump.shell.cli.completion.completers.HdumpClassPatternCompleter;
import io.jafar.hdump.shell.cli.completion.completers.HdumpCommandCompleter;
import io.jafar.hdump.shell.cli.completion.completers.HdumpFilterFieldCompleter;
import io.jafar.hdump.shell.cli.completion.completers.HdumpFilterLogicalCompleter;
import io.jafar.hdump.shell.cli.completion.completers.HdumpFilterOperatorCompleter;
import io.jafar.hdump.shell.cli.completion.completers.HdumpFunctionParamCompleter;
import io.jafar.hdump.shell.cli.completion.completers.HdumpGcRootTypeCompleter;
import io.jafar.hdump.shell.cli.completion.completers.HdumpPipelineOperatorCompleter;
import io.jafar.hdump.shell.cli.completion.completers.HdumpRootCompleter;
import io.jafar.hdump.shell.cli.completion.completers.HdumpVariableReferenceCompleter;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.completion.CompletionContext;
import io.jafar.shell.core.completion.ContextCompleter;
import java.util.List;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

/**
 * JLine completer for hdump shell commands. Uses Strategy pattern with context-specific completers
 * for clean separation of concerns.
 */
public final class HdumpShellCompleter implements Completer {

  private static final boolean DEBUG = Boolean.getBoolean("hdump.shell.completion.debug");

  private final HdumpCompletionContextAnalyzer analyzer;
  private final HdumpMetadataService metadata;
  private final List<ContextCompleter<HdumpMetadataService>> completers;
  private final org.jline.reader.impl.completer.FileNameCompleter fileCompleter =
      new org.jline.reader.impl.completer.FileNameCompleter();

  public HdumpShellCompleter(SessionManager sessions) {
    this.analyzer = new HdumpCompletionContextAnalyzer();
    this.metadata = new HdumpMetadataService(sessions);

    // Register completers in priority order
    // GcRootTypeCompleter comes before ClassPatternCompleter because it
    // also handles TYPE_PATTERN but only for gcroots root type
    this.completers =
        List.of(
            new HdumpCommandCompleter(),
            new HdumpRootCompleter(),
            new HdumpGcRootTypeCompleter(),
            new HdumpClassPatternCompleter(),
            new HdumpFilterFieldCompleter(),
            new HdumpFilterOperatorCompleter(),
            new HdumpFilterLogicalCompleter(),
            new HdumpPipelineOperatorCompleter(),
            new HdumpFunctionParamCompleter(),
            new HdumpVariableReferenceCompleter());
  }

  @Override
  public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
    List<String> words = line.words();
    int wordIndex = line.wordIndex();

    if (DEBUG) {
      System.err.println("=== HDUMP COMPLETION DEBUG ===");
      System.err.println("  line():       '" + line.line() + "'");
      System.err.println("  cursor():     " + line.cursor());
      System.err.println("  word():       '" + line.word() + "'");
      System.err.println("  wordIndex():  " + wordIndex);
      System.err.println("  words():      " + words);
    }

    // Handle first word - use framework for commands
    if (wordIndex == 0) {
      completeWithFramework(line, candidates);
      return;
    }

    String cmd = words.get(0).toLowerCase();

    // For show command, use the completion framework
    if ("show".equals(cmd)) {
      completeWithFramework(line, candidates);
      if (DEBUG) {
        System.err.println(
            "[HDUMP COMPLETION] Generated " + candidates.size() + " candidates for 'show'");
      }
      return;
    }

    // For open command, use file completion
    if ("open".equals(cmd)) {
      if (reader != null) {
        fileCompleter.complete(reader, line, candidates);
      }
      return;
    }

    // Default: no special completion
    if (DEBUG) {
      System.err.println("[HDUMP COMPLETION] No special completion for command: " + cmd);
    }
  }

  /** Complete using the framework with context-specific completers. */
  private void completeWithFramework(ParsedLine line, List<Candidate> candidates) {
    CompletionContext ctx = analyzer.analyze(line);

    // Add JLine's word to context
    ctx =
        new CompletionContext(
            ctx.type(),
            ctx.command(),
            ctx.rootType(),
            ctx.typePattern(),
            ctx.fieldPath(),
            ctx.functionName(),
            ctx.parameterIndex(),
            ctx.partialInput(),
            ctx.fullLine(),
            ctx.cursor(),
            line.word(),
            ctx.extras());

    if (DEBUG) {
      System.err.println("  --- Context Analysis ---");
      System.err.println("  detected:     " + ctx.type());
      System.err.println("  rootType:     " + (ctx.rootType() != null ? ctx.rootType() : "(none)"));
      System.err.println("  partial:      '" + ctx.partialInput() + "'");
      System.err.println("  command:      " + (ctx.command() != null ? ctx.command() : "(none)"));
      System.err.println("  functionName: " + (ctx.functionName() != null ? ctx.functionName() : "(none)"));
      System.err.println("  paramIndex:   " + ctx.parameterIndex());
      System.err.println("========================");
    }

    // Find a completer that can handle this context
    for (ContextCompleter<HdumpMetadataService> completer : completers) {
      if (completer.canHandle(ctx)) {
        completer.complete(ctx, metadata, candidates);
        if (DEBUG) {
          System.err.println("  Completer:    " + completer.getClass().getSimpleName());
          System.err.println("  Candidates:   " + candidates.size());
        }
        return;
      }
    }

    if (DEBUG) {
      System.err.println("  No completer found for context: " + ctx.type());
    }
  }
}

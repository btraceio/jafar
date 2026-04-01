package io.jafar.pprof.shell.cli;

import io.jafar.pprof.shell.PprofSession;
import io.jafar.pprof.shell.cli.completion.PprofCompletionContextAnalyzer;
import io.jafar.pprof.shell.cli.completion.PprofMetadataService;
import io.jafar.pprof.shell.cli.completion.completers.PprofCommandCompleter;
import io.jafar.pprof.shell.cli.completion.completers.PprofFilterFieldCompleter;
import io.jafar.pprof.shell.cli.completion.completers.PprofFilterLogicalCompleter;
import io.jafar.pprof.shell.cli.completion.completers.PprofFilterOperatorCompleter;
import io.jafar.pprof.shell.cli.completion.completers.PprofFunctionParamCompleter;
import io.jafar.pprof.shell.cli.completion.completers.PprofPipelineOperatorCompleter;
import io.jafar.pprof.shell.cli.completion.completers.PprofRootCompleter;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.completion.CompletionContext;
import io.jafar.shell.core.completion.ContextCompleter;
import java.util.List;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.jline.reader.impl.completer.FileNameCompleter;

/**
 * JLine completer for pprof shell commands. Uses Strategy pattern with context-specific completers
 * for clean separation of concerns.
 */
public final class PprofShellCompleter implements Completer {

  private static final boolean DEBUG = Boolean.getBoolean("pprof.shell.completion.debug");

  private final PprofCompletionContextAnalyzer analyzer;
  private final PprofMetadataService metadata;
  private final List<ContextCompleter<PprofMetadataService>> completers;
  private final FileNameCompleter fileCompleter = new FileNameCompleter();

  @SuppressWarnings("unchecked")
  public PprofShellCompleter(SessionManager<?> sessions) {
    this.analyzer = new PprofCompletionContextAnalyzer();
    this.metadata = new PprofMetadataService((SessionManager<PprofSession>) sessions);

    this.completers =
        List.of(
            new PprofCommandCompleter(),
            new PprofRootCompleter(),
            new PprofFilterFieldCompleter(),
            new PprofFilterOperatorCompleter(),
            new PprofFilterLogicalCompleter(),
            new PprofPipelineOperatorCompleter(),
            new PprofFunctionParamCompleter());
  }

  @Override
  public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
    int wordIndex = line.wordIndex();

    if (DEBUG) {
      System.err.println("=== PPROF COMPLETION DEBUG ===");
      System.err.println("  line():      '" + line.line() + "'");
      System.err.println("  cursor():    " + line.cursor());
      System.err.println("  word():      '" + line.word() + "'");
      System.err.println("  wordIndex(): " + wordIndex);
      System.err.println("  words():     " + line.words());
    }

    if (wordIndex == 0) {
      completeWithFramework(line, candidates);
      return;
    }

    String cmd = line.words().get(0).toLowerCase();

    if ("open".equals(cmd)) {
      if (reader != null) {
        fileCompleter.complete(reader, line, candidates);
      }
      return;
    }

    if ("show".equals(cmd) || "samples".equals(cmd)) {
      completeWithFramework(line, candidates);
      return;
    }
  }

  private void completeWithFramework(ParsedLine line, List<Candidate> candidates) {
    CompletionContext ctx = analyzer.analyze(line);

    // Inject JLine's current word into the context
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
      System.err.println("  partial:      '" + ctx.partialInput() + "'");
      System.err.println("  command:      " + (ctx.command() != null ? ctx.command() : "(none)"));
      System.err.println(
          "  functionName: " + (ctx.functionName() != null ? ctx.functionName() : "(none)"));
      System.err.println("  paramIndex:   " + ctx.parameterIndex());
      System.err.println("========================");
    }

    for (ContextCompleter<PprofMetadataService> completer : completers) {
      if (completer.canHandle(ctx)) {
        completer.complete(ctx, metadata, candidates);
        if (DEBUG) {
          System.err.println("  Completer:  " + completer.getClass().getSimpleName());
          System.err.println("  Candidates: " + candidates.size());
        }
        return;
      }
    }

    if (DEBUG) {
      System.err.println("  No completer found for context: " + ctx.type());
    }
  }
}

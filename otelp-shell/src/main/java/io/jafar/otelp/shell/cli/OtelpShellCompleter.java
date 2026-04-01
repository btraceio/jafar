package io.jafar.otelp.shell.cli;

import io.jafar.otelp.shell.OtelpSession;
import io.jafar.otelp.shell.cli.completion.OtelpCompletionContextAnalyzer;
import io.jafar.otelp.shell.cli.completion.OtelpMetadataService;
import io.jafar.otelp.shell.cli.completion.completers.OtelpCommandCompleter;
import io.jafar.otelp.shell.cli.completion.completers.OtelpFilterFieldCompleter;
import io.jafar.otelp.shell.cli.completion.completers.OtelpFilterLogicalCompleter;
import io.jafar.otelp.shell.cli.completion.completers.OtelpFilterOperatorCompleter;
import io.jafar.otelp.shell.cli.completion.completers.OtelpFunctionParamCompleter;
import io.jafar.otelp.shell.cli.completion.completers.OtelpPipelineOperatorCompleter;
import io.jafar.otelp.shell.cli.completion.completers.OtelpRootCompleter;
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
 * JLine completer for otelp shell commands. Uses Strategy pattern with context-specific completers
 * for clean separation of concerns.
 */
public final class OtelpShellCompleter implements Completer {

  private static final boolean DEBUG = Boolean.getBoolean("otelp.shell.completion.debug");

  private final OtelpCompletionContextAnalyzer analyzer;
  private final OtelpMetadataService metadata;
  private final List<ContextCompleter<OtelpMetadataService>> completers;
  private final FileNameCompleter fileCompleter = new FileNameCompleter();

  @SuppressWarnings("unchecked")
  public OtelpShellCompleter(SessionManager<?> sessions) {
    this.analyzer = new OtelpCompletionContextAnalyzer();
    this.metadata = new OtelpMetadataService((SessionManager<OtelpSession>) sessions);

    this.completers =
        List.of(
            new OtelpCommandCompleter(),
            new OtelpRootCompleter(),
            new OtelpFilterFieldCompleter(),
            new OtelpFilterOperatorCompleter(),
            new OtelpFilterLogicalCompleter(),
            new OtelpPipelineOperatorCompleter(),
            new OtelpFunctionParamCompleter());
  }

  @Override
  public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
    int wordIndex = line.wordIndex();

    if (DEBUG) {
      System.err.println("=== OTELP COMPLETION DEBUG ===");
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

    for (ContextCompleter<OtelpMetadataService> completer : completers) {
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

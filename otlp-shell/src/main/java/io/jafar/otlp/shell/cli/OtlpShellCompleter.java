package io.jafar.otlp.shell.cli;

import io.jafar.otlp.shell.OtlpSession;
import io.jafar.otlp.shell.cli.completion.OtlpCompletionContextAnalyzer;
import io.jafar.otlp.shell.cli.completion.OtlpMetadataService;
import io.jafar.otlp.shell.cli.completion.completers.OtlpCommandCompleter;
import io.jafar.otlp.shell.cli.completion.completers.OtlpFilterFieldCompleter;
import io.jafar.otlp.shell.cli.completion.completers.OtlpFilterLogicalCompleter;
import io.jafar.otlp.shell.cli.completion.completers.OtlpFilterOperatorCompleter;
import io.jafar.otlp.shell.cli.completion.completers.OtlpFunctionParamCompleter;
import io.jafar.otlp.shell.cli.completion.completers.OtlpPipelineOperatorCompleter;
import io.jafar.otlp.shell.cli.completion.completers.OtlpRootCompleter;
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
 * JLine completer for otlp shell commands. Uses Strategy pattern with context-specific completers
 * for clean separation of concerns.
 */
public final class OtlpShellCompleter implements Completer {

  private static final boolean DEBUG = Boolean.getBoolean("otlp.shell.completion.debug");

  private final OtlpCompletionContextAnalyzer analyzer;
  private final OtlpMetadataService metadata;
  private final List<ContextCompleter<OtlpMetadataService>> completers;
  private final FileNameCompleter fileCompleter = new FileNameCompleter();

  @SuppressWarnings("unchecked")
  public OtlpShellCompleter(SessionManager<?> sessions) {
    this.analyzer = new OtlpCompletionContextAnalyzer();
    this.metadata = new OtlpMetadataService((SessionManager<OtlpSession>) sessions);

    this.completers =
        List.of(
            new OtlpCommandCompleter(),
            new OtlpRootCompleter(),
            new OtlpFilterFieldCompleter(),
            new OtlpFilterOperatorCompleter(),
            new OtlpFilterLogicalCompleter(),
            new OtlpPipelineOperatorCompleter(),
            new OtlpFunctionParamCompleter());
  }

  @Override
  public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
    int wordIndex = line.wordIndex();

    if (DEBUG) {
      System.err.println("=== OTLP COMPLETION DEBUG ===");
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

    for (ContextCompleter<OtlpMetadataService> completer : completers) {
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

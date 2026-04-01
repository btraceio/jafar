package io.jafar.otelp.shell.cli.completion.completers;

import io.jafar.otelp.shell.cli.completion.OtelpMetadataService;
import io.jafar.shell.core.completion.CompletionContext;
import io.jafar.shell.core.completion.CompletionContextType;
import io.jafar.shell.core.completion.ContextCompleter;
import java.util.List;
import org.jline.reader.Candidate;

/** Completer for top-level shell commands. */
public final class OtelpCommandCompleter implements ContextCompleter<OtelpMetadataService> {

  private static final String[] COMMANDS = {
    "show", "open", "close", "sessions", "info", "help", "exit", "quit"
  };

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.COMMAND;
  }

  @Override
  public void complete(
      CompletionContext ctx, OtelpMetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput().toLowerCase();

    for (String cmd : COMMANDS) {
      if (cmd.startsWith(partial)) {
        candidates.add(candidate(cmd, cmd, getDescription(cmd)));
      }
    }
  }

  private String getDescription(String cmd) {
    return switch (cmd) {
      case "show" -> "execute a query";
      case "open" -> "open an otlp file";
      case "close" -> "close current session";
      case "sessions" -> "list open sessions";
      case "info" -> "show session info";
      case "help" -> "show help";
      case "exit", "quit" -> "exit the shell";
      default -> null;
    };
  }
}

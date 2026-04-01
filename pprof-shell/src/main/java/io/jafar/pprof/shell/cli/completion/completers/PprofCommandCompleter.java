package io.jafar.pprof.shell.cli.completion.completers;

import io.jafar.pprof.shell.cli.completion.PprofMetadataService;
import io.jafar.shell.core.completion.CompletionContext;
import io.jafar.shell.core.completion.CompletionContextType;
import io.jafar.shell.core.completion.ContextCompleter;
import java.util.List;
import org.jline.reader.Candidate;

/** Completer for top-level shell commands. */
public final class PprofCommandCompleter implements ContextCompleter<PprofMetadataService> {

  private static final String[] COMMANDS = {
    "show", "open", "close", "sessions", "info", "help", "exit", "quit"
  };

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.COMMAND;
  }

  @Override
  public void complete(
      CompletionContext ctx, PprofMetadataService metadata, List<Candidate> candidates) {
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
      case "open" -> "open a pprof file";
      case "close" -> "close current session";
      case "sessions" -> "list open sessions";
      case "info" -> "show session info";
      case "help" -> "show help";
      case "exit", "quit" -> "exit the shell";
      default -> null;
    };
  }
}

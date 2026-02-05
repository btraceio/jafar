package io.jafar.hdump.shell.cli.completion.completers;

import io.jafar.hdump.shell.cli.completion.HdumpMetadataService;
import io.jafar.shell.core.completion.CompletionContext;
import io.jafar.shell.core.completion.CompletionContextType;
import io.jafar.shell.core.completion.ContextCompleter;
import java.util.List;
import org.jline.reader.Candidate;

/** Completer for top-level shell commands. Suggests commands like show, open, help, etc. */
public final class HdumpCommandCompleter implements ContextCompleter<HdumpMetadataService> {

  private static final String[] COMMANDS = {
    "show", "open", "close", "use", "sessions", "info", "help", "exit", "quit", "set", "let",
    "vars", "unset"
  };

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.COMMAND;
  }

  @Override
  public void complete(
      CompletionContext ctx, HdumpMetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput().toLowerCase();

    for (String cmd : COMMANDS) {
      if (cmd.startsWith(partial)) {
        String description = getCommandDescription(cmd);
        candidates.add(candidate(cmd, cmd, description));
      }
    }
  }

  private String getCommandDescription(String cmd) {
    return switch (cmd) {
      case "show" -> "execute a query";
      case "open" -> "open a heap dump file";
      case "close" -> "close current session";
      case "use" -> "switch to a session";
      case "sessions" -> "list open sessions";
      case "info" -> "show session info";
      case "help" -> "show help";
      case "exit", "quit" -> "exit the shell";
      case "set", "let" -> "set a variable";
      case "vars" -> "list variables";
      case "unset" -> "remove a variable";
      default -> null;
    };
  }
}

package io.jafar.shell.cli.completion.completers;

import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextType;
import io.jafar.shell.cli.completion.ContextCompleter;
import io.jafar.shell.cli.completion.MetadataService;
import java.util.List;
import java.util.Locale;
import org.jline.reader.Candidate;

/** Completer for shell commands (first word on the line). */
public class CommandCompleter implements ContextCompleter {

  private static final String[] COMMANDS = {
    "open",
    "sessions",
    "use",
    "close",
    "info",
    "show",
    "metadata",
    "chunks",
    "chunk",
    "cp",
    "set",
    "let",
    "vars",
    "unset",
    "echo",
    "invalidate", // Variables
    "if",
    "elif",
    "else",
    "endif", // Conditionals
    "script",
    "record", // Scripting
    "ask",
    "llm", // LLM integration
    "help",
    "exit",
    "quit"
  };

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.COMMAND;
  }

  @Override
  public void complete(
      CompletionContext ctx, MetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput().toLowerCase(Locale.ROOT);
    for (String cmd : COMMANDS) {
      if (cmd.startsWith(partial)) {
        candidates.add(new Candidate(cmd));
      }
    }
  }
}

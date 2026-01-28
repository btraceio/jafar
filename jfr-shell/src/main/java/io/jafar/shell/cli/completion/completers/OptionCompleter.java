package io.jafar.shell.cli.completion.completers;

import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextType;
import io.jafar.shell.cli.completion.ContextCompleter;
import io.jafar.shell.cli.completion.MetadataService;
import java.util.List;
import java.util.Map;
import org.jline.reader.Candidate;

/** Completer for command options (--limit, --format, etc.) and option values. */
public final class OptionCompleter implements ContextCompleter {

  // Options per command
  private static final Map<String, String[]> COMMAND_OPTIONS =
      Map.of(
          "show", new String[] {"--limit", "--format", "--tree", "--depth", "--list-match"},
          "metadata",
              new String[] {
                "--search",
                "--regex",
                "--refresh",
                "--events-only",
                "--non-events-only",
                "--primitives",
                "--summary",
                "class",
                "--tree",
                "--json",
                "--fields",
                "--annotations",
                "--depth"
              },
          "open", new String[] {"--alias"},
          "close", new String[] {"--all"},
          "cp", new String[] {"--limit", "--format", "--tree", "--depth"});

  // Values for specific options
  private static final Map<String, String[]> OPTION_VALUES =
      Map.of(
          "--list-match", new String[] {"any", "all", "none"},
          "--format", new String[] {"table", "json", "csv"});

  // Subcommands for specific commands
  private static final Map<String, String[]> COMMAND_SUBCOMMANDS =
      Map.of("set", new String[] {"output"}, "let", new String[] {"output"});

  // Values for subcommands
  private static final Map<String, String[]> SUBCOMMAND_VALUES =
      Map.of("output", new String[] {"table", "json", "csv"});

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.COMMAND_OPTION
        || ctx.type() == CompletionContextType.OPTION_VALUE;
  }

  @Override
  public void complete(
      CompletionContext ctx, MetadataService metadata, List<Candidate> candidates) {
    if (ctx.type() == CompletionContextType.OPTION_VALUE) {
      completeOptionValue(ctx, candidates);
    } else {
      // Check if we should complete subcommands for set/let commands
      String command = ctx.command();
      if (("set".equals(command) || "let".equals(command)) && isSubcommandContext(ctx)) {
        completeSubcommand(ctx, candidates);
      } else {
        completeOption(ctx, candidates);
      }
    }
  }

  private void completeOption(CompletionContext ctx, List<Candidate> candidates) {
    String partial = ctx.partialInput();
    String command = ctx.command();

    String[] options = COMMAND_OPTIONS.getOrDefault(command, new String[] {});
    for (String opt : options) {
      if (opt.startsWith(partial)) {
        candidates.add(new Candidate(opt));
      }
    }
  }

  private void completeOptionValue(CompletionContext ctx, List<Candidate> candidates) {
    String option = ctx.extras().get("option");
    String partial = ctx.partialInput();

    String[] values = OPTION_VALUES.get(option);
    if (values != null) {
      for (String value : values) {
        if (value.startsWith(partial)) {
          candidates.add(new Candidate(value));
        }
      }
    }
  }

  private boolean isSubcommandContext(CompletionContext ctx) {
    // Check if we're completing right after "set" or "let" (first token after command)
    String fullLine = ctx.fullLine();
    if (fullLine == null) return false;

    String[] tokens = fullLine.split("\\s+");
    // tokens[0] is command, tokens[1] (if exists) might be subcommand or start of "output"
    // We want to complete subcommands when:
    // 1. "set " or "let " with cursor after space
    // 2. "set o" or "let o" - partial subcommand name
    return tokens.length <= 2 || (tokens.length == 3 && "output".startsWith(tokens[1]));
  }

  private void completeSubcommand(CompletionContext ctx, List<Candidate> candidates) {
    String partial = ctx.partialInput();
    String command = ctx.command();
    String fullLine = ctx.fullLine();

    String[] subcommands = COMMAND_SUBCOMMANDS.getOrDefault(command, new String[] {});

    // Check if we're completing the subcommand name or its value
    String[] tokens = fullLine.split("\\s+");

    if (tokens.length == 2 || (tokens.length == 3 && tokens[2].isEmpty())) {
      // Complete subcommand name: "set " or "set o"
      for (String sub : subcommands) {
        if (sub.startsWith(partial)) {
          candidates.add(new Candidate(sub));
        }
      }
    } else if (tokens.length >= 3 && "output".equals(tokens[1])) {
      // Complete subcommand value: "set output " or "set output t"
      String[] values = SUBCOMMAND_VALUES.get("output");
      if (values != null) {
        for (String value : values) {
          if (value.startsWith(partial)) {
            candidates.add(new Candidate(value));
          }
        }
      }
    }
  }
}

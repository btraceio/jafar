package io.jafar.shell.cli.completion.completers;

import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextType;
import io.jafar.shell.cli.completion.ContextCompleter;
import io.jafar.shell.cli.completion.MetadataService;
import java.util.List;
import java.util.Map;
import org.jline.reader.Candidate;

/** Completer for command options (--limit, --format, etc.) and option values. */
public class OptionCompleter implements ContextCompleter {

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
          "--format", new String[] {"table", "json"});

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
      completeOption(ctx, candidates);
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
}

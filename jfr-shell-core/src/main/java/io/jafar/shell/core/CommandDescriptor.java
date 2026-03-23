package io.jafar.shell.core;

/**
 * Describes how a TUI adapter command should be routed and rendered. Adapters return a {@link
 * CommandDescriptor} from {@link TuiAdapter#describeCommand} to declare ownership of a command and
 * specify the output mode the TUI should use for its results.
 */
public final class CommandDescriptor {

  /** Output rendering mode for a command's results. */
  public enum OutputMode {
    /** Sortable, scrollable table — the default for query results. */
    TABULAR,
    /**
     * Two-pane master/detail split: table on top, detail below. The detail pane opens automatically
     * after the command completes.
     */
    MASTER_DETAIL,
    /** Plain line-by-line text output (reports, help, informational commands). */
    TEXT,
  }

  public static final CommandDescriptor TABULAR = new CommandDescriptor(OutputMode.TABULAR);
  public static final CommandDescriptor MASTER_DETAIL =
      new CommandDescriptor(OutputMode.MASTER_DETAIL);
  public static final CommandDescriptor TEXT = new CommandDescriptor(OutputMode.TEXT);

  private final OutputMode outputMode;

  private CommandDescriptor(OutputMode outputMode) {
    this.outputMode = outputMode;
  }

  public OutputMode outputMode() {
    return outputMode;
  }
}

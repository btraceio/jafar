package io.jafar.shell.core;

import java.util.ArrayList;
import java.util.List;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;

/** Interactive menu component for multi-select, yes/no, and integer prompts using JLine. */
public final class InteractiveMenu {

  /** A menu item with an id, label, and description. */
  public record MenuItem(String id, String label, String description) {}

  private final Terminal terminal;
  private final LineReader reader;

  public InteractiveMenu(Terminal terminal, LineReader reader) {
    this.terminal = terminal;
    this.reader = reader;
  }

  /**
   * Shows a multi-select menu and returns the IDs of selected items.
   *
   * @param prompt the prompt message
   * @param items the available items
   * @return list of selected item IDs
   */
  public List<String> showMultiSelect(String prompt, List<MenuItem> items) {
    terminal.writer().println(prompt);
    terminal.writer().println();

    boolean[] selected = new boolean[items.size()];
    // Default: select all
    for (int i = 0; i < selected.length; i++) {
      selected[i] = true;
    }

    for (int i = 0; i < items.size(); i++) {
      MenuItem item = items.get(i);
      terminal
          .writer()
          .printf(
              "  [%s] %d. %s - %s%n",
              selected[i] ? "x" : " ", i + 1, item.label(), item.description());
    }
    terminal.writer().println();

    String input =
        reader.readLine("Enter numbers to toggle (comma-separated), or Enter to confirm: ");
    if (input != null && !input.isBlank()) {
      for (String part : input.split(",")) {
        part = part.trim();
        try {
          int idx = Integer.parseInt(part) - 1;
          if (idx >= 0 && idx < selected.length) {
            selected[idx] = !selected[idx];
          }
        } catch (NumberFormatException ignore) {
          // skip invalid
        }
      }
    }

    List<String> result = new ArrayList<>();
    for (int i = 0; i < selected.length; i++) {
      if (selected[i]) {
        result.add(items.get(i).id());
      }
    }
    return result;
  }

  /**
   * Prompts for a yes/no answer.
   *
   * @param prompt the prompt message
   * @param defaultValue the default if the user just presses Enter
   * @return true for yes, false for no
   */
  public boolean promptYesNo(String prompt, boolean defaultValue) {
    String suffix = defaultValue ? " [Y/n]: " : " [y/N]: ";
    String input = reader.readLine(prompt + suffix);
    if (input == null || input.isBlank()) {
      return defaultValue;
    }
    return input.trim().toLowerCase().startsWith("y");
  }

  /**
   * Prompts for an integer value.
   *
   * @param prompt the prompt message
   * @param defaultValue the default value
   * @return the entered value, or default if blank
   */
  public int promptInt(String prompt, int defaultValue) {
    String input = reader.readLine(prompt + " [" + defaultValue + "]: ");
    if (input == null || input.isBlank()) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(input.trim());
    } catch (NumberFormatException e) {
      terminal.writer().println("Invalid number, using default: " + defaultValue);
      return defaultValue;
    }
  }
}

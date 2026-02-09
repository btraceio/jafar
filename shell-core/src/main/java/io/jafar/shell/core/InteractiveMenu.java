package io.jafar.shell.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.jline.reader.LineReader;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;

/**
 * Interactive menu component with arrow-key navigation.
 *
 * <p>Provides multi-select menus, yes/no prompts, and integer input with defaults.
 */
public final class InteractiveMenu {
  private final Terminal terminal;
  private final LineReader lineReader;

  public InteractiveMenu(Terminal terminal, LineReader lineReader) {
    this.terminal = terminal;
    this.lineReader = lineReader;
  }

  /** Menu item for multi-select menus. */
  public static class MenuItem {
    final String id;
    final String label;
    final String description;
    boolean selected;

    public MenuItem(String id, String label, String description) {
      this.id = id;
      this.label = label;
      this.description = description;
      this.selected = false;
    }
  }

  /**
   * Display multi-select menu with arrow key navigation.
   *
   * @param title Menu title/instructions
   * @param items List of menu items
   * @return List of selected item IDs
   */
  public List<String> showMultiSelect(String title, List<MenuItem> items) throws IOException {
    if (items.isEmpty()) {
      return List.of();
    }

    terminal.writer().println(title);
    terminal.writer().println();
    terminal.writer().flush();

    int currentIndex = 0;

    // Initial render
    renderMenu(items, currentIndex);

    // Enter raw mode for character-by-character input
    Attributes original = terminal.enterRawMode();
    try {
      NonBlockingReader reader = terminal.reader();

      while (true) {
        int c = reader.read(1000); // 1 second timeout

        if (c == -2) {
          // Timeout, continue
          continue;
        }

        if (c == 27) { // ESC - start of escape sequence
          int next = reader.read(100);
          if (next == '[') {
            int arrow = reader.read(100);
            switch (arrow) {
              case 'A': // Up arrow
                currentIndex = Math.max(0, currentIndex - 1);
                renderMenu(items, currentIndex);
                break;
              case 'B': // Down arrow
                currentIndex = Math.min(items.size() - 1, currentIndex + 1);
                renderMenu(items, currentIndex);
                break;
            }
          }
        } else if (c == ' ') { // Space toggles selection
          items.get(currentIndex).selected = !items.get(currentIndex).selected;
          renderMenu(items, currentIndex);
        } else if (c == '\r' || c == '\n') { // Enter confirms
          break;
        } else if (c == 3) { // Ctrl+C
          terminal.writer().println();
          terminal.writer().println("Cancelled.");
          terminal.writer().flush();
          return List.of();
        }
      }
    } finally {
      terminal.setAttributes(original);
    }

    // Clear menu and move past it
    terminal.writer().println();
    terminal.writer().flush();

    // Collect selected IDs
    List<String> selectedIds = new ArrayList<>();
    for (MenuItem item : items) {
      if (item.selected) {
        selectedIds.add(item.id);
      }
    }

    return selectedIds;
  }

  /**
   * Render the menu at the current cursor position. Uses ANSI codes to overwrite previous
   * rendering.
   */
  private void renderMenu(List<MenuItem> items, int currentIndex) {
    // Move cursor up and clear previous menu
    // Count: blank line + N items + blank line + status line + instruction line = N + 4
    int linesToClear = items.size() + 4;
    for (int i = 0; i < linesToClear; i++) {
      terminal.writer().print("\033[F"); // Move cursor up
      terminal.writer().print("\033[2K"); // Clear entire line
    }

    // Redraw menu
    terminal.writer().println();
    for (int i = 0; i < items.size(); i++) {
      MenuItem item = items.get(i);

      // Highlight current item with '>'
      String prefix = (i == currentIndex) ? "> " : "  ";

      // Show checkbox
      String checkbox = item.selected ? "[X]" : "[ ]";

      // Format: "  [X] detector-name         - Description"
      terminal.writer()
          .printf("%s%s %-25s - %s%n", prefix, checkbox, item.label, item.description);
    }

    // Show selection count and instructions
    long selectedCount = items.stream().filter(i -> i.selected).count();
    terminal.writer().println();
    terminal.writer().printf("Selected: %d detector(s)%n", selectedCount);
    terminal.writer().println("Press ENTER to continue, or Ctrl+C to cancel");
    terminal.writer().flush();
  }

  /**
   * Display yes/no prompt with default.
   *
   * @param question Question to ask
   * @param defaultValue Default value if user presses Enter
   * @return true if yes, false if no
   */
  public boolean promptYesNo(String question, boolean defaultValue) {
    String prompt = String.format("%s (%s): ", question, defaultValue ? "Y/n" : "y/N");

    String response = lineReader.readLine(prompt).trim().toLowerCase();

    if (response.isEmpty()) {
      return defaultValue;
    }

    return response.startsWith("y");
  }

  /**
   * Prompt for integer value with default.
   *
   * @param question Question to ask
   * @param defaultValue Default value if user presses Enter
   * @return Integer value entered or default
   */
  public int promptInt(String question, int defaultValue) {
    String prompt = String.format("%s [%d]: ", question, defaultValue);

    String response = lineReader.readLine(prompt).trim();

    if (response.isEmpty()) {
      return defaultValue;
    }

    try {
      return Integer.parseInt(response);
    } catch (NumberFormatException e) {
      terminal.writer().println("Invalid number, using default: " + defaultValue);
      terminal.writer().flush();
      return defaultValue;
    }
  }
}

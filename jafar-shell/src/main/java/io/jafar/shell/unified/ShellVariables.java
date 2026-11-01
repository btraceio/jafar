package io.jafar.shell.unified;

import io.jafar.shell.cli.VariableSubstitutor;
import io.jafar.shell.core.VariableStore;
import io.jafar.shell.core.VariableStore.ScalarValue;
import io.jafar.shell.core.VariableStore.Value;
import io.jafar.shell.core.llm.LlmSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@code set} / {@code vars} / {@code unset} / {@code echo} commands of the unified shell.
 *
 * <p>This is the scalar subset of what {@code jfr-shell}'s {@code CommandDispatcher} offers: a value
 * is a string, a number, a boolean, or a copy of another variable. Map literals, {@code merge()},
 * {@code if}, {@code script} and query-valued (lazy) variables are not implemented here yet. None
 * of that is inherently JFR-specific; {@code jfr-shell} happens to implement lazy variables against
 * {@code JFRSession}. They should become shell-level features resolved through each module's
 * {@code QueryEvaluator}.
 *
 * <p>Every method returns the lines to print; a failure throws {@link IllegalArgumentException}
 * carrying the message to show, so the caller cannot print a confirmation for a rejected command.
 */
final class ShellVariables {
  private static final String SET_USAGE = "Usage: set <name> = <value>";
  private static final String NAME = "[a-zA-Z_][a-zA-Z0-9_]*";

  private final VariableStore store;

  ShellVariables(VariableStore store) {
    this.store = store;
  }

  /** Handles the text after {@code set}/{@code let}, e.g. {@code llm.backend = ollama}. */
  List<String> set(String rest) {
    int eq = rest.indexOf('=');
    if (eq < 0) {
      throw new IllegalArgumentException(SET_USAGE);
    }
    String name = rest.substring(0, eq).trim();
    String expr = rest.substring(eq + 1).trim();
    if (name.isEmpty()) {
      throw new IllegalArgumentException("Variable name cannot be empty");
    }

    boolean setting = LlmSettings.isSetting(name);
    if (setting) {
      name = name.toLowerCase(Locale.ROOT);
    } else if (LlmSettings.looksLikeSetting(name)) {
      throw new IllegalArgumentException(
          "Unknown setting: " + name + "\nSettings are: " + String.join(", ", LlmSettings.names()));
    } else if (!name.matches(NAME)) {
      throw new IllegalArgumentException(
          "Invalid variable name: "
              + name
              + "\nNames may contain letters, digits and underscores, and cannot start with a"
              + " digit.");
    }

    String text = substitute(expr);
    boolean quoted = isQuoted(text);
    if (quoted) {
      text = text.substring(1, text.length() - 1);
    }

    Value value;
    if (setting) {
      // A setting's value is text. Coercing '20' to a number would store 20.0 and the config
      // reader would then fall back to its default without saying so.
      value = new ScalarValue(text);
    } else if (quoted) {
      value = new ScalarValue(text);
    } else if (text.matches("-?\\d+")) {
      value = new ScalarValue(Long.parseLong(text));
    } else if (text.matches("-?\\d+\\.\\d+")) {
      value = new ScalarValue(Double.parseDouble(text));
    } else if (text.equals("true") || text.equals("false")) {
      value = new ScalarValue(Boolean.parseBoolean(text));
    } else {
      String source = text.startsWith("$") ? text.substring(1) : text;
      Value existing = source.matches(NAME) ? store.get(source) : null;
      if (existing == null) {
        throw new IllegalArgumentException(
            text.startsWith("$") || text.matches(NAME)
                ? "Variable not found: " + source
                : "Unsupported value: "
                    + text
                    + "\nQuote strings (set name = \"text\"); query-valued variables are not"
                    + " supported in this shell.");
      }
      value = existing;
    }
    store.set(name, value);
    return List.of("Set " + name + " = " + value.describe());
  }

  /** {@code vars}: every variable, one per line. */
  List<String> vars() {
    if (store.isEmpty()) {
      return List.of("No variables defined");
    }
    List<String> lines = new ArrayList<>();
    lines.add("Variables:");
    store.names().stream()
        .sorted()
        .forEach(n -> lines.add("  " + n + " = " + store.get(n).describe()));
    return lines;
  }

  /** {@code unset <name>}. */
  List<String> unset(String name) {
    String key = LlmSettings.isSetting(name) ? name.toLowerCase(Locale.ROOT) : name;
    if (key.isEmpty()) {
      throw new IllegalArgumentException("Usage: unset <name>");
    }
    if (!store.remove(key)) {
      throw new IllegalArgumentException("Variable not found: " + key);
    }
    return List.of("Removed " + key);
  }

  /** {@code echo <text>} with {@code ${var}} substitution. */
  List<String> echo(String text) {
    return List.of(substitute(text));
  }

  private String substitute(String text) {
    if (!VariableSubstitutor.hasVariables(text)) {
      return text;
    }
    try {
      return new VariableSubstitutor(null, store).substitute(text);
    } catch (Exception e) {
      throw new IllegalArgumentException("Variable substitution failed: " + e.getMessage());
    }
  }

  private static boolean isQuoted(String s) {
    return s.length() >= 2
        && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")));
  }
}

package io.jafar.shell.core.llm;

import io.jafar.shell.core.VariableStore;

/**
 * Recognises a command line that assigns a credential setting ({@link LlmSettings#isSecret}), so
 * the places that keep or show typed lines — command recordings, history files, the TUI's echo —
 * can leave its value out; and shows such a setting's stored value masked.
 *
 * <p>Follows both shells' {@code set} parsing: {@code set} or {@code let} in any case, optionally
 * {@code --global}, then the name is the last word before the first {@code =}. A line that is not a
 * {@code set}/{@code let} is rejected without allocating.
 */
public final class SecretCommands {
  private SecretCommands() {}

  /** Whether {@code line} sets a credential, e.g. {@code set --global llm.api-key = sk-...}. */
  public static boolean setsSecret(String line) {
    return secretAssignment(line) >= 0;
  }

  /**
   * {@code set [--global] llm.api-key = sk-...} as {@code # set [--global] llm.api-key = <redacted:
   * not recorded>}; any other line is returned as is (the same instance).
   *
   * <p>A comment rather than a placeholder value: replayed, a placeholder would become the key and
   * the request would fail as unauthorized, instead of using the key configured where it runs.
   */
  public static String redact(String line) {
    int eq = secretAssignment(line);
    if (eq < 0) {
      return line;
    }
    return "# " + line.substring(firstNonSpace(line), eq + 1) + " <redacted: not recorded>";
  }

  /**
   * {@code set llm.api-key = sk-...} as {@code set llm.api-key = <hidden>}, for echoing a typed
   * line back on screen; any other line is returned as is (the same instance).
   */
  public static String hideValue(String line) {
    int eq = secretAssignment(line);
    return eq < 0 ? line : line.substring(firstNonSpace(line), eq + 1) + " " + HIDDEN;
  }

  /**
   * How {@code set}'s confirmation and {@code vars} show a variable: {@link
   * VariableStore.Value#describe()}, except a credential setting, whose value is masked (see {@link
   * #mask}).
   */
  public static String describe(String name, VariableStore.Value value) {
    if (!LlmSettings.isSecret(name)) {
      return value.describe();
    }
    return value instanceof VariableStore.ScalarValue scalar
        ? mask(String.valueOf(scalar.value()))
        : HIDDEN;
  }

  /**
   * {@code <hidden, ends in "REAL">} for a value long enough that its last four characters say
   * which key it is without giving it away (as provider dashboards show keys), {@code <hidden>} for
   * a shorter one, and {@code ""} for an empty one — nothing is set, so nothing is hidden.
   */
  public static String mask(String value) {
    if (value == null || value.isEmpty()) {
      return "\"\"";
    }
    if (value.length() < MIN_LENGTH_TO_SHOW_TAIL) {
      return HIDDEN;
    }
    return "<hidden, ends in \"" + value.substring(value.length() - 4) + "\">";
  }

  private static final String HIDDEN = "<hidden>";
  // Below this, four characters would be a large part of the secret.
  private static final int MIN_LENGTH_TO_SHOW_TAIL = 16;

  /** The index of the {@code =} of a credential assignment, or -1 if the line is not one. */
  private static int secretAssignment(String line) {
    if (line == null) {
      return -1;
    }
    int start = firstNonSpace(line);
    if (!isWord(line, start, "set") && !isWord(line, start, "let")) {
      return -1;
    }
    int eq = line.indexOf('=', start);
    if (eq < 0) {
      return -1;
    }
    int nameEnd = eq;
    while (nameEnd > start && Character.isWhitespace(line.charAt(nameEnd - 1))) {
      nameEnd--;
    }
    int nameStart = nameEnd;
    while (nameStart > start && !Character.isWhitespace(line.charAt(nameStart - 1))) {
      nameStart--;
    }
    return LlmSettings.isSecret(line.substring(nameStart, nameEnd)) ? eq : -1;
  }

  private static int firstNonSpace(String s) {
    int i = 0;
    while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
      i++;
    }
    return i;
  }

  /** Whether {@code word} (ASCII, lower case) is at {@code at}, any case, followed by a space. */
  private static boolean isWord(String s, int at, String word) {
    int end = at + word.length();
    return s.regionMatches(true, at, word, 0, word.length())
        && end < s.length()
        && Character.isWhitespace(s.charAt(end));
  }
}

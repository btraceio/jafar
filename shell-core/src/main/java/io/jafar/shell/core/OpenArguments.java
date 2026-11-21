package io.jafar.shell.core;

/**
 * The argument of the {@code open} command: a path, optionally quoted, then {@code --alias NAME}.
 * Both shells read it here, so a script that one shell writes opens in the other.
 *
 * <p>Command lines are split at whitespace, so a path with a space could not be opened (#233). Here
 * the path is everything after {@code open} up to a {@code --alias} word, or the text between a
 * leading pair of matching quotes ({@code "..."} or {@code '...'}) when only {@code --alias NAME}
 * or nothing follows the closing quote. Otherwise a leading quote is part of the path, so a file
 * whose name starts with one still opens. There is no escaping; {@link #quote} picks a form that
 * reads back.
 *
 * @param path the path as given, quotes removed; {@code ~} is not expanded
 * @param alias the alias, or {@code null}
 * @param quoted whether the path was given in quotes
 */
public record OpenArguments(String path, String alias, boolean quoted) {

  private static final String ALIAS = "--alias";
  private static final String USAGE = "Usage: open <path> [--alias NAME]";

  /**
   * Reads the text after {@code open}.
   *
   * @param rest the command line after the command word; may be blank
   * @return the path and alias
   * @throws IllegalArgumentException if no path is given, or {@code --alias} has no name or more
   *     than one word after it
   */
  public static OpenArguments parse(String rest) {
    String text = rest == null ? "" : rest.strip();
    if (text.isEmpty()) {
      throw new IllegalArgumentException(USAGE);
    }
    char first = text.charAt(0);
    if (first == '"' || first == '\'') {
      int close = text.indexOf(first, 1);
      if (close > 0) {
        String tail = text.substring(close + 1).strip();
        if (tail.isEmpty() || isAliasAt(tail, 0)) {
          String path = text.substring(1, close);
          if (path.isEmpty()) {
            throw new IllegalArgumentException(USAGE);
          }
          return new OpenArguments(path, alias(tail), true);
        }
      }
      // Not a quoted form ('odd.jfr, or "a" b): the quote is part of a plain path
    }
    int alias = aliasIndex(text);
    String path = (alias < 0 ? text : text.substring(0, alias)).strip();
    if (path.isEmpty()) {
      throw new IllegalArgumentException(USAGE);
    }
    return new OpenArguments(path, alias(alias < 0 ? "" : text.substring(alias)), false);
  }

  /**
   * The path as an {@code open} argument that {@link #parse} reads back as exactly this path: in
   * double or single quotes when it contains whitespace, else as is, falling back to whichever of
   * the three forms reads back.
   *
   * @throws IllegalArgumentException if no form reads back, which takes whitespace together with
   *     both quote characters and a leading or trailing space or a {@code --alias} word
   */
  public static String quote(String path) {
    String[] forms =
        path.chars().anyMatch(Character::isWhitespace)
            ? new String[] {'"' + path + '"', '\'' + path + '\'', path}
            : new String[] {path, '"' + path + '"', '\'' + path + '\''};
    for (String form : forms) {
      if (readsBackAs(form, path)) {
        return form;
      }
    }
    throw new IllegalArgumentException("open cannot express this path: " + path);
  }

  private static boolean readsBackAs(String form, String path) {
    try {
      OpenArguments read = parse(form);
      return read.path().equals(path) && read.alias() == null;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** The alias in {@code tail}, which is empty or starts with {@code --alias}. */
  private static String alias(String tail) {
    if (tail.isEmpty()) {
      return null;
    }
    String[] words = tail.substring(ALIAS.length()).strip().split("\\s+");
    if (words.length != 1 || words[0].isEmpty()) {
      throw new IllegalArgumentException("open: --alias takes one name: " + tail);
    }
    return words[0];
  }

  /** Where a whitespace-separated {@code --alias} word starts, or {@code -1}. */
  private static int aliasIndex(String text) {
    for (int i = text.indexOf(ALIAS); i >= 0; i = text.indexOf(ALIAS, i + 1)) {
      if (i > 0 && Character.isWhitespace(text.charAt(i - 1)) && isAliasAt(text, i)) {
        return i;
      }
    }
    return -1;
  }

  private static boolean isAliasAt(String text, int i) {
    int end = i + ALIAS.length();
    return text.startsWith(ALIAS, i)
        && (end == text.length() || Character.isWhitespace(text.charAt(end)));
  }
}

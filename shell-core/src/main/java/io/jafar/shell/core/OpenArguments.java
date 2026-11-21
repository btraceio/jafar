package io.jafar.shell.core;

/**
 * The argument of the {@code open} command: a path, optionally quoted, then {@code --alias NAME}.
 * Both shells read it here, so a script that one shell writes opens in the other.
 *
 * <p>Command lines are split at whitespace, so a path with a space could not be opened (#233). Here
 * the path is everything after {@code open} up to {@code --alias}, or the text between a leading
 * pair of matching quotes ({@code "..."} or {@code '...'}) when the path itself contains {@code
 * --alias}. There is no escaping inside quotes.
 *
 * @param path the path as given, quotes removed; {@code ~} is not expanded
 * @param alias the alias, or {@code null}
 */
public record OpenArguments(String path, String alias) {

  private static final String ALIAS = "--alias";

  /**
   * Reads the text after {@code open}.
   *
   * @param rest the command line after the command word; may be blank
   * @return the path and alias
   * @throws IllegalArgumentException if no path is given, a quote is not closed, or {@code --alias}
   *     has no name or is followed by anything else
   */
  public static OpenArguments parse(String rest) {
    String text = rest == null ? "" : rest.strip();
    String path;
    String tail;
    if (!text.isEmpty() && (text.charAt(0) == '"' || text.charAt(0) == '\'')) {
      char quote = text.charAt(0);
      int close = text.indexOf(quote, 1);
      if (close < 0) {
        throw new IllegalArgumentException("open: unclosed " + quote + " in " + text);
      }
      path = text.substring(1, close);
      tail = text.substring(close + 1).strip();
      if (!tail.isEmpty() && !isAliasAt(tail, 0)) {
        throw new IllegalArgumentException("open: unexpected text after the path: " + tail);
      }
    } else {
      int alias = aliasIndex(text);
      path = (alias < 0 ? text : text.substring(0, alias)).strip();
      tail = alias < 0 ? "" : text.substring(alias);
    }
    if (path.isEmpty()) {
      throw new IllegalArgumentException("Usage: open <path> [--alias NAME]");
    }
    String alias = null;
    if (!tail.isEmpty()) {
      String[] words = tail.substring(ALIAS.length()).strip().split("\\s+");
      if (words.length != 1 || words[0].isEmpty()) {
        throw new IllegalArgumentException("open: --alias takes one name: " + tail);
      }
      alias = words[0];
    }
    return new OpenArguments(path, alias);
  }

  /**
   * The path as an {@code open} argument that {@link #parse} reads back: as is when it has no
   * whitespace and does not start with a quote, else in double quotes, or single quotes when it
   * contains a double quote.
   */
  public static String quote(String path) {
    boolean plain =
        !path.isEmpty()
            && path.chars().noneMatch(Character::isWhitespace)
            && path.charAt(0) != '"'
            && path.charAt(0) != '\'';
    if (plain) {
      return path;
    }
    char quote = path.indexOf('"') < 0 ? '"' : '\'';
    return quote + path + quote;
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

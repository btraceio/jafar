package io.jafar.shell.core.scripting;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses a map literal in JSON-like syntax: {@code {"key": value, "nested": {...}}}. Supports
 * strings, numbers (integers as {@code Long}, decimals as {@code Double}), booleans, {@code null}
 * and nested maps. Insertion order is kept.
 *
 * <p>Shared by {@code jfr-shell}'s and {@code jafar-shell}'s {@code set name = {...}}.
 */
public final class MapLiteralParser {
  private final String input;
  private int pos = 0;

  private MapLiteralParser(String input) {
    this.input = input.trim();
  }

  /**
   * Parses a map literal.
   *
   * @param input map literal string starting with { and ending with }
   * @return parsed map
   * @throws IllegalArgumentException if syntax is invalid
   */
  public static Map<String, Object> parse(String input) {
    return new MapLiteralParser(input).parseMap();
  }

  private Map<String, Object> parseMap() {
    skipWs();
    if (peek() != '{') {
      throw error("Expected '{'");
    }
    consume(); // {

    Map<String, Object> map = new LinkedHashMap<>();
    skipWs();

    if (peek() == '}') {
      consume();
      return map;
    }

    while (true) {
      skipWs();

      // Parse key (must be string)
      if (peek() != '"') {
        throw error("Expected string key");
      }
      String key = parseString();

      skipWs();
      if (peek() != ':') {
        throw error("Expected ':' after key");
      }
      consume();

      skipWs();
      Object value = parseValue();
      map.put(key, value);

      skipWs();
      char next = peek();
      if (next == '}') {
        consume();
        break;
      } else if (next == ',') {
        consume();
      } else {
        throw error("Expected ',' or '}'");
      }
    }

    return map;
  }

  private Object parseValue() {
    skipWs();
    char ch = peek();

    if (ch == '"') {
      return parseString();
    } else if (ch == '{') {
      return parseMap();
    } else if (ch == 't' || ch == 'f') {
      return parseBoolean();
    } else if (ch == 'n') {
      return parseNull();
    } else if (ch == '-' || Character.isDigit(ch)) {
      return parseNumber();
    } else {
      throw error("Unexpected character: " + ch);
    }
  }

  private String parseString() {
    if (peek() != '"') {
      throw error("Expected '\"'");
    }
    consume(); // opening "

    StringBuilder sb = new StringBuilder();
    while (pos < input.length()) {
      char ch = input.charAt(pos);
      if (ch == '"') {
        consume();
        return sb.toString();
      } else if (ch == '\\') {
        if (pos + 1 >= input.length()) {
          throw error("Lone backslash at end of string");
        }
        consume();
        char escaped = input.charAt(pos);
        switch (escaped) {
          case 'n' -> sb.append('\n');
          case 't' -> sb.append('\t');
          case 'r' -> sb.append('\r');
          case '"' -> sb.append('"');
          case '\\' -> sb.append('\\');
          default -> throw error("Invalid escape: \\" + escaped);
        }
        consume();
      } else {
        sb.append(ch);
        consume();
      }
    }
    throw error("Unterminated string");
  }

  private Number parseNumber() {
    int start = pos;
    if (peek() == '-') {
      consume();
    }

    int digitStart = pos;
    while (pos < input.length() && Character.isDigit(peek())) {
      consume();
    }

    // Validate at least one digit after optional minus sign
    if (pos == digitStart) {
      throw error("Expected digit after minus sign");
    }

    boolean isDouble = false;
    if (pos < input.length() && peek() == '.') {
      isDouble = true;
      consume();
      int decimalStart = pos;
      while (pos < input.length() && Character.isDigit(peek())) {
        consume();
      }
      // Validate at least one digit after decimal point
      if (pos == decimalStart) {
        throw error("Expected digit after decimal point");
      }
    }

    String numStr = input.substring(start, pos);
    try {
      // Not a conditional expression: '?:' with a double and a long operand promotes both to
      // double (JLS 15.25.2), which stored every integer in a map literal as a Double.
      if (isDouble) {
        return Double.parseDouble(numStr);
      }
      return Long.parseLong(numStr);
    } catch (NumberFormatException e) {
      throw error("Invalid number: " + numStr);
    }
  }

  private Boolean parseBoolean() {
    if (input.startsWith("true", pos)) {
      pos += 4;
      if (!isDelimiter(pos)) {
        throw error("Invalid boolean literal - expected delimiter after 'true'");
      }
      return true;
    } else if (input.startsWith("false", pos)) {
      pos += 5;
      if (!isDelimiter(pos)) {
        throw error("Invalid boolean literal - expected delimiter after 'false'");
      }
      return false;
    }
    throw error("Expected 'true' or 'false'");
  }

  private Object parseNull() {
    if (input.startsWith("null", pos)) {
      pos += 4;
      if (!isDelimiter(pos)) {
        throw error("Invalid null literal - expected delimiter after 'null'");
      }
      return null;
    }
    throw error("Expected 'null'");
  }

  /**
   * Checks if the position is at a valid delimiter (comma, closing brace, or end of input).
   *
   * @param position the position to check
   * @return true if at a delimiter
   */
  private boolean isDelimiter(int position) {
    if (position >= input.length()) {
      return true; // End of input
    }
    char c = input.charAt(position);
    return c == ',' || c == '}' || Character.isWhitespace(c);
  }

  private char peek() {
    if (pos >= input.length()) {
      throw error("Unexpected end of input");
    }
    return input.charAt(pos);
  }

  private void consume() {
    pos++;
  }

  private void skipWs() {
    while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
      pos++;
    }
  }

  private IllegalArgumentException error(String message) {
    return new IllegalArgumentException(message + " at position " + pos);
  }
}

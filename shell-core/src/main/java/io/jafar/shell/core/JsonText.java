package io.jafar.shell.core;

import io.jafar.parser.api.ArrayType;
import io.jafar.parser.api.ComplexType;
import java.lang.reflect.Array;
import java.util.Locale;
import java.util.Map;

/**
 * The JSON the shells print: {@code show --format json} (jfr-shell and the unified shell) and the
 * one-line events of {@code tail --format json}. One writer, so the outputs agree on every value
 * (#266): a map is an object, a {@link java.util.Collection} or Java array (primitive arrays
 * included) is an array, a boolean or finite number is itself, a non-finite {@code double} or
 * {@code float} is {@code null} (JSON has no NaN or Infinity), and anything else is the string of
 * its {@code toString}: an {@code Iterable} that is not a collection too, since some iterate to
 * themselves (a one-name {@link java.nio.file.Path}). The untyped parser's wrappers are written as
 * what they hold: an {@link ArrayType} as its array (a stack trace's {@code frames}), a {@link
 * ComplexType} as its resolved map. Constants nested inside stay as their wrapper maps ({@code
 * {"string": "libmPow"}}), as jfr-shell's other formats keep them.
 */
public final class JsonText {

  /** Pre-rendered unicode escapes for the 32 control characters (allocation-light per char). */
  private static final String[] CONTROL_ESCAPES = new String[0x20];

  static {
    for (int i = 0; i < 0x20; i++) {
      CONTROL_ESCAPES[i] = String.format(Locale.ROOT, "\\u%04x", i);
    }
  }

  private JsonText() {}

  /**
   * {@code value} as indented JSON: two spaces per level, each member or element on its own line.
   */
  public static String pretty(Object value) {
    StringBuilder sb = new StringBuilder();
    appendPretty(sb, value, 0);
    return sb.toString();
  }

  /** Appends {@code value} as JSON on one line, without spaces. */
  public static void appendCompact(StringBuilder sb, Object value) {
    value = unwrapped(value);
    if (!appendScalar(sb, value)) {
      if (value instanceof Map<?, ?> m) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
          if (!first) {
            sb.append(',');
          }
          first = false;
          appendString(sb, String.valueOf(e.getKey()));
          sb.append(':');
          appendCompact(sb, e.getValue());
        }
        sb.append('}');
      } else {
        sb.append('[');
        boolean first = true;
        for (Object element : elements(value)) {
          if (!first) {
            sb.append(',');
          }
          first = false;
          appendCompact(sb, element);
        }
        sb.append(']');
      }
    }
  }

  /** {@code s} escaped for the inside of a JSON string, without the quotes. */
  public static String escape(String s) {
    StringBuilder sb = new StringBuilder(s.length() + 8);
    appendEscaped(sb, s);
    return sb.toString();
  }

  private static void appendPretty(StringBuilder sb, Object value, int indent) {
    value = unwrapped(value);
    if (appendScalar(sb, value)) {
      return;
    }
    String ind = "  ".repeat(indent);
    if (value instanceof Map<?, ?> m) {
      sb.append("{\n");
      boolean first = true;
      for (Map.Entry<?, ?> e : m.entrySet()) {
        if (!first) {
          sb.append(",\n");
        }
        first = false;
        sb.append(ind).append("  ");
        appendString(sb, String.valueOf(e.getKey()));
        sb.append(": ");
        appendPretty(sb, e.getValue(), indent + 1);
      }
      sb.append('\n').append(ind).append('}');
      return;
    }
    sb.append("[\n");
    boolean first = true;
    for (Object element : elements(value)) {
      if (!first) {
        sb.append(",\n");
      }
      first = false;
      sb.append(ind).append("  ");
      appendPretty(sb, element, indent + 1);
    }
    sb.append('\n').append(ind).append(']');
  }

  /**
   * Appends {@code value} if it is not a container (map, Java array, iterable).
   *
   * @return whether it was appended
   */
  private static boolean appendScalar(StringBuilder sb, Object value) {
    if (value == null) {
      sb.append("null");
    } else if (value instanceof Boolean b) {
      sb.append(b.booleanValue());
    } else if (value instanceof Double d) {
      sb.append(Double.isFinite(d) ? d.toString() : "null");
    } else if (value instanceof Float f) {
      sb.append(Float.isFinite(f) ? f.toString() : "null");
    } else if (value instanceof Number n) {
      sb.append(n);
    } else if (value instanceof Map<?, ?>
        || value.getClass().isArray()
        || value instanceof java.util.Collection<?>) {
      return false;
    } else {
      appendString(sb, String.valueOf(value));
    }
    return true;
  }

  /**
   * What a parser wrapper holds: an {@link ArrayType}'s array, a {@link ComplexType}'s map
   * (resolved here, which may be {@code null}); any other value as it is.
   */
  private static Object unwrapped(Object value) {
    if (value instanceof ArrayType array) {
      return array.getArray();
    }
    if (value instanceof ComplexType complex) {
      return complex.getValue();
    }
    return value;
  }

  /** The elements of a Java array (primitive arrays boxed one by one) or a collection. */
  private static Iterable<?> elements(Object container) {
    if (!container.getClass().isArray()) {
      return (Iterable<?>) container;
    }
    int n = Array.getLength(container);
    return () ->
        new java.util.Iterator<Object>() {
          private int next;

          @Override
          public boolean hasNext() {
            return next < n;
          }

          @Override
          public Object next() {
            if (next >= n) {
              throw new java.util.NoSuchElementException();
            }
            return Array.get(container, next++);
          }
        };
  }

  private static void appendString(StringBuilder sb, String s) {
    sb.append('"');
    appendEscaped(sb, s);
    sb.append('"');
  }

  private static void appendEscaped(StringBuilder sb, String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(CONTROL_ESCAPES[c]);
          } else {
            sb.append(c);
          }
        }
      }
    }
  }
}

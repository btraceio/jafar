package io.jafar.shell.live;

import java.util.Map;

/** Single-line JSON serialization for streamed events ({@code tail --format json}). */
final class JsonLines {

  /** Pre-rendered unicode escapes for the 32 control characters (allocation-light per char). */
  private static final String[] CONTROL_ESCAPES = new String[0x20];

  static {
    for (int i = 0; i < 0x20; i++) {
      CONTROL_ESCAPES[i] = String.format("\\u%04x", i);
    }
  }

  private JsonLines() {}

  static String toJson(String typeName, Map<String, Object> map) {
    StringBuilder sb = new StringBuilder(160);
    sb.append("{\"type\":\"").append(escape(typeName)).append('"');
    for (Map.Entry<String, Object> e : map.entrySet()) {
      sb.append(",\"").append(escape(e.getKey())).append("\":");
      appendValue(sb, e.getValue());
    }
    return sb.append('}').toString();
  }

  private static void appendValue(StringBuilder sb, Object v) {
    if (v == null) {
      sb.append("null");
    } else if (v instanceof Boolean b) {
      sb.append(b);
    } else if (v instanceof Double d) {
      // Non-finite doubles/floats are invalid JSON: emit literal null (never a raw NaN token)
      sb.append(Double.isFinite(d) ? d : "null");
    } else if (v instanceof Float f) {
      sb.append(Float.isFinite(f) ? f : "null");
    } else if (v instanceof Number n) {
      sb.append(n);
    } else if (v instanceof Map<?, ?> m) {
      sb.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : m.entrySet()) {
        if (!first) sb.append(',');
        first = false;
        sb.append('"').append(escape(String.valueOf(e.getKey()))).append("\":");
        appendValue(sb, e.getValue());
      }
      sb.append('}');
    } else if (v instanceof Iterable<?> it) {
      sb.append('[');
      boolean first = true;
      for (Object o : it) {
        if (!first) sb.append(',');
        first = false;
        appendValue(sb, o);
      }
      sb.append(']');
    } else {
      sb.append('"').append(escape(String.valueOf(v))).append('"');
    }
  }

  private static String escape(String s) {
    StringBuilder sb = new StringBuilder(s.length() + 8);
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
    return sb.toString();
  }
}

package io.jafar.mcp.bridge;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;

/**
 * The little JSON-RPC the bridge needs to understand: is a frame a request, a notification or a
 * response, and what is its id.
 *
 * <p>Deliberately built on Jackson's streaming parser rather than {@code ObjectMapper}. The bridge
 * starts once per MCP client, and constructing an {@code ObjectMapper} loads some 350 databind
 * classes (about 300ms) to read two fields; the streaming parser does the same job in about 60ms.
 */
final class JsonRpc {

  private static final JsonFactory FACTORY = JsonFactory.builder().build();

  private JsonRpc() {}

  /**
   * The parts of a frame the bridge acts on. {@code idJson} is the id as canonical JSON text (a
   * number as written, a string quoted and escaped one way only), so the same id compares equal
   * however each side spelled it.
   */
  record Frame(boolean hasMethod, String method, String idJson) {
    boolean isRequest() {
      return hasMethod && idJson != null;
    }

    boolean isResponse() {
      return !hasMethod && idJson != null;
    }
  }

  /** Reads the top-level {@code method} and {@code id}; {@code null} if not a JSON object. */
  static Frame parse(String json) {
    try (JsonParser p = FACTORY.createParser(json)) {
      if (p.nextToken() != JsonToken.START_OBJECT) {
        return null;
      }
      boolean hasMethod = false;
      String method = null;
      String idJson = null;
      while (p.nextToken() == JsonToken.PROPERTY_NAME) {
        String name = p.currentName();
        JsonToken value = p.nextToken();
        if (name.equals("method")) {
          hasMethod = true;
          method = value == JsonToken.VALUE_STRING ? p.getString() : null;
          p.skipChildren();
        } else if (name.equals("id")) {
          idJson = idAsJson(p, value);
          p.skipChildren();
        } else {
          p.skipChildren(); // a no-op for scalars; skips a whole nested object or array
        }
      }
      return new Frame(hasMethod, method, idJson);
    } catch (JacksonException e) {
      return null;
    }
  }

  private static String idAsJson(JsonParser p, JsonToken value) {
    return switch (value) {
      case VALUE_STRING -> quote(p.getString());
      case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> p.getString();
      case VALUE_NULL -> "null";
      default -> null; // an object, array or boolean is not a usable id
    };
  }

  /** A JSON-RPC error response for request {@code idJson}, on one line. */
  static String error(String idJson, int code, String message) {
    return "{\"jsonrpc\":\"2.0\",\"id\":"
        + idJson
        + ",\"error\":{\"code\":"
        + code
        + ",\"message\":"
        + quote(message)
        + "}}";
  }

  /** Removes the whitespace between tokens, leaving string contents alone. */
  static String compact(String json) {
    StringBuilder out = new StringBuilder(json.length());
    boolean inString = false;
    boolean escaped = false;
    for (int i = 0; i < json.length(); i++) {
      char c = json.charAt(i);
      if (inString) {
        out.append(c);
        if (escaped) {
          escaped = false;
        } else if (c == '\\') {
          escaped = true;
        } else if (c == '"') {
          inString = false;
        }
      } else if (c == '"') {
        inString = true;
        out.append(c);
      } else if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
        out.append(c);
      }
    }
    return out.toString();
  }

  private static String quote(String s) {
    StringBuilder out = new StringBuilder(s.length() + 2).append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }
}

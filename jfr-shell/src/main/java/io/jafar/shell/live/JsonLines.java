package io.jafar.shell.live;

import io.jafar.shell.core.JsonText;
import java.util.Map;

/**
 * Single-line JSON serialization for streamed events ({@code tail --format json}), through the
 * writer {@code show --format json} uses ({@link JsonText}).
 */
final class JsonLines {

  private JsonLines() {}

  static String toJson(String typeName, Map<String, Object> map) {
    StringBuilder sb = new StringBuilder(160);
    sb.append("{\"type\":\"").append(JsonText.escape(typeName)).append('"');
    for (Map.Entry<String, Object> e : map.entrySet()) {
      sb.append(",\"").append(JsonText.escape(e.getKey())).append("\":");
      JsonText.appendCompact(sb, e.getValue());
    }
    return sb.append('}').toString();
  }
}

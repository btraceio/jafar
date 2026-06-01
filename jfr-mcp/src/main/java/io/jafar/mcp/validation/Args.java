package io.jafar.mcp.validation;

import java.util.List;
import java.util.Map;

/** Type-safe accessors for MCP tool argument maps. */
public final class Args {

  private final Map<String, Object> values;

  public Args(Map<String, Object> values) {
    this.values = values;
  }

  public static Args of(Map<String, Object> values) {
    return new Args(values);
  }

  public String string(String name) {
    Object value = values.get(name);
    return value instanceof String s ? s : null;
  }

  public String requiredString(String name, String errorMessage) {
    String value = string(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(errorMessage);
    }
    return value;
  }

  public int positiveInt(String name, int defaultValue, String errorMessage) {
    Object value = values.get(name);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof Number number)) {
      throw new IllegalArgumentException(errorMessage);
    }
    int intValue = number.intValue();
    if (intValue <= 0) {
      throw new IllegalArgumentException(errorMessage);
    }
    return intValue;
  }

  @SuppressWarnings("unchecked")
  public List<String> stringList(String name) {
    Object value = values.get(name);
    return value instanceof List<?> ? (List<String>) value : null;
  }
}

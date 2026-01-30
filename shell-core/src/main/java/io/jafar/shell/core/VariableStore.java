package io.jafar.shell.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Stores variables with support for scalar values, maps, and lazy query results. Variables can hold
 * immediate scalar values (String, Number, Boolean), structured map values, or lazy query results
 * that are evaluated on-demand.
 */
public class VariableStore {

  /** A variable value that can be retrieved and released. */
  public sealed interface Value permits ScalarValue, MapValue, LazyValue {
    /**
     * Gets the value. For lazy values, this triggers evaluation.
     *
     * @return the value (scalar, Map, or List of Maps for query results)
     * @throws Exception if the value cannot be retrieved (e.g., session closed)
     */
    Object get() throws Exception;

    /** Releases any resources held by this value. Called when variable is replaced or removed. */
    default void release() {}

    /** Returns a description of this value for display in vars command. */
    String describe();
  }

  /** Immediate scalar value (String, Number, Boolean, etc.). */
  public record ScalarValue(Object value) implements Value {
    @Override
    public Object get() {
      return value;
    }

    @Override
    public String describe() {
      if (value == null) return "null";
      if (value instanceof String) return "\"" + value + "\"";
      return value.toString();
    }
  }

  /**
   * Immutable map value for structured data storage. Maps can contain nested maps, scalars, and
   * collections. Used for configuration management, custom report structures, and variable
   * namespacing.
   */
  public record MapValue(Map<String, Object> value) implements Value {
    public MapValue {
      // Ensure immutability - create defensive copy
      // Use Collections.unmodifiableMap instead of Map.copyOf to support null values
      value = Collections.unmodifiableMap(new HashMap<>(value));
    }

    @Override
    public Object get() {
      return value;
    }

    /**
     * Gets a nested field value using dot-notation path.
     *
     * @param path field path (e.g., "db.host" or "config.timeout")
     * @return the value at the path, or null if not found or if any intermediate field is null
     */
    public Object getField(String path) {
      String[] parts = path.split("\\.");
      Object current = value;

      for (String part : parts) {
        if (!(current instanceof Map<?, ?> map)) {
          return null;
        }
        current = map.get(part);
        if (current == null) {
          return null;
        }
      }

      return current;
    }

    @Override
    public String describe() {
      return "map" + formatMap(value, 0, 2);
    }

    /**
     * Formats a map for display with limited depth to avoid overwhelming output.
     *
     * @param map the map to format
     * @param depth current nesting depth
     * @param maxDepth maximum nesting depth before truncation
     * @return formatted string representation
     */
    private String formatMap(Map<?, ?> map, int depth, int maxDepth) {
      if (map.isEmpty()) {
        return "{}";
      }

      if (depth >= maxDepth) {
        return "{...}";
      }

      StringBuilder sb = new StringBuilder("{");
      boolean first = true;
      int count = 0;
      final int maxEntries = 5; // Show at most 5 entries at each level

      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (count >= maxEntries) {
          sb.append(", ...");
          break;
        }

        if (!first) {
          sb.append(", ");
        }
        first = false;

        sb.append(entry.getKey()).append("=");
        Object val = entry.getValue();

        if (val instanceof Map<?, ?> nestedMap) {
          sb.append(formatMap(nestedMap, depth + 1, maxDepth));
        } else if (val instanceof String str) {
          sb.append("\"").append(escapeString(str)).append("\"");
        } else if (val == null) {
          sb.append("null");
        } else {
          sb.append(val);
        }

        count++;
      }

      sb.append("}");
      return sb.toString();
    }

    /**
     * Escapes special characters in a string for display.
     *
     * @param str the string to escape
     * @return escaped string
     */
    private String escapeString(String str) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < str.length(); i++) {
        char c = str.charAt(i);
        switch (c) {
          case '"' -> sb.append("\\\"");
          case '\\' -> sb.append("\\\\");
          case '\n' -> sb.append("\\n");
          case '\r' -> sb.append("\\r");
          case '\t' -> sb.append("\\t");
          default -> sb.append(c);
        }
      }
      return sb.toString();
    }
  }

  /**
   * Base interface for lazy-evaluated query values. Domain-specific modules (JFR, heap dump)
   * provide their own implementations that evaluate queries against their respective data sources.
   */
  public non-sealed interface LazyValue extends Value {
    /** Invalidates the cache, forcing re-evaluation on next access. */
    void invalidate();

    /** Returns whether the result has been cached. */
    boolean isCached();

    /** Returns the original query string. */
    String getQueryString();
  }

  private final Map<String, Value> variables = new HashMap<>();

  /**
   * Sets a variable value, releasing any previous value.
   *
   * @param name variable name
   * @param value the new value
   */
  public void set(String name, Value value) {
    Value old = variables.put(name, value);
    if (old != null) {
      old.release();
    }
  }

  /**
   * Gets a variable value.
   *
   * @param name variable name
   * @return the value, or null if not found
   */
  public Value get(String name) {
    return variables.get(name);
  }

  /**
   * Checks if a variable exists.
   *
   * @param name variable name
   * @return true if the variable exists
   */
  public boolean contains(String name) {
    return variables.containsKey(name);
  }

  /**
   * Removes a variable, releasing its value.
   *
   * @param name variable name
   * @return true if the variable existed
   */
  public boolean remove(String name) {
    Value old = variables.remove(name);
    if (old != null) {
      old.release();
      return true;
    }
    return false;
  }

  /** Clears all variables, releasing all values. */
  public void clear() {
    for (Value v : variables.values()) {
      v.release();
    }
    variables.clear();
  }

  /**
   * Returns the names of all variables.
   *
   * @return set of variable names
   */
  public Set<String> names() {
    return variables.keySet();
  }

  /**
   * Returns the number of variables.
   *
   * @return variable count
   */
  public int size() {
    return variables.size();
  }

  /**
   * Returns whether the store is empty.
   *
   * @return true if no variables are stored
   */
  public boolean isEmpty() {
    return variables.isEmpty();
  }
}

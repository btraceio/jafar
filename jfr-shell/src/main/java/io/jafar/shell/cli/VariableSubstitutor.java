package io.jafar.shell.cli;

import io.jafar.shell.core.VariableStore;
import io.jafar.shell.core.VariableStore.LazyQueryValue;
import io.jafar.shell.core.VariableStore.MapValue;
import io.jafar.shell.core.VariableStore.ScalarValue;
import io.jafar.shell.core.VariableStore.Value;
import java.util.Collection;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes ${varname} references in strings with values from VariableStore. Supports variable
 * access patterns:
 *
 * <ul>
 *   <li>${var} - scalar value, or first value from first row of result set
 *   <li>${var.size} - row count for result sets
 *   <li>${var.field} - field from first row or map value
 *   <li>${var.a.b.c} - nested field access (multi-level)
 *   <li>${var[idx].field} - field from specific row (0-indexed)
 *   <li>${var[idx].a.b} - nested field from specific row
 * </ul>
 */
public final class VariableSubstitutor {

  // Matches ${name} or ${name[idx]} followed by optional .field.path
  private static final Pattern VAR_PATTERN =
      Pattern.compile(
          "\\$\\{([a-zA-Z_][a-zA-Z0-9_]*)(?:\\[(\\d+)\\])?(\\.(?:[a-zA-Z_][a-zA-Z0-9_]*)(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)*)?\\}");

  private final VariableStore sessionStore; // may be null
  private final VariableStore globalStore; // may be null

  /**
   * Creates a substitutor with session and global stores.
   *
   * @param sessionStore session-scoped variables (may be null)
   * @param globalStore global variables (may be null)
   */
  public VariableSubstitutor(VariableStore sessionStore, VariableStore globalStore) {
    this.sessionStore = sessionStore;
    this.globalStore = globalStore;
  }

  /**
   * Substitutes all ${var} references in the input string.
   *
   * @param input the input string with variable references
   * @return the string with variables substituted
   * @throws IllegalArgumentException if a referenced variable doesn't exist
   * @throws IllegalStateException if a lazy variable's session is unavailable
   */
  public String substitute(String input) throws Exception {
    if (input == null || !input.contains("${")) {
      return input;
    }

    StringBuffer result = new StringBuffer();
    Matcher m = VAR_PATTERN.matcher(input);

    while (m.find()) {
      String varName = m.group(1);
      String indexStr = m.group(2); // may be null
      String path = m.group(3); // may be null, starts with dot if present

      Value value = resolve(varName);
      if (value == null) {
        throw new IllegalArgumentException("Undefined variable: " + varName);
      }

      String replacement = extractValue(value, indexStr, path);
      m.appendReplacement(result, Matcher.quoteReplacement(replacement));
    }
    m.appendTail(result);

    return result.toString();
  }

  /**
   * Checks if the input contains any variable references.
   *
   * @param input the input string
   * @return true if variables are present
   */
  public static boolean hasVariables(String input) {
    return input != null && input.contains("${");
  }

  /**
   * Resolves a variable by name, checking session store first, then global.
   *
   * @param name variable name
   * @return the value, or null if not found
   */
  private Value resolve(String name) {
    if (sessionStore != null && sessionStore.contains(name)) {
      return sessionStore.get(name);
    }
    if (globalStore != null && globalStore.contains(name)) {
      return globalStore.get(name);
    }
    return null;
  }

  /**
   * Extracts the appropriate value based on access pattern.
   *
   * @param value the variable value
   * @param indexStr row index as string, or null for first row
   * @param path property/field path, or null for first/scalar value (starts with dot if present)
   * @return the extracted value as a string
   */
  private String extractValue(Value value, String indexStr, String path) throws Exception {
    // Remove leading dot from path if present
    String fieldPath = (path != null && path.startsWith(".")) ? path.substring(1) : path;

    if (value instanceof ScalarValue sv) {
      // Scalar values ignore index and path (except "size" returns "1")
      if ("size".equals(fieldPath)) {
        return "1";
      }
      Object v = sv.value();
      return v == null ? "" : String.valueOf(v);
    }

    if (value instanceof MapValue mv) {
      // Handle .size property for maps
      if ("size".equals(fieldPath)) {
        return String.valueOf(mv.value().size());
      }

      // Navigate nested map structure
      Object result;
      if (fieldPath == null || fieldPath.isEmpty()) {
        // ${map} returns the map itself as string
        result = mv.value();
      } else {
        result = mv.getField(fieldPath);
      }
      return result == null ? "" : formatValue(result);
    }

    if (value instanceof LazyQueryValue lqv) {
      // Handle .size property
      if ("size".equals(fieldPath) && indexStr == null) {
        return String.valueOf(lqv.size());
      }

      int idx = (indexStr != null) ? Integer.parseInt(indexStr) : 0;

      // For lazy queries, we need to handle nested paths differently
      // First extract the row, then navigate the path within the row
      if (fieldPath != null && fieldPath.contains(".")) {
        // Split path into first field and rest
        String[] parts = fieldPath.split("\\.", 2);
        String firstField = parts[0];
        String restPath = parts.length > 1 ? parts[1] : null;

        Object extracted = lqv.extract(idx, firstField);
        if (restPath != null && extracted instanceof Map) {
          // Navigate nested map structure
          @SuppressWarnings("unchecked")
          Map<String, Object> map = (Map<String, Object>) extracted;
          extracted = navigateMap(map, restPath);
        }
        return extracted == null ? "" : String.valueOf(extracted);
      } else {
        Object extracted = lqv.extract(idx, fieldPath);
        return extracted == null ? "" : String.valueOf(extracted);
      }
    }

    return "";
  }

  /**
   * Navigates a nested map structure using dot-notation path.
   *
   * @param map the map to navigate
   * @param path the path (e.g., "a.b.c")
   * @return the value at the path, or null if not found
   */
  private Object navigateMap(Map<String, Object> map, String path) {
    if (map == null || path == null || path.isEmpty()) {
      return null;
    }

    String[] parts = path.split("\\.");
    Object current = map;

    for (String part : parts) {
      if (!(current instanceof Map)) {
        return null;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> currentMap = (Map<String, Object>) current;
      current = currentMap.get(part);
      if (current == null) {
        return null;
      }
    }

    return current;
  }

  /**
   * Formats a value for display, handling maps and collections specially.
   *
   * @param value the value to format
   * @return formatted string representation
   */
  private String formatValue(Object value) {
    if (value instanceof Map) {
      // Format map as simple string (not full JSON)
      return value.toString();
    } else if (value instanceof Collection || value.getClass().isArray()) {
      return String.valueOf(value);
    } else {
      return String.valueOf(value);
    }
  }
}

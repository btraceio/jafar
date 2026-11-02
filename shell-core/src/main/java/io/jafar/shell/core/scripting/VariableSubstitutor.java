package io.jafar.shell.core.scripting;

import io.jafar.shell.core.VariableStore;
import io.jafar.shell.core.VariableStore.MapValue;
import io.jafar.shell.core.VariableStore.ScalarValue;
import io.jafar.shell.core.VariableStore.Value;
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

  // A reference: name, optional [idx], optional .field.path. Groups: 1 name, 2 index (may be null),
  // 3 path with its leading dot (may be null).
  private static final String REFERENCE =
      "([a-zA-Z_][a-zA-Z0-9_]*)"
          + "(?:\\[(\\d+)\\])?"
          + "(\\.(?:[a-zA-Z_][a-zA-Z0-9_]*)(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)*)?";

  // Matches ${name} or ${name[idx]} followed by optional .field.path
  private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{" + REFERENCE + "\\}");

  /**
   * The bare reference grammar of {@code ${...}}, without the braces, with the same groups. {@link
   * ConditionEvaluator} parses {@code exists()}/{@code empty()} arguments with it, so the two
   * cannot accept different shapes.
   */
  static final Pattern REFERENCE_PATTERN = Pattern.compile(REFERENCE);

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
   * <p>Package-private so that {@link ConditionEvaluator} resolves names with the same precedence.
   *
   * @param name variable name
   * @return the value, or null if not found
   */
  Value resolve(String name) {
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
    Object result = extractRaw(value, indexStr, fieldPath);
    return result == null ? "" : String.valueOf(result);
  }

  /**
   * Extracts the unformatted value a {@code ${name[idx].path}} reference denotes. This is the
   * single definition of what a reference means: {@link #substitute} formats its result, and {@link
   * ConditionEvaluator}'s {@code exists()} and {@code empty()} inspect it.
   *
   * @param value the variable value
   * @param indexStr row index as string, or null for first row
   * @param fieldPath property/field path without a leading dot, or null for first/scalar value
   * @return the value, or null if the reference resolves to nothing
   */
  Object extractRaw(Value value, String indexStr, String fieldPath) throws Exception {
    if (value instanceof ScalarValue sv) {
      // Scalar values ignore index and path (except "size" returns 1)
      if ("size".equals(fieldPath)) {
        return 1;
      }
      return sv.value();
    }

    if (value instanceof MapValue mv) {
      // Handle .size property for maps
      if ("size".equals(fieldPath)) {
        return mv.value().size();
      }

      // Navigate nested map structure; ${map} denotes the map itself
      if (fieldPath == null || fieldPath.isEmpty()) {
        return mv.value();
      }
      return mv.getField(fieldPath);
    }

    if (value instanceof VariableStore.LazyValue lazy) {
      // Any query-valued variable, whichever module's evaluator produced it: the rows are what
      // get() returns, read the same way for every format.
      Object rows = lazy.get();
      // Handle .size property
      if ("size".equals(fieldPath) && indexStr == null) {
        return LazyRows.size(rows);
      }

      int idx = (indexStr != null) ? Integer.parseInt(indexStr) : 0;

      // For lazy queries, we need to handle nested paths differently
      // First extract the row, then navigate the path within the row
      if (fieldPath != null && fieldPath.contains(".")) {
        // Split path into first field and rest
        String[] parts = fieldPath.split("\\.", 2);
        String firstField = parts[0];
        String restPath = parts.length > 1 ? parts[1] : null;

        Object extracted = LazyRows.extract(rows, idx, firstField);
        if (restPath != null && extracted instanceof Map) {
          // Navigate nested map structure
          @SuppressWarnings("unchecked")
          Map<String, Object> map = (Map<String, Object>) extracted;
          extracted = navigateMap(map, restPath);
        }
        return extracted;
      }
      return LazyRows.extract(rows, idx, fieldPath);
    }

    return null;
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
}

package io.jafar.shell.cli;

import io.jafar.shell.core.VariableStore;
import io.jafar.shell.core.VariableStore.LazyQueryValue;
import io.jafar.shell.core.VariableStore.ScalarValue;
import io.jafar.shell.core.VariableStore.Value;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes ${varname} references in strings with values from VariableStore. Supports variable
 * access patterns:
 *
 * <ul>
 *   <li>${var} - scalar value, or first value from first row of result set
 *   <li>${var.size} - row count for result sets
 *   <li>${var.field} - field from first row
 *   <li>${var[idx].field} - field from specific row (0-indexed)
 * </ul>
 */
public final class VariableSubstitutor {

  // Matches ${name} or ${name.prop} or ${name[idx]} or ${name[idx].prop}
  private static final Pattern VAR_PATTERN =
      Pattern.compile(
          "\\$\\{([a-zA-Z_][a-zA-Z0-9_]*)(?:\\[(\\d+)\\])?(?:\\.([a-zA-Z_][a-zA-Z0-9_]*))?\\}");

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
      String prop = m.group(3); // may be null

      Value value = resolve(varName);
      if (value == null) {
        throw new IllegalArgumentException("Undefined variable: " + varName);
      }

      String replacement = extractValue(value, indexStr, prop);
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
   * @param prop property/field name, or null for first/scalar value
   * @return the extracted value as a string
   */
  private String extractValue(Value value, String indexStr, String prop) throws Exception {
    if (value instanceof ScalarValue sv) {
      // Scalar values ignore index and prop (except "size" returns "1")
      if ("size".equals(prop)) {
        return "1";
      }
      Object v = sv.value();
      return v == null ? "" : String.valueOf(v);
    }

    if (value instanceof LazyQueryValue lqv) {
      // Handle .size property
      if ("size".equals(prop) && indexStr == null) {
        return String.valueOf(lqv.size());
      }

      int idx = (indexStr != null) ? Integer.parseInt(indexStr) : 0;
      Object extracted = lqv.extract(idx, prop);
      return extracted == null ? "" : String.valueOf(extracted);
    }

    return "";
  }
}

package io.jafar.shell.core.scripting;

import io.jafar.shell.core.VariableStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * {@code merge(${a}, ${b}, {...})}: combines map variables and map literals into one map, last
 * argument winning on duplicate keys.
 *
 * <p>Shared by {@code jfr-shell}'s and {@code jafar-shell}'s {@code set name = merge(...)}.
 */
public final class MapMerge {
  private MapMerge() {}

  /** Whether the expression is a {@code merge(...)} call. */
  public static boolean isMergeCall(String expr) {
    String trimmed = expr.trim();
    return trimmed.startsWith("merge(") && trimmed.endsWith(")");
  }

  /**
   * Parses and executes a merge function call: merge(${map1}, ${map2}, ...)
   *
   * @param expr the merge function expression
   * @param lookup resolves a {@code ${name}} argument to its variable, or {@code null}
   * @return merged map
   * @throws IllegalArgumentException for fewer than two arguments, an unknown variable, a variable
   *     that is not a map, or an argument that is neither a variable nor a map literal
   */
  public static Map<String, Object> evaluate(
      String expr, Function<String, VariableStore.Value> lookup) {
    String trimmed = expr.trim();
    // Extract argument list between merge( and )
    String argList = trimmed.substring(6, trimmed.length() - 1).trim();

    if (argList.isEmpty()) {
      throw new IllegalArgumentException("merge() requires at least 2 arguments");
    }

    // Parse arguments
    List<String> args = parseMergeArgs(argList);

    if (args.size() < 2) {
      throw new IllegalArgumentException(
          "merge() requires at least 2 arguments, got " + args.size());
    }

    // Resolve each argument to a map
    List<Map<String, Object>> maps = new ArrayList<>();
    for (String arg : args) {
      maps.add(resolveMapArg(arg, lookup));
    }

    // Merge all maps
    return mergeMaps(maps);
  }

  /**
   * Parses merge function arguments, splitting by comma while respecting nested structures.
   *
   * @param argList comma-separated argument list
   * @return list of trimmed arguments
   */
  private static List<String> parseMergeArgs(String argList) {
    List<String> args = new ArrayList<>();
    int start = 0;
    int depth = 0;
    boolean inString = false;

    for (int i = 0; i < argList.length(); i++) {
      char c = argList.charAt(i);

      // Track string boundaries
      if (c == '"' && (i == 0 || argList.charAt(i - 1) != '\\')) {
        inString = !inString;
      }

      if (!inString) {
        // Track nesting depth
        if (c == '(' || c == '{' || c == '[') {
          depth++;
        }
        if (c == ')' || c == '}' || c == ']') {
          depth--;
        }

        // Split on comma at depth 0
        if (c == ',' && depth == 0) {
          args.add(argList.substring(start, i).trim());
          start = i + 1;
        }
      }
    }

    // Add last argument
    args.add(argList.substring(start).trim());

    return args;
  }

  /**
   * Resolves a merge argument to a map. Supports variable references and map literals.
   *
   * @param arg the argument string
   * @param lookup the variable lookup
   * @return resolved map
   */
  private static Map<String, Object> resolveMapArg(
      String arg, Function<String, VariableStore.Value> lookup) {
    // Check for variable reference: ${varName}
    if (arg.startsWith("${") && arg.endsWith("}")) {
      String varName = arg.substring(2, arg.length() - 1);
      VariableStore.Value value = lookup.apply(varName);

      if (value == null) {
        throw new IllegalArgumentException("Variable not found: " + varName);
      }
      if (!(value instanceof VariableStore.MapValue mapValue)) {
        throw new IllegalArgumentException(
            "Variable is not a map: "
                + varName
                + " (type: "
                + value.getClass().getSimpleName()
                + ")");
      }

      return mapValue.value();
    }

    // Check for map literal: {...}
    if (arg.startsWith("{") && arg.endsWith("}")) {
      return MapLiteralParser.parse(arg);
    }

    throw new IllegalArgumentException(
        "Merge argument must be a map variable (${name}) or map literal {...}: " + arg);
  }

  /**
   * Merges multiple maps into a single map. Last wins for duplicate keys.
   *
   * @param maps list of maps to merge
   * @return merged map with insertion order preserved
   */
  private static Map<String, Object> mergeMaps(List<Map<String, Object>> maps) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map<String, Object> map : maps) {
      // Last wins: putAll overwrites existing keys
      result.putAll(map);
    }
    return result;
  }
}

package io.jafar.shell.cli.completion;

import java.util.Collections;
import java.util.List;

/**
 * Specification for a function parameter used in code completion. Defines the parameter's type,
 * position, and valid values for intelligent completion suggestions.
 *
 * @param index Parameter position (0-indexed for positional params, -1 for keyword-only)
 * @param name Parameter name (null for positional-only, e.g., "agg" for keyword params)
 * @param type The type of value this parameter accepts
 * @param enumValues Valid values for ENUM type parameters (empty for other types)
 * @param required Whether this parameter is required
 * @param multi Whether this parameter accepts multiple values (comma-separated)
 * @param description Human-readable description for completion hints
 */
public record ParamSpec(
    int index,
    String name,
    ParamType type,
    List<String> enumValues,
    boolean required,
    boolean multi,
    String description) {

  /** Canonical constructor with validation */
  public ParamSpec {
    if (enumValues == null) {
      enumValues = Collections.emptyList();
    }
    if (description == null) {
      description = "";
    }
    if (type == ParamType.ENUM && enumValues.isEmpty()) {
      throw new IllegalArgumentException("ENUM type requires non-empty enumValues");
    }
  }

  // Factory methods for common parameter patterns

  /** Create a required positional parameter */
  public static ParamSpec positional(int index, ParamType type, String description) {
    return new ParamSpec(index, null, type, Collections.emptyList(), true, false, description);
  }

  /** Create an optional positional parameter */
  public static ParamSpec optionalPositional(int index, ParamType type, String description) {
    return new ParamSpec(index, null, type, Collections.emptyList(), false, false, description);
  }

  /** Create a required keyword parameter */
  public static ParamSpec keyword(String name, ParamType type, String description) {
    return new ParamSpec(-1, name, type, Collections.emptyList(), true, false, description);
  }

  /** Create an optional keyword parameter */
  public static ParamSpec optionalKeyword(String name, ParamType type, String description) {
    return new ParamSpec(-1, name, type, Collections.emptyList(), false, false, description);
  }

  /** Create a keyword parameter that accepts multiple values */
  public static ParamSpec multiKeyword(String name, ParamType type, String description) {
    return new ParamSpec(-1, name, type, Collections.emptyList(), false, true, description);
  }

  /** Create an enum keyword parameter with specific valid values */
  public static ParamSpec enumKeyword(
      String name, List<String> values, boolean required, String description) {
    return new ParamSpec(-1, name, ParamType.ENUM, values, required, false, description);
  }

  /** Create an enum positional parameter with specific valid values */
  public static ParamSpec enumPositional(
      int index, List<String> values, boolean required, String description) {
    return new ParamSpec(index, null, ParamType.ENUM, values, required, false, description);
  }

  // Convenience methods

  /** Check if this is a keyword parameter (has a name) */
  public boolean isKeyword() {
    return name != null && !name.isEmpty();
  }

  /** Check if this is a positional parameter */
  public boolean isPositional() {
    return index >= 0 && (name == null || name.isEmpty());
  }

  /** Get the completion prefix for keyword parameters (e.g., "agg=") */
  public String keywordPrefix() {
    return isKeyword() ? name + "=" : "";
  }

  /**
   * Types of parameter values for completion purposes.
   *
   * <p>Each type determines how the completer generates candidates:
   *
   * <ul>
   *   <li>FIELD_PATH - Complete with field names from the event type
   *   <li>EVENT_TYPE - Complete with event type names from metadata
   *   <li>NUMBER - No completion (user enters numeric literal)
   *   <li>STRING - Suggest common string patterns or templates
   *   <li>BOOLEAN - Suggest true/false
   *   <li>ENUM - Suggest from a fixed list of valid values
   *   <li>EXPRESSION - Complex expression (arithmetic, function calls)
   * </ul>
   */
  public enum ParamType {
    /** Field path - complete with field names from current event type */
    FIELD_PATH,

    /** Event type name - complete with event types from metadata */
    EVENT_TYPE,

    /** Numeric literal - no completion */
    NUMBER,

    /** String literal - suggest common patterns */
    STRING,

    /** Boolean value - suggest true/false */
    BOOLEAN,

    /** Enumerated values - suggest from fixed list */
    ENUM,

    /** Complex expression - arithmetic, functions, templates */
    EXPRESSION
  }
}

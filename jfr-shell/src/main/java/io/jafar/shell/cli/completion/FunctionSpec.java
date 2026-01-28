package io.jafar.shell.cli.completion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Specification for a function in the JFR query language. Used by completers to provide
 * context-aware suggestions for function parameters.
 *
 * <p>Functions can be:
 *
 * <ul>
 *   <li>Pipeline operators: Used after | (e.g., groupBy, sum, select)
 *   <li>Filter functions: Used in filter predicates [...] (e.g., contains, exists)
 *   <li>Select functions: Used in select expressions (e.g., upper, substring)
 * </ul>
 *
 * @param name Function name (case-insensitive matching)
 * @param parameters List of parameter specifications in order
 * @param category The function category (PIPELINE, FILTER, SELECT)
 * @param description Human-readable description for help/completion
 * @param template Example usage template shown in completion
 * @param hasVarargs Whether the function accepts variable number of arguments
 * @param requiredFieldTypes Field types required for this function to be applicable
 */
public record FunctionSpec(
    String name,
    List<ParamSpec> parameters,
    FunctionCategory category,
    String description,
    String template,
    boolean hasVarargs,
    Set<RequiredFieldType> requiredFieldTypes) {

  /** Canonical constructor with validation */
  public FunctionSpec {
    if (name == null || name.isEmpty()) {
      throw new IllegalArgumentException("Function name cannot be null or empty");
    }
    if (parameters == null) {
      parameters = Collections.emptyList();
    }
    if (category == null) {
      category = FunctionCategory.PIPELINE;
    }
    if (description == null) {
      description = "";
    }
    if (template == null) {
      template = name + "()";
    }
    if (requiredFieldTypes == null || requiredFieldTypes.isEmpty()) {
      requiredFieldTypes = EnumSet.of(RequiredFieldType.ANY);
    }
  }

  /** Function categories */
  public enum FunctionCategory {
    /** Pipeline operator - used after | */
    PIPELINE,

    /** Filter function - used in [...] predicates */
    FILTER,

    /** Select expression function - used in select() */
    SELECT
  }

  /** Required field types for semantic completion filtering */
  public enum RequiredFieldType {
    /** Function requires numeric fields (long, int, double, Duration, etc.) */
    NUMERIC,

    /** Function requires string fields */
    STRING,

    /** Function requires time-related fields (startTime, timestamps, etc.) */
    TIME,

    /** Function works with any field type (always applicable) */
    ANY
  }

  // Factory methods for creating function specs

  /** Create a pipeline operator with no parameters */
  public static FunctionSpec pipeline(String name, String description) {
    return new FunctionSpec(
        name,
        Collections.emptyList(),
        FunctionCategory.PIPELINE,
        description,
        name + "()",
        false,
        null);
  }

  /** Create a pipeline operator with parameters */
  public static FunctionSpec pipeline(
      String name, String description, String template, List<ParamSpec> params) {
    return new FunctionSpec(
        name, params, FunctionCategory.PIPELINE, description, template, false, null);
  }

  /** Create a pipeline operator with varargs */
  public static FunctionSpec pipelineVarargs(
      String name, String description, String template, List<ParamSpec> params) {
    return new FunctionSpec(
        name, params, FunctionCategory.PIPELINE, description, template, true, null);
  }

  /** Create a filter function */
  public static FunctionSpec filter(
      String name, String description, String template, List<ParamSpec> params) {
    return new FunctionSpec(
        name, params, FunctionCategory.FILTER, description, template, false, null);
  }

  /** Create a select expression function */
  public static FunctionSpec select(
      String name, String description, String template, List<ParamSpec> params) {
    return new FunctionSpec(
        name, params, FunctionCategory.SELECT, description, template, false, null);
  }

  // Convenience methods

  /** Check if this is a pipeline operator */
  public boolean isPipeline() {
    return category == FunctionCategory.PIPELINE;
  }

  /** Check if this is a filter function */
  public boolean isFilter() {
    return category == FunctionCategory.FILTER;
  }

  /** Check if this is a select expression function */
  public boolean isSelect() {
    return category == FunctionCategory.SELECT;
  }

  /** Get positional parameters in order */
  public List<ParamSpec> positionalParams() {
    return parameters.stream()
        .filter(ParamSpec::isPositional)
        .sorted((a, b) -> Integer.compare(a.index(), b.index()))
        .collect(Collectors.toList());
  }

  /** Get keyword parameters */
  public List<ParamSpec> keywordParams() {
    return parameters.stream().filter(ParamSpec::isKeyword).collect(Collectors.toList());
  }

  /** Get the parameter specification for a given index (for positional params) */
  public ParamSpec getPositionalParam(int index) {
    return parameters.stream()
        .filter(p -> p.isPositional() && p.index() == index)
        .findFirst()
        .orElse(null);
  }

  /** Get the parameter specification for a given keyword name */
  public ParamSpec getKeywordParam(String keyword) {
    return parameters.stream()
        .filter(p -> p.isKeyword() && keyword.equalsIgnoreCase(p.name()))
        .findFirst()
        .orElse(null);
  }

  /** Get all keyword names for this function */
  public List<String> keywordNames() {
    return parameters.stream()
        .filter(ParamSpec::isKeyword)
        .map(ParamSpec::name)
        .collect(Collectors.toList());
  }

  /** Check if a keyword parameter exists */
  public boolean hasKeyword(String keyword) {
    return getKeywordParam(keyword) != null;
  }

  /** Get required positional parameter count */
  public int requiredPositionalCount() {
    return (int) parameters.stream().filter(p -> p.isPositional() && p.required()).count();
  }

  /**
   * Check if this function is applicable given available field types.
   *
   * @param hasNumeric true if numeric fields are available
   * @param hasString true if string fields are available
   * @param hasTime true if time fields are available
   * @return true if this function can be used with the available field types
   */
  public boolean isApplicable(boolean hasNumeric, boolean hasString, boolean hasTime) {
    if (requiredFieldTypes.contains(RequiredFieldType.ANY)) {
      return true;
    }
    if (requiredFieldTypes.contains(RequiredFieldType.NUMERIC) && hasNumeric) {
      return true;
    }
    if (requiredFieldTypes.contains(RequiredFieldType.STRING) && hasString) {
      return true;
    }
    if (requiredFieldTypes.contains(RequiredFieldType.TIME) && hasTime) {
      return true;
    }
    return false;
  }

  /** Builder for fluent construction of FunctionSpec */
  public static Builder builder(String name) {
    return new Builder(name);
  }

  public static class Builder {
    private final String name;
    private final List<ParamSpec> params = new ArrayList<>();
    private FunctionCategory category = FunctionCategory.PIPELINE;
    private String description = "";
    private String template;
    private boolean hasVarargs = false;
    private Set<RequiredFieldType> requiredFieldTypes = EnumSet.of(RequiredFieldType.ANY);

    Builder(String name) {
      this.name = name;
    }

    public Builder category(FunctionCategory category) {
      this.category = category;
      return this;
    }

    public Builder pipeline() {
      this.category = FunctionCategory.PIPELINE;
      return this;
    }

    public Builder filter() {
      this.category = FunctionCategory.FILTER;
      return this;
    }

    public Builder select() {
      this.category = FunctionCategory.SELECT;
      return this;
    }

    public Builder description(String description) {
      this.description = description;
      return this;
    }

    public Builder template(String template) {
      this.template = template;
      return this;
    }

    public Builder varargs() {
      this.hasVarargs = true;
      return this;
    }

    /** Require numeric fields for this function to be applicable */
    public Builder requiresNumeric() {
      this.requiredFieldTypes = EnumSet.of(RequiredFieldType.NUMERIC);
      return this;
    }

    /** Require string fields for this function to be applicable */
    public Builder requiresString() {
      this.requiredFieldTypes = EnumSet.of(RequiredFieldType.STRING);
      return this;
    }

    /** Require time fields for this function to be applicable */
    public Builder requiresTime() {
      this.requiredFieldTypes = EnumSet.of(RequiredFieldType.TIME);
      return this;
    }

    /** Function works with any field type (always applicable) */
    public Builder requiresAny() {
      this.requiredFieldTypes = EnumSet.of(RequiredFieldType.ANY);
      return this;
    }

    public Builder param(ParamSpec param) {
      this.params.add(param);
      return this;
    }

    public Builder positional(int index, ParamSpec.ParamType type, String description) {
      this.params.add(ParamSpec.positional(index, type, description));
      return this;
    }

    public Builder optionalPositional(int index, ParamSpec.ParamType type, String description) {
      this.params.add(ParamSpec.optionalPositional(index, type, description));
      return this;
    }

    public Builder keyword(String name, ParamSpec.ParamType type, String description) {
      this.params.add(ParamSpec.keyword(name, type, description));
      return this;
    }

    public Builder optionalKeyword(String name, ParamSpec.ParamType type, String description) {
      this.params.add(ParamSpec.optionalKeyword(name, type, description));
      return this;
    }

    public Builder multiKeyword(String name, ParamSpec.ParamType type, String description) {
      this.params.add(ParamSpec.multiKeyword(name, type, description));
      return this;
    }

    public Builder enumKeyword(String name, List<String> values, String description) {
      this.params.add(ParamSpec.enumKeyword(name, values, false, description));
      return this;
    }

    public Builder requiredEnumKeyword(String name, List<String> values, String description) {
      this.params.add(ParamSpec.enumKeyword(name, values, true, description));
      return this;
    }

    public FunctionSpec build() {
      String t = template != null ? template : name + "()";
      return new FunctionSpec(
          name, params, category, description, t, hasVarargs, requiredFieldTypes);
    }
  }
}

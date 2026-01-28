package io.jafar.shell.cli.completion;

import static io.jafar.shell.cli.completion.ParamSpec.ParamType.*;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Central registry of all functions in the JFR query language. Serves as the single source of truth
 * for function signatures, enabling:
 *
 * <ul>
 *   <li>Code completion with context-aware parameter suggestions
 *   <li>Syntax validation
 *   <li>Documentation generation
 * </ul>
 *
 * <p>Functions are organized into three categories:
 *
 * <ul>
 *   <li><b>Pipeline operators</b>: Used after | (e.g., groupBy, sum, select)
 *   <li><b>Filter functions</b>: Used in filter predicates [...] (e.g., contains, exists)
 *   <li><b>Select functions</b>: Used in select expressions (e.g., upper, substring)
 * </ul>
 */
public final class FunctionRegistry {

  private static final Map<String, FunctionSpec> FUNCTIONS = new LinkedHashMap<>();

  // Aggregation function values for groupBy
  private static final List<String> AGG_FUNCTIONS = List.of("count", "sum", "avg", "min", "max");

  static {
    // === PIPELINE OPERATORS ===
    registerPipelineOperators();
    registerTransformOperators();
    registerDecoratorOperators();

    // === FILTER FUNCTIONS ===
    registerFilterFunctions();

    // === SELECT EXPRESSION FUNCTIONS ===
    registerSelectFunctions();
  }

  private static void registerPipelineOperators() {
    // count() - Count matching events
    register(
        FunctionSpec.builder("count")
            .pipeline()
            .description("Count matching events")
            .template("count()")
            .build());

    // sum([path]) - Sum numeric values
    register(
        FunctionSpec.builder("sum")
            .pipeline()
            .description("Sum numeric values")
            .template("sum(field)")
            .optionalPositional(0, FIELD_PATH, "Field path to sum")
            .build());

    // stats([path]) - Compute statistics (min, max, avg, stddev, count)
    register(
        FunctionSpec.builder("stats")
            .pipeline()
            .description("Compute statistics (min, max, avg, stddev, count)")
            .template("stats(field)")
            .optionalPositional(0, FIELD_PATH, "Field path for statistics")
            .build());

    // quantiles(values..., [path=field]) - Calculate percentiles
    register(
        FunctionSpec.builder("quantiles")
            .pipeline()
            .description("Calculate quantiles/percentiles")
            .template("quantiles(0.5, 0.9, 0.99)")
            .varargs()
            .positional(0, NUMBER, "Quantile values (0-1), e.g., 0.5, 0.9, 0.99")
            .optionalKeyword("path", FIELD_PATH, "Field path to analyze")
            .build());

    // sketch([path]) - Generate HdrHistogram sketch
    register(
        FunctionSpec.builder("sketch")
            .pipeline()
            .description("Generate stats + p50, p90, p99 histogram")
            .template("sketch(field)")
            .optionalPositional(0, FIELD_PATH, "Field path for histogram")
            .build());

    // groupBy(key, [agg=func], [value=path]) - Group and aggregate
    register(
        FunctionSpec.builder("groupBy")
            .pipeline()
            .description("Group by field and aggregate")
            .template("groupBy(field, agg=count)")
            .positional(0, FIELD_PATH, "Grouping key field")
            .enumKeyword("agg", AGG_FUNCTIONS, "Aggregation function")
            .optionalKeyword("value", FIELD_PATH, "Value field to aggregate")
            .build());

    // top(n, [by=path], [asc=bool]) - Top N entries
    register(
        FunctionSpec.builder("top")
            .pipeline()
            .description("Return top N entries sorted by value")
            .template("top(10)")
            .positional(0, NUMBER, "Number of entries to return")
            .optionalKeyword("by", FIELD_PATH, "Sort by field")
            .optionalKeyword("asc", BOOLEAN, "Ascending order (default: false)")
            .build());

    // select(fields...) - Project fields
    register(
        FunctionSpec.builder("select")
            .pipeline()
            .description("Project specific fields with optional aliases")
            .template("select(field1, field2 as alias)")
            .varargs()
            .positional(0, EXPRESSION, "Fields or expressions to select")
            .build());

    // toMap(key, value) - Convert to map
    register(
        FunctionSpec.builder("toMap")
            .pipeline()
            .description("Convert rows to a map")
            .template("toMap(keyField, valueField)")
            .positional(0, FIELD_PATH, "Key field")
            .positional(1, FIELD_PATH, "Value field")
            .build());

    // timerange([path], [duration=path], [format=str]) - Calculate time range
    register(
        FunctionSpec.builder("timerange")
            .pipeline()
            .description("Calculate time range with wall-clock conversion")
            .template("timerange(startTime)")
            .optionalPositional(0, FIELD_PATH, "Time field (default: startTime)")
            .optionalKeyword("duration", FIELD_PATH, "Duration field")
            .optionalKeyword("format", STRING, "Date format string")
            .build());
  }

  private static void registerTransformOperators() {
    // len([path]) - String/array length
    register(
        FunctionSpec.builder("len")
            .pipeline()
            .description("Get string or array length")
            .template("len(field)")
            .optionalPositional(0, FIELD_PATH, "Field path")
            .build());

    // uppercase([path]) - Convert to uppercase
    register(
        FunctionSpec.builder("uppercase")
            .pipeline()
            .description("Convert string to uppercase")
            .template("uppercase(field)")
            .optionalPositional(0, FIELD_PATH, "String field")
            .build());

    // lowercase([path]) - Convert to lowercase
    register(
        FunctionSpec.builder("lowercase")
            .pipeline()
            .description("Convert string to lowercase")
            .template("lowercase(field)")
            .optionalPositional(0, FIELD_PATH, "String field")
            .build());

    // trim([path]) - Trim whitespace
    register(
        FunctionSpec.builder("trim")
            .pipeline()
            .description("Trim whitespace from string")
            .template("trim(field)")
            .optionalPositional(0, FIELD_PATH, "String field")
            .build());

    // abs([path]) - Absolute value
    register(
        FunctionSpec.builder("abs")
            .pipeline()
            .description("Absolute value of numeric field")
            .template("abs(field)")
            .optionalPositional(0, FIELD_PATH, "Numeric field")
            .build());

    // round([path]) - Round to integer
    register(
        FunctionSpec.builder("round")
            .pipeline()
            .description("Round numeric value to nearest integer")
            .template("round(field)")
            .optionalPositional(0, FIELD_PATH, "Numeric field")
            .build());

    // floor([path]) - Floor value
    register(
        FunctionSpec.builder("floor")
            .pipeline()
            .description("Floor of numeric value")
            .template("floor(field)")
            .optionalPositional(0, FIELD_PATH, "Numeric field")
            .build());

    // ceil([path]) - Ceiling value
    register(
        FunctionSpec.builder("ceil")
            .pipeline()
            .description("Ceiling of numeric value")
            .template("ceil(field)")
            .optionalPositional(0, FIELD_PATH, "Numeric field")
            .build());

    // contains([path], substring) - Check substring (pipeline version)
    register(
        FunctionSpec.builder("contains")
            .pipeline()
            .description("Check if string contains substring")
            .template("contains(field, \"text\")")
            .optionalPositional(0, FIELD_PATH, "String field")
            .positional(1, STRING, "Substring to find")
            .build());

    // replace([path], target, replacement) - String replace
    register(
        FunctionSpec.builder("replace")
            .pipeline()
            .description("Replace occurrences in string")
            .template("replace(field, \"old\", \"new\")")
            .optionalPositional(0, FIELD_PATH, "String field")
            .positional(1, STRING, "Target substring")
            .positional(2, STRING, "Replacement string")
            .build());
  }

  private static void registerDecoratorOperators() {
    // decorateByTime(eventType, [fields=...], [threadPath=...], [decoratorThreadPath=...])
    register(
        FunctionSpec.builder("decorateByTime")
            .pipeline()
            .description("Join events that overlap temporally on same thread")
            .template("decorateByTime(EventType, fields=field1,field2)")
            .positional(0, EVENT_TYPE, "Decorator event type")
            .multiKeyword("fields", FIELD_PATH, "Fields to include from decorator")
            .optionalKeyword("threadPath", FIELD_PATH, "Thread path in main event")
            .optionalKeyword("decoratorThreadPath", FIELD_PATH, "Thread path in decorator event")
            .build());

    // decorateByKey(eventType, key=..., decoratorKey=..., [fields=...])
    register(
        FunctionSpec.builder("decorateByKey")
            .pipeline()
            .description("Join events by matching correlation keys")
            .template("decorateByKey(EventType, key=field, decoratorKey=field)")
            .positional(0, EVENT_TYPE, "Decorator event type")
            .keyword("key", FIELD_PATH, "Key path in main event")
            .keyword("decoratorKey", FIELD_PATH, "Key path in decorator event")
            .multiKeyword("fields", FIELD_PATH, "Fields to include from decorator")
            .build());
  }

  private static void registerFilterFunctions() {
    // contains(field, substring) - String contains check
    register(
        FunctionSpec.builder("contains")
            .filter()
            .description("Check if field contains substring")
            .template("contains(field, \"text\")")
            .positional(0, FIELD_PATH, "Field to check")
            .positional(1, STRING, "Substring to find")
            .build());

    // exists(field) - Field existence check
    register(
        FunctionSpec.builder("exists")
            .filter()
            .description("Check if field exists and is not null")
            .template("exists(field)")
            .positional(0, FIELD_PATH, "Field to check")
            .build());

    // empty(field) - Field is empty
    register(
        FunctionSpec.builder("empty")
            .filter()
            .description("Check if field is empty or null")
            .template("empty(field)")
            .positional(0, FIELD_PATH, "Field to check")
            .build());

    // between(field, min, max) - Range check
    register(
        FunctionSpec.builder("between")
            .filter()
            .description("Check if value is between min and max (inclusive)")
            .template("between(field, 0, 100)")
            .positional(0, FIELD_PATH, "Field to check")
            .positional(1, NUMBER, "Minimum value")
            .positional(2, NUMBER, "Maximum value")
            .build());

    // len(field) - Length for comparison
    register(
        FunctionSpec.builder("len")
            .filter()
            .description("Get length of string or array for comparison")
            .template("len(field) > 10")
            .positional(0, FIELD_PATH, "Field to measure")
            .build());

    // matches(field, regex, [flags]) - Regex match
    register(
        FunctionSpec.builder("matches")
            .filter()
            .description("Check if field matches regex pattern")
            .template("matches(field, \"pattern\")")
            .positional(0, FIELD_PATH, "Field to match")
            .positional(1, STRING, "Regex pattern")
            .optionalPositional(2, STRING, "Regex flags (e.g., \"i\" for case-insensitive)")
            .build());

    // startsWith(field, prefix) - Prefix check
    register(
        FunctionSpec.builder("startsWith")
            .filter()
            .description("Check if field starts with prefix")
            .template("startsWith(field, \"prefix\")")
            .positional(0, FIELD_PATH, "Field to check")
            .positional(1, STRING, "Prefix to match")
            .build());

    // endsWith(field, suffix) - Suffix check
    register(
        FunctionSpec.builder("endsWith")
            .filter()
            .description("Check if field ends with suffix")
            .template("endsWith(field, \"suffix\")")
            .positional(0, FIELD_PATH, "Field to check")
            .positional(1, STRING, "Suffix to match")
            .build());
  }

  private static void registerSelectFunctions() {
    // if(condition, trueValue, falseValue) - Conditional
    register(
        FunctionSpec.builder("if")
            .select()
            .description("Conditional expression")
            .template("if(condition, trueValue, falseValue)")
            .positional(0, EXPRESSION, "Condition expression")
            .positional(1, EXPRESSION, "Value if true")
            .positional(2, EXPRESSION, "Value if false")
            .build());

    // upper(string) - Uppercase
    register(
        FunctionSpec.builder("upper")
            .select()
            .description("Convert to uppercase")
            .template("upper(field)")
            .positional(0, FIELD_PATH, "String field")
            .build());

    // lower(string) - Lowercase
    register(
        FunctionSpec.builder("lower")
            .select()
            .description("Convert to lowercase")
            .template("lower(field)")
            .positional(0, FIELD_PATH, "String field")
            .build());

    // substring(string, start, [length]) - Extract substring
    register(
        FunctionSpec.builder("substring")
            .select()
            .description("Extract substring")
            .template("substring(field, 0, 10)")
            .positional(0, FIELD_PATH, "String field")
            .positional(1, NUMBER, "Start index")
            .optionalPositional(2, NUMBER, "Length (optional)")
            .build());

    // length(string) - String length
    register(
        FunctionSpec.builder("length")
            .select()
            .description("Get string length")
            .template("length(field)")
            .positional(0, FIELD_PATH, "String field")
            .build());

    // coalesce(value1, value2, ...) - First non-null
    register(
        FunctionSpec.builder("coalesce")
            .select()
            .description("Return first non-null value")
            .template("coalesce(field1, field2, \"default\")")
            .varargs()
            .positional(0, EXPRESSION, "Values to check")
            .build());
  }

  private static void register(FunctionSpec spec) {
    // For filter/select functions, use category prefix to avoid collisions
    String key = spec.name().toLowerCase();
    if (spec.isFilter()) {
      key = "filter:" + key;
    } else if (spec.isSelect()) {
      key = "select:" + key;
    }
    FUNCTIONS.put(key, spec);
  }

  // === PUBLIC API ===

  /** Get a pipeline operator by name */
  public static FunctionSpec getPipelineOperator(String name) {
    return FUNCTIONS.get(name.toLowerCase());
  }

  /** Get a filter function by name */
  public static FunctionSpec getFilterFunction(String name) {
    FunctionSpec spec = FUNCTIONS.get("filter:" + name.toLowerCase());
    if (spec == null) {
      // Some functions exist in multiple categories (e.g., len, contains)
      spec = FUNCTIONS.get(name.toLowerCase());
      if (spec != null && !spec.isFilter()) {
        // Found but not a filter - check if there's a filter version
        return null;
      }
    }
    return spec;
  }

  /** Get a select expression function by name */
  public static FunctionSpec getSelectFunction(String name) {
    FunctionSpec spec = FUNCTIONS.get("select:" + name.toLowerCase());
    if (spec == null) {
      // Some functions may be shared
      spec = FUNCTIONS.get(name.toLowerCase());
      if (spec != null && !spec.isSelect()) {
        return null;
      }
    }
    return spec;
  }

  /** Get all pipeline operators */
  public static Collection<FunctionSpec> getPipelineOperators() {
    return FUNCTIONS.values().stream()
        .filter(FunctionSpec::isPipeline)
        .collect(Collectors.toList());
  }

  /** Get all filter functions */
  public static Collection<FunctionSpec> getFilterFunctions() {
    return FUNCTIONS.values().stream().filter(FunctionSpec::isFilter).collect(Collectors.toList());
  }

  /** Get all select expression functions */
  public static Collection<FunctionSpec> getSelectFunctions() {
    return FUNCTIONS.values().stream().filter(FunctionSpec::isSelect).collect(Collectors.toList());
  }

  /** Get all registered functions */
  public static Collection<FunctionSpec> getAllFunctions() {
    return Collections.unmodifiableCollection(FUNCTIONS.values());
  }

  /** Check if a function exists (any category) */
  public static boolean exists(String name) {
    String lower = name.toLowerCase();
    return FUNCTIONS.containsKey(lower)
        || FUNCTIONS.containsKey("filter:" + lower)
        || FUNCTIONS.containsKey("select:" + lower);
  }

  /** Get function by name, searching all categories */
  public static FunctionSpec get(String name, FunctionSpec.FunctionCategory category) {
    return switch (category) {
      case PIPELINE -> getPipelineOperator(name);
      case FILTER -> getFilterFunction(name);
      case SELECT -> getSelectFunction(name);
    };
  }

  private FunctionRegistry() {
    // Prevent instantiation
  }
}

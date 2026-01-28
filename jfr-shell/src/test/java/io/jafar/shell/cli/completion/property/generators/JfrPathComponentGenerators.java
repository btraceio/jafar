package io.jafar.shell.cli.completion.property.generators;

import io.jafar.shell.cli.completion.MetadataService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;

/**
 * Atomic generators for individual JfrPath components.
 *
 * <p>These generators create the building blocks for JfrPath expressions: roots, event types, field
 * names, operators, functions, and values. They use real metadata from the test JFR file to ensure
 * generated expressions are valid.
 */
public class JfrPathComponentGenerators {

  // ==================== Root Generators ====================

  /**
   * Generates valid JfrPath root types.
   *
   * @return arbitrary generating "events", "metadata", "cp", or "chunks"
   */
  public static Arbitrary<String> roots() {
    return Arbitraries.of("events", "metadata", "cp", "chunks");
  }

  // ==================== Event Type Generators ====================

  /**
   * Generates event type names from actual JFR metadata.
   *
   * @param metadata the metadata service with loaded JFR file
   * @return arbitrary generating real event type names
   */
  public static Arbitrary<String> eventTypes(MetadataService metadata) {
    Set<String> types = metadata.getEventTypes();
    if (types.isEmpty()) {
      // Fallback to common types if no metadata available
      return Arbitraries.of(
          "jdk.ExecutionSample",
          "jdk.JavaMonitorEnter",
          "jdk.ObjectAllocationSample",
          "jdk.ThreadPark");
    }
    return Arbitraries.of(types);
  }

  /**
   * Generates metadata type names from actual JFR metadata.
   *
   * @param metadata the metadata service with loaded JFR file
   * @return arbitrary generating real metadata type names
   */
  public static Arbitrary<String> metadataTypes(MetadataService metadata) {
    Set<String> types = metadata.getAllMetadataTypes();
    if (types.isEmpty()) {
      // Fallback to common types
      return Arbitraries.of("Class", "Method", "Thread", "StackTrace");
    }
    return Arbitraries.of(types);
  }

  /**
   * Generates constant pool type names from actual JFR metadata.
   *
   * @param metadata the metadata service with loaded JFR file
   * @return arbitrary generating real constant pool type names
   */
  public static Arbitrary<String> constantPoolTypes(MetadataService metadata) {
    Set<String> types = metadata.getConstantPoolTypes();
    if (types.isEmpty()) {
      return Arbitraries.of("Class", "Package", "Module");
    }
    return Arbitraries.of(types);
  }

  // ==================== Field Generators ====================

  /**
   * Generates field names for a given event type.
   *
   * @param eventType the event type name
   * @param metadata the metadata service
   * @return arbitrary generating field names for the event type
   */
  public static Arbitrary<String> fieldName(String eventType, MetadataService metadata) {
    List<String> fields = metadata.getFieldNames(eventType);
    if (fields.isEmpty()) {
      // Return a minimal fallback
      return Arbitraries.of("startTime", "duration", "eventThread");
    }
    return Arbitraries.of(fields);
  }

  /**
   * Generates nested field paths up to a maximum depth.
   *
   * @param eventType the root event type
   * @param maxDepth maximum nesting depth (1-10)
   * @param metadata the metadata service
   * @return arbitrary generating nested field paths
   */
  public static Arbitrary<List<String>> nestedFieldPath(
      String eventType, int maxDepth, MetadataService metadata) {

    if (maxDepth < 1) {
      return Arbitraries.just(Collections.emptyList());
    }

    // Start with root field names
    List<String> rootFields = metadata.getFieldNames(eventType);
    if (rootFields.isEmpty()) {
      return Arbitraries.just(Collections.emptyList());
    }

    // Build path recursively
    return Arbitraries.recursive(
        () -> Arbitraries.just(Collections.<String>emptyList()),
        pathArbitrary ->
            pathArbitrary.flatMap(
                currentPath -> {
                  // Determine current type
                  String currentType = eventType;
                  for (String segment : currentPath) {
                    String fieldType = metadata.getFieldType(currentType, segment);
                    if (fieldType == null) {
                      return Arbitraries.just(currentPath);
                    }
                    currentType = fieldType;
                  }

                  // Get fields at current level
                  List<String> fields = metadata.getFieldNames(currentType);
                  if (fields.isEmpty()) {
                    return Arbitraries.just(currentPath);
                  }

                  // Add one more field to path
                  return Arbitraries.of(fields)
                      .map(
                          field -> {
                            List<String> newPath = new ArrayList<>(currentPath);
                            newPath.add(field);
                            return newPath;
                          });
                }),
        maxDepth);
  }

  // ==================== Operator Generators ====================

  /**
   * Generates comparison operators.
   *
   * @return arbitrary generating "==", "!=", ">", ">=", "<", "<=", "~"
   */
  public static Arbitrary<String> comparisonOperator() {
    return Arbitraries.of("==", "!=", ">", ">=", "<", "<=", "~");
  }

  /**
   * Generates logical operators for filters.
   *
   * @return arbitrary generating "&&" or "||"
   */
  public static Arbitrary<String> logicalOperator() {
    return Arbitraries.of("&&", "||");
  }

  // ==================== Function Generators ====================

  /**
   * Generates filter function names (used in filter predicates).
   *
   * @return arbitrary generating filter function names
   */
  public static Arbitrary<String> filterFunction() {
    return Arbitraries.of("contains", "starts_with", "ends_with", "matches", "exists", "empty");
  }

  /**
   * Generates aggregation function names (used in pipelines).
   *
   * @return arbitrary generating aggregation function names
   */
  public static Arbitrary<String> aggregationFunction() {
    return Arbitraries.of(
        "count", "sum", "stats", "quantiles", "groupBy", "top", "select", "sketch");
  }

  /**
   * Generates transform function names (used in pipelines).
   *
   * @return arbitrary generating transform function names
   */
  public static Arbitrary<String> transformFunction() {
    return Arbitraries.of(
        "len",
        "uppercase",
        "lowercase",
        "trim",
        "abs",
        "round",
        "floor",
        "ceil",
        "contains",
        "replace");
  }

  /**
   * Generates decorator function names (used in pipelines).
   *
   * @return arbitrary generating "decorateByTime" or "decorateByKey"
   */
  public static Arbitrary<String> decoratorFunction() {
    return Arbitraries.of("decorateByTime", "decorateByKey");
  }

  /**
   * Generates all pipeline function names (aggregation + transform + decorator).
   *
   * @return arbitrary generating any pipeline function name
   */
  public static Arbitrary<String> pipelineFunction() {
    return Arbitraries.oneOf(aggregationFunction(), transformFunction(), decoratorFunction());
  }

  // ==================== Value Generators ====================

  /**
   * Generates string literals (with surrounding quotes).
   *
   * @return arbitrary generating quoted string literals
   */
  public static Arbitrary<String> stringLiteral() {
    return Arbitraries.strings()
        .alpha()
        .numeric()
        .ofMinLength(1)
        .ofMaxLength(20)
        .map(s -> "\"" + s + "\"");
  }

  /**
   * Generates numeric literals (integers).
   *
   * @return arbitrary generating numeric strings
   */
  public static Arbitrary<String> numericLiteral() {
    return Arbitraries.integers().between(-1000, 1000).map(String::valueOf);
  }

  /**
   * Generates floating-point literals.
   *
   * @return arbitrary generating floating-point numeric strings
   */
  public static Arbitrary<String> floatLiteral() {
    return Arbitraries.doubles().between(-1000.0, 1000.0).map(d -> String.format("%.2f", d));
  }

  /**
   * Generates boolean literals.
   *
   * @return arbitrary generating "true" or "false"
   */
  public static Arbitrary<String> booleanLiteral() {
    return Arbitraries.of("true", "false");
  }

  /**
   * Generates any literal value (string, numeric, float, or boolean).
   *
   * @return arbitrary generating any kind of literal
   */
  public static Arbitrary<String> anyLiteral() {
    return Arbitraries.oneOf(stringLiteral(), numericLiteral(), floatLiteral(), booleanLiteral());
  }

  // ==================== List Match Prefix Generators ====================

  /**
   * Generates list match prefixes for filter operations.
   *
   * @return arbitrary generating "any:", "all:", or "none:"
   */
  public static Arbitrary<String> listMatchPrefix() {
    return Arbitraries.of("any:", "all:", "none:");
  }

  // ==================== Chunk and Session Generators ====================

  /**
   * Generates chunk IDs from actual JFR metadata.
   *
   * @param metadata the metadata service
   * @return arbitrary generating valid chunk IDs
   */
  public static Arbitrary<Integer> chunkId(MetadataService metadata) {
    List<Integer> ids = metadata.getChunkIds();
    if (ids.isEmpty()) {
      // Fallback to typical chunk IDs
      return Arbitraries.of(0, 1, 2);
    }
    return Arbitraries.of(ids);
  }

  // ==================== Option Generators ====================

  /**
   * Generates command option names.
   *
   * @return arbitrary generating command line option names
   */
  public static Arbitrary<String> commandOption() {
    return Arbitraries.of("--format", "--limit", "--list-match", "--json");
  }

  /**
   * Generates format option values.
   *
   * @return arbitrary generating "table" or "json"
   */
  public static Arbitrary<String> formatValue() {
    return Arbitraries.of("table", "json");
  }

  // ==================== Helper Methods ====================

  /**
   * Generates a comma-separated list of field names.
   *
   * @param eventType the event type
   * @param minFields minimum number of fields (1+)
   * @param maxFields maximum number of fields
   * @param metadata the metadata service
   * @return arbitrary generating comma-separated field lists
   */
  public static Arbitrary<String> fieldList(
      String eventType, int minFields, int maxFields, MetadataService metadata) {
    return Arbitraries.integers()
        .between(minFields, maxFields)
        .flatMap(
            count ->
                Arbitraries.of(metadata.getFieldNames(eventType))
                    .list()
                    .ofMinSize(count)
                    .ofMaxSize(count)
                    .map(fields -> String.join(",", fields)));
  }

  /**
   * Generates parameter syntax for decorator functions.
   *
   * @param metadata the metadata service
   * @return arbitrary generating decorator parameter strings
   */
  public static Arbitrary<String> decoratorParams(MetadataService metadata) {
    return Arbitraries.oneOf(
        // decorateByTime(eventType, fields=...)
        eventTypes(metadata)
            .flatMap(
                type ->
                    fieldList(type, 1, 3, metadata)
                        .map(fields -> "\"" + type + "\", fields=" + fields)),
        // decorateByKey(eventType, key=, decoratorKey=, fields=...)
        eventTypes(metadata)
            .flatMap(
                type ->
                    Arbitraries.of(metadata.getFieldNames(type))
                        .flatMap(
                            key ->
                                Arbitraries.of(metadata.getFieldNames(type))
                                    .flatMap(
                                        decorKey ->
                                            fieldList(type, 1, 3, metadata)
                                                .map(
                                                    fields ->
                                                        "\""
                                                            + type
                                                            + "\", key="
                                                            + key
                                                            + ", decoratorKey="
                                                            + decorKey
                                                            + ", fields="
                                                            + fields)))));
  }
}

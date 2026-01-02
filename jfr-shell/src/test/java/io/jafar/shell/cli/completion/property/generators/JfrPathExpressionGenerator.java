package io.jafar.shell.cli.completion.property.generators;

import io.jafar.shell.cli.completion.MetadataService;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Tuple;

/**
 * Composite generator for complete JfrPath expressions.
 *
 * <p>This class builds valid and invalid JfrPath expressions by composing atomic generators. It
 * uses a bottom-up approach: roots → paths → filters → pipelines, with probabilistic branching to
 * create diverse test cases.
 *
 * <p>The generator produces expressions suitable for completion testing, including both complete
 * and partial expressions at various stages of construction.
 */
public class JfrPathExpressionGenerator {

  private final MetadataService metadata;
  private final JfrPathComponentGenerators components;

  public JfrPathExpressionGenerator(MetadataService metadata) {
    this.metadata = metadata;
    this.components = new JfrPathComponentGenerators();
  }

  // ==================== Main Generators ====================

  /**
   * Generates valid JfrPath expressions.
   *
   * <p>Produces expressions like:
   * - "events/jdk.ExecutionSample"
   * - "events/jdk.ExecutionSample/sampledThread"
   * - "events/jdk.ExecutionSample[startTime > 0]"
   * - "events/jdk.ExecutionSample | count()"
   * - "metadata/Class/fields"
   *
   * @return arbitrary generating valid JfrPath expressions
   */
  public Arbitrary<String> validJfrPathExpression() {
    return rootExpression()
        .flatMap(this::optionallyAddFilter)
        .flatMap(this::optionallyAddPipeline);
  }

  /**
   * Generates simple valid expressions (no filters or pipelines).
   *
   * @return arbitrary generating simple path expressions
   */
  public Arbitrary<String> simpleExpression() {
    return rootExpression();
  }

  /**
   * Generates expressions with filters but no pipelines.
   *
   * @return arbitrary generating filtered expressions
   */
  public Arbitrary<String> filteredExpression() {
    return rootExpression().flatMap(this::optionallyAddFilter);
  }

  // ==================== Root Expression Generators ====================

  /**
   * Generates root-level expressions (events, metadata, cp, chunks).
   *
   * @return arbitrary generating root expressions
   */
  private Arbitrary<String> rootExpression() {
    return Arbitraries.oneOf(
        eventsExpression(),
        eventsExpression(),  // 2x weight
        eventsExpression(),  // 3x weight
        eventsExpression(),  // 4x weight
        eventsExpression(),  // 5x weight
        metadataExpression(),
        metadataExpression(),  // 2x weight
        cpExpression(),
        chunksExpression());
  }

  /**
   * Generates events expressions with optional field paths.
   *
   * <p>Examples:
   * - "events/jdk.ExecutionSample"
   * - "events/jdk.ExecutionSample/sampledThread"
   * - "events/jdk.ExecutionSample/sampledThread/javaThreadId"
   *
   * @return arbitrary generating events expressions
   */
  private Arbitrary<String> eventsExpression() {
    return JfrPathComponentGenerators.eventTypes(metadata)
        .flatMap(
            type ->
                Arbitraries.oneOf(
                    // 50%: Just event type
                    Arbitraries.just("events/" + type),
                    Arbitraries.just("events/" + type),  // 2x
                    Arbitraries.just("events/" + type),  // 3x
                    Arbitraries.just("events/" + type),  // 4x
                    Arbitraries.just("events/" + type),  // 5x
                    // 30%: Event type + single field
                    JfrPathComponentGenerators.fieldName(type, metadata)
                        .map(field -> "events/" + type + "/" + field),
                    JfrPathComponentGenerators.fieldName(type, metadata)
                        .map(field -> "events/" + type + "/" + field),  // 2x
                    JfrPathComponentGenerators.fieldName(type, metadata)
                        .map(field -> "events/" + type + "/" + field),  // 3x
                    // 20%: Event type + nested path
                    JfrPathComponentGenerators.nestedFieldPath(type, 3, metadata)
                        .map(
                            path -> {
                              if (path.isEmpty()) {
                                return "events/" + type;
                              }
                              return "events/" + type + "/" + String.join("/", path);
                            }),
                    JfrPathComponentGenerators.nestedFieldPath(type, 3, metadata)
                        .map(
                            path -> {
                              if (path.isEmpty()) {
                                return "events/" + type;
                              }
                              return "events/" + type + "/" + String.join("/", path);
                            })));  // 2x
  }

  /**
   * Generates metadata expressions.
   *
   * <p>Examples:
   * - "metadata/Class"
   * - "metadata/Class/fields"
   * - "metadata/Method/fields"
   *
   * @return arbitrary generating metadata expressions
   */
  private Arbitrary<String> metadataExpression() {
    return JfrPathComponentGenerators.metadataTypes(metadata)
        .flatMap(
            type ->
                Arbitraries.oneOf(
                    Arbitraries.just("metadata/" + type),
                    Arbitraries.just("metadata/" + type),  // 2x
                    Arbitraries.just("metadata/" + type),  // 3x
                    Arbitraries.just("metadata/" + type + "/fields"),
                    Arbitraries.just("metadata/" + type + "/fields")));  // 2x
  }

  /**
   * Generates constant pool expressions.
   *
   * <p>Examples:
   * - "cp/Class"
   * - "cp/Package/name"
   *
   * @return arbitrary generating cp expressions
   */
  private Arbitrary<String> cpExpression() {
    return JfrPathComponentGenerators.constantPoolTypes(metadata)
        .flatMap(
            type ->
                Arbitraries.oneOf(
                    Arbitraries.just("cp/" + type),
                    Arbitraries.just("cp/" + type),  // 2x
                    Arbitraries.just("cp/" + type),  // 3x
                    JfrPathComponentGenerators.fieldName(type, metadata)
                        .map(field -> "cp/" + type + "/" + field),
                    JfrPathComponentGenerators.fieldName(type, metadata)
                        .map(field -> "cp/" + type + "/" + field)));  // 2x
  }

  /**
   * Generates chunks expressions.
   *
   * <p>Examples:
   * - "chunks"
   * - "chunks/0"
   * - "chunks/1"
   *
   * @return arbitrary generating chunks expressions
   */
  private Arbitrary<String> chunksExpression() {
    return Arbitraries.oneOf(
        Arbitraries.just("chunks"),
        Arbitraries.just("chunks"),  // 2x weight
        JfrPathComponentGenerators.chunkId(metadata).map(id -> "chunks/" + id),
        JfrPathComponentGenerators.chunkId(metadata).map(id -> "chunks/" + id),  // 2x weight
        JfrPathComponentGenerators.chunkId(metadata).map(id -> "chunks/" + id)); // 3x weight
  }

  // ==================== Filter Generators ====================

  /**
   * Optionally adds a filter to an expression.
   *
   * @param expr the base expression
   * @return arbitrary that may add a filter
   */
  private Arbitrary<String> optionallyAddFilter(String expr) {
    // Only add filters to events expressions
    if (!expr.startsWith("events/")) {
      return Arbitraries.just(expr);
    }

    return Arbitraries.oneOf(
        simpleFilter(expr),
        simpleFilter(expr),  // 2x
        simpleFilter(expr),  // 3x
        simpleFilter(expr),  // 4x
        complexFilter(expr),
        complexFilter(expr),  // 2x
        Arbitraries.just(expr),  // no filter
        Arbitraries.just(expr),  // 2x
        Arbitraries.just(expr),  // 3x
        Arbitraries.just(expr)); // 4x
  }

  /**
   * Generates a simple filter (single condition).
   *
   * <p>Example: "events/jdk.ExecutionSample[startTime > 0]"
   *
   * @param expr the base expression
   * @return arbitrary adding a simple filter
   */
  private Arbitrary<String> simpleFilter(String expr) {
    String eventType = extractEventType(expr);
    if (eventType == null) {
      return Arbitraries.just(expr);
    }

    return filterCondition(eventType).map(condition -> expr + "[" + condition + "]");
  }

  /**
   * Generates a complex filter (multiple conditions with logical operators).
   *
   * <p>Example: "events/jdk.ExecutionSample[startTime > 0 && duration < 1000]"
   *
   * @param expr the base expression
   * @return arbitrary adding a complex filter
   */
  private Arbitrary<String> complexFilter(String expr) {
    String eventType = extractEventType(expr);
    if (eventType == null) {
      return Arbitraries.just(expr);
    }

    return Combinators.combine(
            filterCondition(eventType),
            JfrPathComponentGenerators.logicalOperator(),
            filterCondition(eventType))
        .as((c1, op, c2) -> expr + "[" + c1 + " " + op + " " + c2 + "]");
  }

  /**
   * Generates a filter condition (field op value).
   *
   * @param eventType the event type name
   * @return arbitrary generating filter conditions
   */
  private Arbitrary<String> filterCondition(String eventType) {
    return Combinators.combine(
            JfrPathComponentGenerators.fieldName(eventType, metadata),
            JfrPathComponentGenerators.comparisonOperator(),
            JfrPathComponentGenerators.anyLiteral())
        .as((field, op, val) -> field + " " + op + " " + val);
  }

  // ==================== Pipeline Generators ====================

  /**
   * Optionally adds a pipeline operator to an expression.
   *
   * @param expr the base expression
   * @return arbitrary that may add a pipeline
   */
  private Arbitrary<String> optionallyAddPipeline(String expr) {
    return Arbitraries.oneOf(
        pipelineOperator(expr),
        pipelineOperator(expr),  // 2x
        pipelineOperator(expr),  // 3x
        Arbitraries.just(expr),  // no pipeline
        Arbitraries.just(expr),  // 2x
        Arbitraries.just(expr),  // 3x
        Arbitraries.just(expr),  // 4x
        Arbitraries.just(expr),  // 5x
        Arbitraries.just(expr),  // 6x
        Arbitraries.just(expr)); // 7x
  }

  /**
   * Generates a pipeline operator.
   *
   * @param expr the base expression
   * @return arbitrary adding a pipeline operator
   */
  private Arbitrary<String> pipelineOperator(String expr) {
    String eventType = extractEventType(expr);

    // Choose operator type
    return Arbitraries.oneOf(
        Arbitraries.just(expr + " | count()"),
        Arbitraries.just(expr + " | count()"),  // 2x
        Arbitraries.just(expr + " | count()"),  // 3x
        sumOperator(expr, eventType),
        sumOperator(expr, eventType),  // 2x
        groupByOperator(expr, eventType),
        groupByOperator(expr, eventType),  // 2x
        topOperator(expr, eventType),
        selectOperator(expr, eventType),
        transformOperator(expr),
        decoratorOperator(expr, eventType));
  }

  /**
   * Generates a sum operator.
   *
   * <p>Example: "events/jdk.ExecutionSample | sum(duration)"
   *
   * @param expr the base expression
   * @param eventType the event type (may be null)
   * @return arbitrary generating sum operator
   */
  private Arbitrary<String> sumOperator(String expr, String eventType) {
    if (eventType == null) {
      return Arbitraries.just(expr + " | sum()");
    }

    return JfrPathComponentGenerators.fieldName(eventType, metadata)
        .map(field -> expr + " | sum(" + field + ")");
  }

  /**
   * Generates a groupBy operator.
   *
   * <p>Example: "events/jdk.ExecutionSample | groupBy(sampledThread)"
   *
   * @param expr the base expression
   * @param eventType the event type (may be null)
   * @return arbitrary generating groupBy operator
   */
  private Arbitrary<String> groupByOperator(String expr, String eventType) {
    if (eventType == null) {
      return Arbitraries.just(expr + " | groupBy()");
    }

    return JfrPathComponentGenerators.fieldName(eventType, metadata)
        .map(field -> expr + " | groupBy(" + field + ")");
  }

  /**
   * Generates a top operator.
   *
   * <p>Example: "events/jdk.ExecutionSample | top(10, sampledThread)"
   *
   * @param expr the base expression
   * @param eventType the event type (may be null)
   * @return arbitrary generating top operator
   */
  private Arbitrary<String> topOperator(String expr, String eventType) {
    if (eventType == null) {
      return Arbitraries.just(expr + " | top(10)");
    }

    return Combinators.combine(
            Arbitraries.integers().between(5, 100),
            JfrPathComponentGenerators.fieldName(eventType, metadata))
        .as((n, field) -> expr + " | top(" + n + ", " + field + ")");
  }

  /**
   * Generates a select operator.
   *
   * <p>Example: "events/jdk.ExecutionSample | select(sampledThread, startTime)"
   *
   * @param expr the base expression
   * @param eventType the event type (may be null)
   * @return arbitrary generating select operator
   */
  private Arbitrary<String> selectOperator(String expr, String eventType) {
    if (eventType == null) {
      return Arbitraries.just(expr + " | select()");
    }

    return JfrPathComponentGenerators.fieldList(eventType, 1, 3, metadata)
        .map(fields -> expr + " | select(" + fields + ")");
  }

  /**
   * Generates a transform operator (uppercase, lowercase, etc.).
   *
   * <p>Example: "events/jdk.ExecutionSample | uppercase()"
   *
   * @param expr the base expression
   * @return arbitrary generating transform operator
   */
  private Arbitrary<String> transformOperator(String expr) {
    return JfrPathComponentGenerators.transformFunction()
        .map(fn -> expr + " | " + fn + "()");
  }

  /**
   * Generates a decorator operator (decorateByTime or decorateByKey).
   *
   * <p>Examples:
   * - "events/jdk.ExecutionSample | decorateByTime("jdk.JavaMonitorEnter", fields=address)"
   * - "events/jdk.ExecutionSample | decorateByKey("jdk.JavaMonitorEnter", key=eventThread,
   * decoratorKey=eventThread, fields=address)"
   *
   * @param expr the base expression
   * @param eventType the event type (may be null)
   * @return arbitrary generating decorator operator
   */
  private Arbitrary<String> decoratorOperator(String expr, String eventType) {
    return JfrPathComponentGenerators.decoratorFunction()
        .flatMap(
            func -> {
              if (func.equals("decorateByTime")) {
                return decorateByTimeOperator(expr);
              } else {
                return decorateByKeyOperator(expr);
              }
            });
  }

  /**
   * Generates a decorateByTime operator.
   *
   * @param expr the base expression
   * @return arbitrary generating decorateByTime
   */
  private Arbitrary<String> decorateByTimeOperator(String expr) {
    return Combinators.combine(
            JfrPathComponentGenerators.eventTypes(metadata),
            JfrPathComponentGenerators.eventTypes(metadata)
                .flatMap(
                    type -> JfrPathComponentGenerators.fieldList(type, 1, 2, metadata)))
        .as(
            (decoratorType, fields) ->
                expr
                    + " | decorateByTime(\""
                    + decoratorType
                    + "\", fields="
                    + fields
                    + ")");
  }

  /**
   * Generates a decorateByKey operator.
   *
   * @param expr the base expression
   * @return arbitrary generating decorateByKey
   */
  private Arbitrary<String> decorateByKeyOperator(String expr) {
    return JfrPathComponentGenerators.eventTypes(metadata)
        .flatMap(
            decoratorType -> {
              List<String> fields = metadata.getFieldNames(decoratorType);
              if (fields.isEmpty()) {
                return Arbitraries.just(
                    expr + " | decorateByKey(\"" + decoratorType + "\", key=id, decoratorKey=id, fields=name)");
              }

              return Combinators.combine(
                      Arbitraries.of(fields),
                      Arbitraries.of(fields),
                      JfrPathComponentGenerators.fieldList(decoratorType, 1, 2, metadata))
                  .as(
                      (key, decorKey, fieldList) ->
                          expr
                              + " | decorateByKey(\""
                              + decoratorType
                              + "\", key="
                              + key
                              + ", decoratorKey="
                              + decorKey
                              + ", fields="
                              + fieldList
                              + ")");
            });
  }

  // ==================== Invalid Expression Generators ====================

  /**
   * Generates invalid JfrPath expressions for negative testing.
   *
   * @return arbitrary generating invalid expressions
   */
  public Arbitrary<String> invalidJfrPathExpression() {
    return Arbitraries.oneOf(
        invalidRoot(),
        invalidEventType(),
        invalidFilter(),
        invalidPipeline(),
        incompleteExpression());
  }

  /** Generates invalid root expressions. */
  private Arbitrary<String> invalidRoot() {
    return Arbitraries.of(
        "evnts/Type", // typo
        "event/Type", // missing 's'
        "Events/Type", // wrong case
        "eventsType", // missing slash
        "events Type"); // space instead of slash
  }

  /** Generates expressions with invalid event types. */
  private Arbitrary<String> invalidEventType() {
    return Arbitraries.of(
        "events/", // missing type
        "events/NonExistent.Type",
        "events/jdk.ExecutionSample123",
        "events/jdk.");
  }

  /** Generates expressions with invalid filters. */
  private Arbitrary<String> invalidFilter() {
    return JfrPathComponentGenerators.eventTypes(metadata)
        .flatMap(
            type ->
                Arbitraries.of(
                    "events/" + type + "[", // unclosed
                    "events/" + type + "[field", // incomplete
                    "events/" + type + "[field >", // missing value
                    "events/" + type + "[field > value &&", // incomplete logical
                    "events/" + type + "[]")); // empty filter
  }

  /** Generates expressions with invalid pipeline operators. */
  private Arbitrary<String> invalidPipeline() {
    return JfrPathComponentGenerators.eventTypes(metadata)
        .flatMap(
            type ->
                Arbitraries.of(
                    "events/" + type + " |", // missing function
                    "events/" + type + " | ", // missing function
                    "events/" + type + " | count(", // unclosed paren
                    "events/" + type + " | invalidFunc()",
                    "events/" + type + " ||")); // double pipe
  }

  /** Generates incomplete expressions (valid prefixes). */
  private Arbitrary<String> incompleteExpression() {
    return Arbitraries.of(
        "eve", // partial root
        "events/jdk.Exec", // partial event type
        "metadata/Cla", // partial metadata type
        "events/jdk.ExecutionSample/sampled"); // partial field
  }

  // ==================== Helper Methods ====================

  /**
   * Extracts the event type from an expression.
   *
   * @param expr the expression
   * @return the event type or null if not found
   */
  private String extractEventType(String expr) {
    if (!expr.startsWith("events/")) {
      return null;
    }

    // Remove "events/" prefix
    String rest = expr.substring(7);

    // Find next slash, bracket, or pipe
    int slashIdx = rest.indexOf('/');
    int bracketIdx = rest.indexOf('[');
    int pipeIdx = rest.indexOf('|');

    int endIdx = rest.length();
    if (slashIdx != -1) {
      endIdx = Math.min(endIdx, slashIdx);
    }
    if (bracketIdx != -1) {
      endIdx = Math.min(endIdx, bracketIdx);
    }
    if (pipeIdx != -1) {
      endIdx = Math.min(endIdx, pipeIdx);
    }

    return rest.substring(0, endIdx).trim();
  }
}

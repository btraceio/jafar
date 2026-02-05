package io.jafar.hdump.shell.hdumppath;

import io.jafar.shell.core.expr.ValueExpr;
import java.util.List;

/**
 * AST model for HdumpPath query language. Provides OQL-inspired queries for heap dump analysis.
 *
 * <p>Query syntax examples:
 *
 * <pre>
 * # Object queries
 * objects/java.lang.String[shallow > 100] | top(10, shallow)
 * objects/instanceof/java.util.Map | groupBy(class) | stats(shallow)
 *
 * # Class queries
 * classes/java.util.HashMap | select(name, instanceCount, shallowSize)
 * classes[instanceCount > 1000] | top(10, instanceCount)
 *
 * # GC root queries
 * gcroots | groupBy(type)
 * gcroots/THREAD_OBJ | select(type, object)
 * </pre>
 */
public final class HdumpPath {

  private HdumpPath() {}

  /** Query root types. */
  public enum Root {
    /** Query heap objects. */
    OBJECTS,
    /** Query class metadata. */
    CLASSES,
    /** Query GC roots. */
    GCROOTS
  }

  /** Comparison operators. */
  public enum Op {
    EQ("="),
    NE("!="),
    GT(">"),
    GE(">="),
    LT("<"),
    LE("<="),
    REGEX("~");

    private final String symbol;

    Op(String symbol) {
      this.symbol = symbol;
    }

    public String symbol() {
      return symbol;
    }

    public static Op fromSymbol(String s) {
      return switch (s) {
        case "=", "==" -> EQ;
        case "!=" -> NE;
        case ">" -> GT;
        case ">=" -> GE;
        case "<" -> LT;
        case "<=" -> LE;
        case "~" -> REGEX;
        default -> throw new IllegalArgumentException("Unknown operator: " + s);
      };
    }
  }

  /** Logical operators for combining predicates. */
  public enum LogicalOp {
    AND,
    OR
  }

  // === Query structure ===

  /** A complete HdumpPath query. */
  public record Query(
      Root root,
      String typePattern, // Class name pattern (null for all)
      boolean instanceof_, // Whether to include subclasses
      List<Predicate> predicates,
      List<PipelineOp> pipeline) {

    public Query {
      predicates = predicates == null ? List.of() : List.copyOf(predicates);
      pipeline = pipeline == null ? List.of() : List.copyOf(pipeline);
    }
  }

  // === Predicates (filters) ===

  /** Base interface for filter predicates. */
  public sealed interface Predicate permits FieldPredicate, ExprPredicate {}

  /** Simple field-based predicate: field op value */
  public record FieldPredicate(List<String> fieldPath, Op op, Object literal)
      implements Predicate {}

  /** Expression-based predicate for complex boolean logic. */
  public record ExprPredicate(BoolExpr expr) implements Predicate {}

  // === Boolean expressions ===

  /** Boolean expression hierarchy for complex filters. */
  public sealed interface BoolExpr permits CompExpr, LogicalExpr, NotExpr {}

  /** Comparison expression: path op literal */
  public record CompExpr(List<String> fieldPath, Op op, Object literal) implements BoolExpr {}

  /** Logical expression combining two boolean expressions. */
  public record LogicalExpr(BoolExpr left, LogicalOp op, BoolExpr right) implements BoolExpr {}

  /** Negation of a boolean expression. */
  public record NotExpr(BoolExpr inner) implements BoolExpr {}

  // === Pipeline operations ===

  /** Pipeline operation interface. */
  public sealed interface PipelineOp
      permits SelectOp,
          TopOp,
          GroupByOp,
          CountOp,
          SumOp,
          StatsOp,
          SortByOp,
          HeadOp,
          TailOp,
          FilterOp,
          DistinctOp,
          PathToRootOp,
          CheckLeaksOp,
          DominatorsOp {}

  /** Select specific fields/expressions. */
  public record SelectOp(List<SelectField> fields) implements PipelineOp {
    public SelectOp {
      fields = List.copyOf(fields);
    }
  }

  /** A field selection with optional alias. */
  public record SelectField(String field, String alias) {
    public SelectField(String field) {
      this(field, null);
    }
  }

  /** Top N results by a field. */
  public record TopOp(int n, String orderBy, boolean descending) implements PipelineOp {
    public TopOp(int n, String orderBy) {
      this(n, orderBy, true); // Default descending
    }

    public TopOp(int n) {
      this(n, null, true);
    }
  }

  /**
   * Group by field(s) with aggregation.
   *
   * @param groupFields fields to group by
   * @param aggregation aggregation operation (COUNT, SUM, AVG, MIN, MAX)
   * @param valueExpr optional value expression for aggregation
   * @param sortBy "key" to sort by group key, "value" to sort by aggregated value, null for no sorting
   * @param ascending sort order (true = ascending, false = descending)
   */
  public record GroupByOp(
      List<String> groupFields,
      AggOp aggregation,
      ValueExpr valueExpr,
      String sortBy,
      boolean ascending)
      implements PipelineOp {
    public GroupByOp {
      groupFields = List.copyOf(groupFields);
    }

    public GroupByOp(List<String> groupFields, AggOp aggregation) {
      this(groupFields, aggregation, null, null, false);
    }

    public GroupByOp(List<String> groupFields, AggOp aggregation, ValueExpr valueExpr) {
      this(groupFields, aggregation, valueExpr, null, false);
    }
  }

  /** Aggregation operations for groupBy. */
  public enum AggOp {
    COUNT,
    SUM,
    AVG,
    MIN,
    MAX
  }

  /** Count operation. */
  public record CountOp() implements PipelineOp {}

  /** Sum operation on a field. */
  public record SumOp(String field) implements PipelineOp {}

  /** Statistics operation (min, max, avg, sum, count). */
  public record StatsOp(String field) implements PipelineOp {}

  /** Sort by field(s). */
  public record SortByOp(List<SortField> fields) implements PipelineOp {
    public SortByOp {
      fields = List.copyOf(fields);
    }
  }

  /** A sort field with direction. */
  public record SortField(String field, boolean descending) {
    public SortField(String field) {
      this(field, false);
    }
  }

  /** Take first N results. */
  public record HeadOp(int n) implements PipelineOp {}

  /** Take last N results. */
  public record TailOp(int n) implements PipelineOp {}

  /** Filter with predicate. */
  public record FilterOp(Predicate predicate) implements PipelineOp {}

  /** Distinct values. */
  public record DistinctOp(String field) implements PipelineOp {}

  /**
   * Find shortest path to GC root for each object in the result set.
   *
   * <p>Transforms object results into path representations. Each result becomes a path from a GC
   * root to that object, showing the reference chain that keeps it alive.
   *
   * <p>Example:
   *
   * <pre>
   * objects/com.example.Cache[retained > 1GB] | pathToRoot | head(1)
   * </pre>
   */
  public record PathToRootOp() implements PipelineOp {}

  /**
   * Check for memory leaks using built-in detectors or custom filters.
   *
   * <p>Supports two modes:
   *
   * <ul>
   *   <li><strong>Built-in detector:</strong> {@code checkLeaks(detector="duplicate-strings",
   *       threshold=100)}
   *   <li><strong>Custom filter:</strong> {@code checkLeaks(filter=$myQuery)} where $myQuery is a
   *       saved HdumpPath query
   * </ul>
   *
   * <p>Built-in detectors:
   *
   * <ul>
   *   <li>{@code duplicate-strings} - Find identical string values above threshold count
   *   <li>{@code growing-collections} - Detect collections with large retained sizes
   *   <li>{@code threadlocal-leak} - ThreadLocals not removed from threads
   *   <li>{@code classloader-leak} - ClassLoaders that should be GC'd but aren't
   *   <li>{@code listener-leak} - Event listeners not deregistered
   *   <li>{@code finalizer-queue} - Objects stuck in finalizer queue
   * </ul>
   *
   * @param detector name of built-in detector (mutually exclusive with filter)
   * @param filter custom HdumpPath query (mutually exclusive with detector)
   * @param threshold numeric threshold for detector (e.g., minimum duplicate count)
   * @param minSize minimum size threshold for detector (e.g., collection size)
   */
  public record CheckLeaksOp(String detector, String filter, Integer threshold, Integer minSize)
      implements PipelineOp {
    public CheckLeaksOp {
      if (detector != null && filter != null) {
        throw new IllegalArgumentException("Cannot specify both detector and filter");
      }
      if (detector == null && filter == null) {
        throw new IllegalArgumentException("Must specify either detector or filter");
      }
    }

    public CheckLeaksOp(String detector, Integer threshold, Integer minSize) {
      this(detector, null, threshold, minSize);
    }

    public CheckLeaksOp(String filter) {
      this(null, filter, null, null);
    }
  }

  /**
   * Get objects dominated by the result set.
   *
   * <p>For each object in the result set, returns all objects that would be garbage collected if
   * that object were removed. This helps understand the transitive closure of retained objects.
   *
   * <p>Requires approximate retained size computation to have been performed.
   *
   * <p>Example:
   *
   * <pre>
   * objects[retained > 100MB] | dominators | groupBy(class)
   * </pre>
   */
  public record DominatorsOp() implements PipelineOp {}

  // === Built-in field names for objects ===

  /** Standard field names available on heap objects. */
  public static final class ObjectFields {
    public static final String ID = "id";
    public static final String CLASS = "class";
    public static final String CLASS_NAME = "className";
    public static final String SHALLOW = "shallow";
    public static final String SHALLOW_SIZE = "shallowSize";
    public static final String RETAINED = "retained";
    public static final String RETAINED_SIZE = "retainedSize";
    public static final String ARRAY_LENGTH = "arrayLength";
    public static final String STRING_VALUE = "stringValue";

    private ObjectFields() {}
  }

  /** Standard field names available on classes. */
  public static final class ClassFields {
    public static final String ID = "id";
    public static final String NAME = "name";
    public static final String SIMPLE_NAME = "simpleName";
    public static final String INSTANCE_COUNT = "instanceCount";
    public static final String INSTANCE_SIZE = "instanceSize";
    public static final String SUPER_CLASS = "superClass";
    public static final String IS_ARRAY = "isArray";

    private ClassFields() {}
  }

  /** Standard field names available on GC roots. */
  public static final class GcRootFields {
    public static final String TYPE = "type";
    public static final String OBJECT_ID = "objectId";
    public static final String OBJECT = "object";
    public static final String THREAD_SERIAL = "threadSerial";
    public static final String FRAME_NUMBER = "frameNumber";

    private GcRootFields() {}
  }
}

package io.jafar.otelp.shell.otelppath;

import java.util.List;

/**
 * AST model for the OtelpPath query language.
 *
 * <p>Query syntax examples:
 *
 * <pre>
 * samples
 * samples[thread='main']
 * samples | count()
 * samples | top(10, cpu)
 * samples | top(10, cpu, asc)
 * samples | groupBy(thread)
 * samples | groupBy(thread, sum(cpu))
 * samples | stats(cpu)
 * samples | head(20)
 * samples | tail(20)
 * samples | select(cpu, thread)
 * samples | sortBy(cpu, desc)
 * samples | stackprofile()
 * samples | stackprofile(cpu)
 * samples[thread='main'] | top(10, cpu)
 * </pre>
 */
public final class OtelpPath {

  private OtelpPath() {}

  /** Root types supported by the OtelpPath query language. */
  public enum Root {
    /** Query individual profiling samples. */
    SAMPLES
  }

  /** Comparison operators used in predicates. */
  public enum Op {
    EQ,
    NE,
    GT,
    GE,
    LT,
    LE,
    REGEX
  }

  // ---- Query structure ----

  /** A complete OtelpPath query. */
  public record Query(Root root, List<Predicate> predicates, List<PipelineOp> pipeline) {
    public Query {
      predicates = predicates == null ? List.of() : List.copyOf(predicates);
      pipeline = pipeline == null ? List.of() : List.copyOf(pipeline);
    }
  }

  // ---- Predicates ----

  /** A filter predicate applied to sample rows. */
  public sealed interface Predicate permits FieldPredicate, LogicalPredicate {}

  /** Simple field comparison: {@code field op literal}. */
  public record FieldPredicate(String field, Op op, Object literal) implements Predicate {}

  /** Logical combination of two predicates. */
  public record LogicalPredicate(Predicate left, boolean and, Predicate right)
      implements Predicate {}

  // ---- Pipeline operations ----

  /** A pipeline operation applied to a stream of rows. */
  public sealed interface PipelineOp
      permits CountOp,
          TopOp,
          GroupByOp,
          StatsOp,
          HeadOp,
          TailOp,
          FilterOp,
          SelectOp,
          SortByOp,
          StackProfileOp,
          DistinctOp {}

  /** Counts the total number of rows. */
  public record CountOp() implements PipelineOp {}

  /**
   * Returns the top {@code n} rows sorted by {@code byField}.
   *
   * @param n number of rows
   * @param byField field to sort by (defaults to first value type if null)
   * @param ascending true for ascending order, false for descending (default)
   */
  public record TopOp(int n, String byField, boolean ascending) implements PipelineOp {}

  /**
   * Groups rows by a key field and aggregates values.
   *
   * @param keyField field to group by
   * @param aggFunc aggregation function: "count" or "sum"
   * @param valueField field to aggregate (null for count)
   */
  public record GroupByOp(String keyField, String aggFunc, String valueField)
      implements PipelineOp {}

  /** Computes statistics (min, max, avg, sum, count) for a numeric field. */
  public record StatsOp(String valueField) implements PipelineOp {}

  /** Takes the first {@code n} rows. */
  public record HeadOp(int n) implements PipelineOp {}

  /** Takes the last {@code n} rows. */
  public record TailOp(int n) implements PipelineOp {}

  /** Filters rows by additional predicates. */
  public record FilterOp(List<Predicate> predicates) implements PipelineOp {
    public FilterOp {
      predicates = predicates == null ? List.of() : List.copyOf(predicates);
    }
  }

  /**
   * Projects rows to a subset of fields.
   *
   * @param fields field names to keep
   */
  public record SelectOp(List<String> fields) implements PipelineOp {
    public SelectOp {
      fields = fields == null ? List.of() : List.copyOf(fields);
    }
  }

  /**
   * Sorts rows by a field.
   *
   * @param field field to sort by
   * @param ascending true for ascending, false for descending
   */
  public record SortByOp(String field, boolean ascending) implements PipelineOp {}

  /**
   * Produces folded stack profile output suitable for flame graphs.
   *
   * @param valueField value type to sum per stack (null = use first sample type or sample count)
   */
  public record StackProfileOp(String valueField) implements PipelineOp {}

  /** Returns distinct values of a field. */
  public record DistinctOp(String field) implements PipelineOp {}
}

package io.jafar.shell.llm;

import java.util.Collections;
import java.util.Set;

/**
 * Categories of natural language queries for JFR analysis.
 *
 * <p>These categories enable fine-grained classification and category-specific prompt selection to
 * optimize token usage and translation accuracy.
 */
public enum QueryCategory {
  /** Simple count queries: "count GC events" → events/jdk.GarbageCollection | count() */
  SIMPLE_COUNT(Set.of(), true, false),

  /** Existence checks: "is ExecutionSample present?" → events/jdk.ExecutionSample | count() */
  EXISTENCE_CHECK(Set.of(), true, false),

  /** Metadata queries: "what fields does X have?" → metadata/jdk.ExecutionSample */
  METADATA_QUERY(Set.of(), true, false),

  /** Simple filtering: "file reads over 1MB" → events/jdk.FileRead[bytes>1048576] */
  SIMPLE_FILTER(Set.of(SIMPLE_COUNT), true, false),

  /** Statistical aggregation: "average GC pause" → events/jdk.GarbageCollection | stats(duration) */
  STATISTICS(Set.of(SIMPLE_FILTER, SIMPLE_COUNT), false, false),

  /** Simple groupBy: "how many threads" → groupBy(eventThread/javaName) */
  GROUPBY_SIMPLE(Set.of(SIMPLE_FILTER), false, false),

  /** GroupBy with aggregation: "total by X" → groupBy(X, agg=sum, value=Y) */
  GROUPBY_AGGREGATED(Set.of(GROUPBY_SIMPLE, STATISTICS), false, false),

  /** Top-N ranking: "top 10 allocating classes" → groupBy(...) | top(10, by=sum) */
  TOPN_RANKING(Set.of(GROUPBY_AGGREGATED, GROUPBY_SIMPLE), false, false),

  /** Time range filtering: "GCs longer than 50ms" → [duration>50000000] */
  TIME_RANGE_FILTER(Set.of(SIMPLE_FILTER), true, false),

  /** Temporal correlation: "methods during pinning" → decorateByTime(...) */
  DECORATOR_TEMPORAL(Set.of(TOPN_RANKING, GROUPBY_AGGREGATED), false, true),

  /** Key-based correlation: "samples with requestId" → decorateByKey(...) */
  DECORATOR_CORRELATION(Set.of(TOPN_RANKING, DECORATOR_TEMPORAL), false, true),

  /** Complex multi-operation queries requiring full context */
  COMPLEX_MULTIOP(Set.of(DECORATOR_TEMPORAL, DECORATOR_CORRELATION, TOPN_RANKING), false, true),

  /** Conversational responses (not data queries) */
  CONVERSATIONAL(Set.of(), true, false);

  private final Set<QueryCategory> relatedCategories;
  private final boolean simple;
  private final boolean needsDecorator;

  QueryCategory(Set<QueryCategory> relatedCategories, boolean simple, boolean needsDecorator) {
    this.relatedCategories = relatedCategories;
    this.simple = simple;
    this.needsDecorator = needsDecorator;
  }

  /**
   * Returns related categories that should be included in enhanced prompts.
   *
   * @return set of related categories for context enrichment
   */
  public Set<QueryCategory> getRelatedCategories() {
    return Collections.unmodifiableSet(relatedCategories);
  }

  /**
   * Checks if this is a simple category that typically needs minimal prompts.
   *
   * @return true if simple category (count, existence, metadata, basic filters)
   */
  public boolean isSimple() {
    return simple;
  }

  /**
   * Checks if this category requires decorator syntax knowledge.
   *
   * @return true if decorator-related category
   */
  public boolean needsDecorator() {
    return needsDecorator;
  }

  /**
   * Returns the resource path for category-specific examples.
   *
   * @return path like "categories/simple-count/examples.txt"
   */
  public String getExamplesPath() {
    return "categories/" + name().toLowerCase().replace('_', '-') + "/examples.txt";
  }

  /**
   * Returns the resource path for category-specific rules.
   *
   * @return path like "categories/simple-count/rules.txt"
   */
  public String getRulesPath() {
    return "categories/" + name().toLowerCase().replace('_', '-') + "/rules.txt";
  }
}

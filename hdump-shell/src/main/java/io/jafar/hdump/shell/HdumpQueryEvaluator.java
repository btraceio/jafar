package io.jafar.hdump.shell;

import io.jafar.hdump.shell.hdumppath.HdumpPath.Query;
import io.jafar.hdump.shell.hdumppath.HdumpPathEvaluator;
import io.jafar.hdump.shell.hdumppath.HdumpPathParseException;
import io.jafar.hdump.shell.hdumppath.HdumpPathParser;
import io.jafar.shell.core.QueryEvaluator;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionResolver;
import io.jafar.shell.core.VariableStore;
import java.util.List;
import java.util.Map;

/**
 * QueryEvaluator implementation for HdumpPath query language. Bridges the shell infrastructure with
 * the heap dump query system.
 */
public final class HdumpQueryEvaluator implements QueryEvaluator {

  private static final List<String> ROOT_TYPES =
      List.of("objects", "classes", "gcroots", "clusters", "duplicates", "ages");

  private static final List<String> OPERATORS =
      List.of(
          "select",
          "top",
          "groupBy",
          "count",
          "sum",
          "stats",
          "sortBy",
          "head",
          "tail",
          "filter",
          "distinct",
          "pathToRoot",
          "retentionPaths",
          "retainedBreakdown",
          "checkLeaks",
          "dominators",
          "join",
          "len",
          "uppercase",
          "lowercase",
          "trim",
          "replace",
          "abs",
          "round",
          "floor",
          "ceil",
          "waste",
          "objects");

  @Override
  public Object parse(String queryString) throws QueryParseException {
    try {
      return HdumpPathParser.parse(queryString);
    } catch (HdumpPathParseException e) {
      throw new QueryParseException(e.getMessage(), queryString, -1, e);
    }
  }

  @Override
  public Object evaluate(Session session, Object query) throws Exception {
    if (!(session instanceof HeapSession heapSession)) {
      throw new IllegalArgumentException("HdumpQueryEvaluator requires a HeapSession");
    }
    Query hdumpQuery = toQuery(query);
    return HdumpPathEvaluator.evaluate(heapSession, hdumpQuery);
  }

  @Override
  public Object evaluate(Session session, Object query, SessionResolver resolver) throws Exception {
    if (!(session instanceof HeapSession heapSession)) {
      throw new IllegalArgumentException("HdumpQueryEvaluator requires a HeapSession");
    }
    Query hdumpQuery = toQuery(query);
    return HdumpPathEvaluator.evaluate(heapSession, hdumpQuery, resolver);
  }

  private Query toQuery(Object query) {
    if (query instanceof Query q) {
      return q;
    }
    if (query instanceof String s) {
      return (Query) parse(s);
    }
    throw new IllegalArgumentException(
        "Expected HdumpPath.Query or String, got " + query.getClass());
  }

  @Override
  public List<String> getRootTypes() {
    return ROOT_TYPES;
  }

  @Override
  public List<String> getOperators() {
    return OPERATORS;
  }

  @Override
  public String getOperatorHelp(String operator) {
    return switch (operator.toLowerCase()) {
      case "select" -> "select(field1, field2 as alias, ...) - Project specific fields";
      case "top" -> "top(n, field, [asc|desc]) - Get top N results sorted by field";
      case "groupby", "group" ->
          "groupBy(field, agg=count|sum|avg|min|max, value=expr) - Group and aggregate (expr supports +,-,*,/)";
      case "count" -> "count - Count total results";
      case "sum" -> "sum(field) - Sum numeric field values";
      case "stats" -> "stats(field) - Get statistics (min, max, avg, sum, count)";
      case "sortby", "sort", "orderby", "order" -> "sortBy(field [asc|desc], ...) - Sort results";
      case "head" -> "head(n) - Take first N results";
      case "tail" -> "tail(n) - Take last N results";
      case "filter", "where" -> "filter(predicate) - Filter results by condition";
      case "distinct", "unique" -> "distinct(field) - Get distinct values";
      case "pathtoroot", "path" -> "pathToRoot() - Find path to GC root";
      case "retentionpaths" -> "retentionPaths() - Merge retention paths at class level";
      case "retainedbreakdown", "breakdown" ->
          "retainedBreakdown(depth=N) - Expand dominator subtree by class";
      case "checkleaks", "leaks" ->
          "checkLeaks(detector=\"name\", threshold=N, minSize=N) - Run leak detection";
      case "dominators", "dominated" -> "dominators() - Get dominated objects";
      case "join" ->
          "join(session=id|alias[, root=\"eventType\", by=field]) - Join with another session (heap diff or JFR correlation)";
      case "len" -> "len(field) - String length or collection size";
      case "uppercase" -> "uppercase(field) - Convert to uppercase";
      case "lowercase" -> "lowercase(field) - Convert to lowercase";
      case "trim" -> "trim(field) - Trim whitespace";
      case "replace" -> "replace(field, \"old\", \"new\") - Replace occurrences in string";
      case "abs" -> "abs(field) - Absolute value";
      case "round" -> "round(field) - Round to nearest integer";
      case "floor" -> "floor(field) - Round down";
      case "ceil" -> "ceil(field) - Round up";
      case "waste" -> "waste() - Analyze collection capacity waste (capacity, size, wastedBytes)";
      case "objects" -> "objects() - Drill down from cluster rows into member object rows";
      case "whatif" -> "whatif() — simulate removing the input objects and report freed memory";
      case "ages" -> "ages[/class] — query objects with estimated age score";
      case "estimateage", "age" ->
          "estimateAge() — enrich object rows with estimatedAge, ageBucket, ageSignals";
      default -> null;
    };
  }

  @Override
  public VariableStore.LazyValue createLazyValue(
      Session session, Object query, String queryString) {
    if (!(session instanceof HeapSession heapSession)) {
      throw new IllegalArgumentException("HdumpQueryEvaluator requires a HeapSession");
    }
    if (!(query instanceof Query hdumpQuery)) {
      throw new IllegalArgumentException("Expected HdumpPath.Query, got " + query.getClass());
    }
    return new LazyHdumpValue(heapSession, hdumpQuery, queryString);
  }

  /** Lazy value that evaluates HdumpPath query on demand. */
  private static final class LazyHdumpValue implements VariableStore.LazyValue {
    private final HeapSession session;
    private final Query query;
    private final String queryString;
    private List<Map<String, Object>> cached;
    private boolean evaluated;

    LazyHdumpValue(HeapSession session, Query query, String queryString) {
      this.session = session;
      this.query = query;
      this.queryString = queryString;
    }

    @Override
    public Object get() throws Exception {
      if (!evaluated) {
        cached = HdumpPathEvaluator.evaluate(session, query);
        evaluated = true;
      }
      return cached;
    }

    @Override
    public String describe() {
      return "HdumpPath query: " + queryString;
    }

    @Override
    public void invalidate() {
      cached = null;
      evaluated = false;
    }

    @Override
    public boolean isCached() {
      return evaluated;
    }

    @Override
    public String getQueryString() {
      return queryString;
    }
  }
}

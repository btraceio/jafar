package io.jafar.hdump.shell;

import io.jafar.hdump.shell.hdumppath.HdumpPath.Query;
import io.jafar.hdump.shell.hdumppath.HdumpPathEvaluator;
import io.jafar.hdump.shell.hdumppath.HdumpPathParseException;
import io.jafar.hdump.shell.hdumppath.HdumpPathParser;
import io.jafar.shell.core.QueryEvaluator;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.VariableStore;
import java.util.List;
import java.util.Map;

/**
 * QueryEvaluator implementation for HdumpPath query language. Bridges the shell infrastructure with
 * the heap dump query system.
 */
public final class HdumpQueryEvaluator implements QueryEvaluator {

  private static final List<String> ROOT_TYPES = List.of("objects", "classes", "gcroots");

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
          "distinct");

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
    if (!(query instanceof Query hdumpQuery)) {
      throw new IllegalArgumentException("Expected HdumpPath.Query, got " + query.getClass());
    }
    return HdumpPathEvaluator.evaluate(heapSession, hdumpQuery);
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
      case "groupby", "group" -> "groupBy(field, agg=count|sum|avg|min|max) - Group and aggregate";
      case "count" -> "count - Count total results";
      case "sum" -> "sum(field) - Sum numeric field values";
      case "stats" -> "stats(field) - Get statistics (min, max, avg, sum, count)";
      case "sortby", "sort", "orderby", "order" -> "sortBy(field [asc|desc], ...) - Sort results";
      case "head" -> "head(n) - Take first N results";
      case "tail" -> "tail(n) - Take last N results";
      case "filter", "where" -> "filter(predicate) - Filter results by condition";
      case "distinct", "unique" -> "distinct(field) - Get distinct values";
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

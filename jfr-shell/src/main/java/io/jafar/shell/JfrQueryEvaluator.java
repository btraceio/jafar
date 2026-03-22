package io.jafar.shell;

import io.jafar.shell.core.QueryEvaluator;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.VariableStore;
import io.jafar.shell.jfrpath.JfrPath.Query;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import io.jafar.shell.jfrpath.JfrPathParser;
import java.util.List;
import java.util.Map;

/**
 * QueryEvaluator implementation for JfrPath query language. Bridges the shell infrastructure with
 * the JFR query system.
 */
public final class JfrQueryEvaluator implements QueryEvaluator {

  private static final List<String> ROOT_TYPES =
      List.of("events", "metadata", "chunks", "constants");

  private static final List<String> OPERATORS =
      List.of(
          "count",
          "stats",
          "groupBy",
          "sortBy",
          "top",
          "head",
          "tail",
          "filter",
          "distinct",
          "select",
          "crossref");

  @Override
  public Object parse(String queryString) throws QueryParseException {
    try {
      return JfrPathParser.parse(queryString);
    } catch (IllegalArgumentException e) {
      throw new QueryParseException(e.getMessage(), queryString, -1, e);
    }
  }

  @Override
  public Object evaluate(Session session, Object query) throws Exception {
    if (!(session instanceof JFRSession jfrSession)) {
      throw new IllegalArgumentException("JfrQueryEvaluator requires a JFRSession");
    }
    if (!(query instanceof Query jfrQuery)) {
      throw new IllegalArgumentException("Expected JfrPath.Query, got " + query.getClass());
    }
    return new JfrPathEvaluator().evaluate(jfrSession, jfrQuery);
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
      case "count" -> "count() - Count total results";
      case "stats" -> "stats(field) - Get statistics (min, max, avg, sum, count)";
      case "groupby", "group" ->
          "groupBy(field, [agg]) - Group results by field with optional aggregation";
      case "sortby", "sort", "orderby", "order" -> "sortBy(field [asc|desc], ...) - Sort results";
      case "top" -> "top(n, [field, [asc|desc]]) - Get top N results";
      case "head" -> "head(n) - Take first N results";
      case "tail" -> "tail(n) - Take last N results";
      case "filter", "where" -> "filter(predicate) - Filter results by condition";
      case "distinct", "unique" -> "distinct(field) - Get distinct values";
      case "select" -> "select(field1, field2, ...) - Project specific fields";
      case "crossref" -> "crossref() - Cross-reference constant pool types";
      default -> null;
    };
  }

  @Override
  public VariableStore.LazyValue createLazyValue(
      Session session, Object query, String queryString) {
    if (!(session instanceof JFRSession jfrSession)) {
      throw new IllegalArgumentException("JfrQueryEvaluator requires a JFRSession");
    }
    if (!(query instanceof Query jfrQuery)) {
      throw new IllegalArgumentException("Expected JfrPath.Query, got " + query.getClass());
    }
    return new LazyJfrValue(jfrSession, jfrQuery, queryString);
  }

  /** Lazy value that evaluates JfrPath query on demand. */
  private static final class LazyJfrValue implements VariableStore.LazyValue {
    private final JFRSession session;
    private final Query query;
    private final String queryString;
    private List<Map<String, Object>> cached;
    private boolean evaluated;

    LazyJfrValue(JFRSession session, Query query, String queryString) {
      this.session = session;
      this.query = query;
      this.queryString = queryString;
    }

    @Override
    public Object get() throws Exception {
      if (!evaluated) {
        cached = new JfrPathEvaluator().evaluate(session, query);
        evaluated = true;
      }
      return cached;
    }

    @Override
    public String describe() {
      return "JfrPath query: " + queryString;
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

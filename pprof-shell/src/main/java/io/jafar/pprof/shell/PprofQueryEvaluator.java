package io.jafar.pprof.shell;

import io.jafar.pprof.shell.pprofpath.PprofPathEvaluator;
import io.jafar.pprof.shell.pprofpath.PprofPathParseException;
import io.jafar.pprof.shell.pprofpath.PprofPathParser;
import io.jafar.shell.core.QueryEvaluator;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.VariableStore;
import io.jafar.shell.core.sampling.path.SamplesPath;
import java.util.List;
import java.util.Map;

/**
 * {@link QueryEvaluator} implementation for the pprof query language. Bridges the shell
 * infrastructure with the pprof query system.
 */
public final class PprofQueryEvaluator implements QueryEvaluator {

  private static final List<String> ROOT_TYPES = List.of("samples");

  private static final List<String> OPERATORS =
      List.of(
          "count",
          "top",
          "groupBy",
          "stats",
          "head",
          "tail",
          "filter",
          "select",
          "sortBy",
          "stackprofile",
          "distinct");

  @Override
  public Object parse(String queryString) throws QueryParseException {
    try {
      return PprofPathParser.parse(queryString);
    } catch (PprofPathParseException e) {
      throw new QueryParseException(e.getMessage(), queryString, -1, e);
    }
  }

  @Override
  public Object evaluate(Session session, Object query) throws Exception {
    PprofSession pprofSession = requirePprofSession(session);
    SamplesPath.Query pprofQuery = toQuery(query);
    return PprofPathEvaluator.evaluate(pprofSession, pprofQuery);
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
      case "count" -> "count() - Count total samples";
      case "top" -> "top(n, field, [asc|desc]) - Get top N samples sorted by value type";
      case "groupby", "group" ->
          "groupBy(field, [count|sum(field)]) - Group and aggregate (e.g. groupBy(thread, sum(cpu)))";
      case "stats" -> "stats(field) - Get statistics (min, max, avg, sum, count) for a value type";
      case "head" -> "head(n) - Take first N results";
      case "tail" -> "tail(n) - Take last N results";
      case "filter", "where" -> "filter(predicate) - Filter samples by condition";
      case "select" -> "select(field1, field2, ...) - Project specific fields";
      case "sortby", "sort", "orderby" -> "sortBy(field [asc|desc]) - Sort results";
      case "stackprofile" ->
          "stackprofile([field]) - Produce folded stack profile for flame graph (optional: value type to sum)";
      case "distinct", "unique" -> "distinct(field) - Get distinct values of a field";
      default -> null;
    };
  }

  @Override
  public VariableStore.LazyValue createLazyValue(
      Session session, Object query, String queryString) {
    PprofSession pprofSession = requirePprofSession(session);
    SamplesPath.Query pprofQuery = toQuery(query);
    return new LazyPprofValue(pprofSession, pprofQuery, queryString);
  }

  private PprofSession requirePprofSession(Session session) {
    if (!(session instanceof PprofSession ps)) {
      throw new IllegalArgumentException(
          "PprofQueryEvaluator requires a PprofSession, got: "
              + session.getClass().getSimpleName());
    }
    return ps;
  }

  private SamplesPath.Query toQuery(Object query) {
    if (query instanceof SamplesPath.Query q) return q;
    if (query instanceof String s) return (SamplesPath.Query) parse(s);
    throw new IllegalArgumentException(
        "Expected SamplesPath.Query or String, got: " + query.getClass());
  }

  /** Lazy value that evaluates a pprof query on demand. */
  private static final class LazyPprofValue implements VariableStore.LazyValue {
    private final PprofSession session;
    private final SamplesPath.Query query;
    private final String queryString;
    private List<Map<String, Object>> cached;
    private boolean evaluated;

    LazyPprofValue(PprofSession session, SamplesPath.Query query, String queryString) {
      this.session = session;
      this.query = query;
      this.queryString = queryString;
    }

    @Override
    public synchronized Object get() throws Exception {
      if (!evaluated) {
        cached = PprofPathEvaluator.evaluate(session, query);
        evaluated = true;
      }
      return cached;
    }

    @Override
    public String describe() {
      return "pprof query: " + queryString;
    }

    @Override
    public synchronized void invalidate() {
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

package io.jafar.shell;

import io.jafar.shell.cli.LazyQueryValue;
import io.jafar.shell.core.QueryEvaluator;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.VariableStore;
import io.jafar.shell.jfrpath.JfrPath;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import io.jafar.shell.jfrpath.JfrPathParser;
import java.util.List;

/** Query evaluator for JFR files using JfrPath query language. */
public final class JfrQueryEvaluator implements QueryEvaluator {

  @Override
  public Object parse(String queryString) throws QueryParseException {
    try {
      return JfrPathParser.parse(queryString);
    } catch (Exception e) {
      throw new QueryParseException("Failed to parse JfrPath query", queryString, -1, e);
    }
  }

  @Override
  public Object evaluate(Session session, Object query) throws Exception {
    if (!(session instanceof JFRSession jfrSession)) {
      throw new IllegalArgumentException("Session must be a JFRSession");
    }
    if (!(query instanceof JfrPath.Query jfrQuery)) {
      throw new IllegalArgumentException("Query must be a JfrPath.Query");
    }

    JfrPathEvaluator evaluator = new JfrPathEvaluator();
    return evaluator.evaluate(jfrSession, jfrQuery);
  }

  @Override
  public List<String> getRootTypes() {
    return List.of("events", "metadata", "chunks", "cp");
  }

  @Override
  public List<String> getOperators() {
    return List.of(
        "top",
        "groupBy",
        "select",
        "stats",
        "count",
        "sum",
        "avg",
        "min",
        "max",
        "decorateByTime",
        "decorateByKey");
  }

  @Override
  public String getOperatorHelp(String operator) {
    return switch (operator) {
      case "top" -> "top(N) - Limit results to top N rows";
      case "groupBy" -> "groupBy(field) - Group by field value";
      case "select" -> "select(field1, field2, ...) - Select specific fields";
      case "count" -> "count() - Count number of rows";
      case "sum" -> "sum(field) - Sum numeric field";
      case "avg" -> "avg(field) - Average of numeric field";
      case "min" -> "min(field) - Minimum value";
      case "max" -> "max(field) - Maximum value";
      case "decorateByTime" ->
          "decorateByTime(eventType, startField, endField) - Join events by time overlap";
      case "decorateByKey" ->
          "decorateByKey(eventType, keyField) - Join events with matching correlation key";
      default -> null;
    };
  }

  @Override
  public VariableStore.LazyValue createLazyValue(Session session, Object query, String queryString) {
    if (!(session instanceof JFRSession)) {
      throw new IllegalArgumentException("Session must be a JFRSession");
    }
    // Need to wrap session in a SessionRef for LazyQueryValue
    // For now, create a simple wrapper
    SessionManager.SessionRef sessionRef =
        new SessionManager.SessionRef(1, null, session);
    return new LazyQueryValue(queryString, sessionRef, queryString);
  }
}

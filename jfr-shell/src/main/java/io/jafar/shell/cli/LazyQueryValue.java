package io.jafar.shell.cli;

import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.VariableStore;
import io.jafar.shell.jfrpath.JfrPath;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import io.jafar.shell.jfrpath.JfrPathParser;

/**
 * JFR-specific lazy-evaluated query value. Evaluates JfrPath queries on-demand and caches results.
 */
public final class LazyQueryValue implements VariableStore.LazyValue {

  private final String queryString;
  private final SessionManager.SessionRef sessionRef;
  private final String expression;
  private Object cachedResult;
  private boolean cached;

  public LazyQueryValue(String queryString, SessionManager.SessionRef sessionRef, String expression) {
    this.queryString = queryString;
    this.sessionRef = sessionRef;
    this.expression = expression;
    this.cached = false;
  }

  @Override
  public Object get() throws Exception {
    if (!cached) {
      JfrPathEvaluator evaluator = new JfrPathEvaluator();
      JfrPath.Query query = JfrPathParser.parse(queryString);
      cachedResult = evaluator.evaluate((io.jafar.shell.JFRSession) sessionRef.session, query);
      cached = true;
    }
    return cachedResult;
  }

  @Override
  public void invalidate() {
    cached = false;
    cachedResult = null;
  }

  @Override
  public boolean isCached() {
    return cached;
  }

  @Override
  public String getQueryString() {
    return queryString;
  }

  @Override
  public String describe() {
    return "lazy[" + expression + "]" + (cached ? " (cached)" : "");
  }

  @Override
  public void release() {
    invalidate();
  }

  /**
   * Sets a pre-computed result, useful when the query has already been evaluated during variable
   * assignment.
   */
  public void setCachedResult(Object result) {
    this.cachedResult = result;
    this.cached = true;
  }

  /**
   * Creates a copy of this lazy value with a new session reference. Used when copying variables
   * between scopes.
   */
  public LazyQueryValue copy(SessionManager.SessionRef newSessionRef) {
    LazyQueryValue copy = new LazyQueryValue(queryString, newSessionRef, expression);
    if (cached) {
      copy.setCachedResult(cachedResult);
    }
    return copy;
  }

  /**
   * Returns the size of the result set. For lists, returns the list size. For other types, returns
   * 1.
   */
  public int size() throws Exception {
    Object result = get();
    if (result instanceof java.util.List<?> list) {
      return list.size();
    }
    return result != null ? 1 : 0;
  }

  /**
   * Extracts a field value from a specific row in the result set.
   *
   * @param index row index (0-based)
   * @param field field name to extract
   * @return the field value, or null if not found
   */
  public Object extract(int index, String field) throws Exception {
    Object result = get();
    if (result instanceof java.util.List<?> list) {
      if (index < 0 || index >= list.size()) {
        return null;
      }
      Object row = list.get(index);
      if (row instanceof java.util.Map<?, ?> map) {
        return map.get(field);
      }
    }
    return null;
  }
}

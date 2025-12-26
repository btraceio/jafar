package io.jafar.shell.core;

import io.jafar.shell.core.SessionManager.SessionRef;
import io.jafar.shell.jfrpath.JfrPath.Query;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stores variables with support for scalar values and lazy query results. Variables can hold
 * immediate scalar values (String, Number, Boolean) or lazy query results that are evaluated
 * on-demand using the flyweight pattern.
 */
public final class VariableStore {

  /** A variable value that can be retrieved and released. */
  public sealed interface Value permits ScalarValue, LazyQueryValue {
    /**
     * Gets the value. For lazy values, this triggers evaluation.
     *
     * @return the value (scalar or List of Maps for query results)
     * @throws IllegalStateException if the value cannot be retrieved (e.g., session closed)
     */
    Object get() throws Exception;

    /** Releases any resources held by this value. Called when variable is replaced or removed. */
    default void release() {}

    /** Returns a description of this value for display in vars command. */
    String describe();
  }

  /** Immediate scalar value (String, Number, Boolean, etc.). */
  public record ScalarValue(Object value) implements Value {
    @Override
    public Object get() {
      return value;
    }

    @Override
    public String describe() {
      if (value == null) return "null";
      if (value instanceof String) return "\"" + value + "\"";
      return value.toString();
    }
  }

  /**
   * Lazy query value using flyweight pattern. Stores the query and a weak reference to the session.
   * Evaluation happens on-demand when get() is called, with caching of the result.
   */
  public static final class LazyQueryValue implements Value {
    private final Query query;
    private final WeakReference<SessionRef> sessionRef;
    private final String queryString; // Original query string for display
    private volatile Object cachedResult; // Cached evaluation result
    private volatile boolean evaluated; // Whether cache is valid

    public LazyQueryValue(Query query, SessionRef session, String queryString) {
      this.query = query;
      this.sessionRef = new WeakReference<>(session);
      this.queryString = queryString;
      this.cachedResult = null;
      this.evaluated = false;
    }

    @Override
    public Object get() throws Exception {
      if (evaluated) {
        return cachedResult;
      }
      synchronized (this) {
        if (evaluated) {
          return cachedResult;
        }
        SessionRef ref = sessionRef.get();
        if (ref == null) {
          throw new IllegalStateException("Session no longer available (garbage collected)");
        }
        if (ref.session.isClosed()) {
          throw new IllegalStateException("Session has been closed");
        }
        JfrPathEvaluator evaluator = new JfrPathEvaluator();
        cachedResult = evaluator.evaluate(ref.session, query);
        evaluated = true;
        return cachedResult;
      }
    }

    /** Invalidates the cache, forcing re-evaluation on next access. */
    public void invalidate() {
      synchronized (this) {
        cachedResult = null;
        evaluated = false;
      }
    }

    /** Returns whether the result has been cached. */
    public boolean isCached() {
      return evaluated;
    }

    /**
     * Gets the size of the result set.
     *
     * @return row count
     */
    public int size() throws Exception {
      Object result = get();
      if (result instanceof List<?> list) {
        return list.size();
      }
      return 1;
    }

    /**
     * Extracts a scalar value from the result set.
     *
     * @param index row index (0-based)
     * @param field field name, or null for first field
     * @return the extracted value, or null if not found
     */
    @SuppressWarnings("unchecked")
    public Object extract(int index, String field) throws Exception {
      Object result = get();
      if (!(result instanceof List<?> list)) {
        return index == 0 ? result : null;
      }
      if (index >= list.size()) {
        return null;
      }
      Object row = list.get(index);
      if (!(row instanceof Map<?, ?> map)) {
        return row;
      }
      Map<String, Object> rowMap = (Map<String, Object>) map;
      if (field == null) {
        // Return first field value
        return rowMap.isEmpty() ? null : rowMap.values().iterator().next();
      }
      return rowMap.get(field);
    }

    @Override
    public void release() {
      synchronized (this) {
        cachedResult = null;
        evaluated = false;
        sessionRef.clear();
      }
    }

    @Override
    public String describe() {
      SessionRef ref = sessionRef.get();
      String status;
      if (ref == null) {
        status = " (session gone)";
      } else if (ref.session.isClosed()) {
        status = " (closed)";
      } else if (evaluated) {
        int size = cachedResult instanceof List<?> list ? list.size() : 1;
        status = " (cached: " + size + " rows)";
      } else {
        status = " (not evaluated)";
      }
      return "lazy[" + queryString + "]" + status;
    }

    /** Returns the original query string. */
    public String getQueryString() {
      return queryString;
    }
  }

  private final Map<String, Value> variables = new HashMap<>();

  /**
   * Sets a variable value, releasing any previous value.
   *
   * @param name variable name
   * @param value the new value
   */
  public void set(String name, Value value) {
    Value old = variables.put(name, value);
    if (old != null) {
      old.release();
    }
  }

  /**
   * Gets a variable value.
   *
   * @param name variable name
   * @return the value, or null if not found
   */
  public Value get(String name) {
    return variables.get(name);
  }

  /**
   * Checks if a variable exists.
   *
   * @param name variable name
   * @return true if the variable exists
   */
  public boolean contains(String name) {
    return variables.containsKey(name);
  }

  /**
   * Removes a variable, releasing its value.
   *
   * @param name variable name
   * @return true if the variable existed
   */
  public boolean remove(String name) {
    Value old = variables.remove(name);
    if (old != null) {
      old.release();
      return true;
    }
    return false;
  }

  /** Clears all variables, releasing all values. */
  public void clear() {
    for (Value v : variables.values()) {
      v.release();
    }
    variables.clear();
  }

  /**
   * Returns the names of all variables.
   *
   * @return set of variable names
   */
  public Set<String> names() {
    return variables.keySet();
  }

  /**
   * Returns the number of variables.
   *
   * @return variable count
   */
  public int size() {
    return variables.size();
  }

  /**
   * Returns whether the store is empty.
   *
   * @return true if no variables are stored
   */
  public boolean isEmpty() {
    return variables.isEmpty();
  }
}

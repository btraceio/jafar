package io.jafar.shell.core;

import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager.SessionRef;
import io.jafar.shell.jfrpath.JfrPath.Query;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Lazy query value using flyweight pattern. Stores the query and a weak reference to the session.
 * Evaluation happens on-demand when get() is called, with caching of the result.
 *
 * <p>Thread-safe. The cache is one volatile slot holding the rows or a "not evaluated" token, so a
 * reader sees either rows or "not evaluated" — never the "evaluated" flag of one state with the
 * rows of another, which two separate volatiles allowed (a read racing {@link #invalidate} could
 * return {@code null}). Every {@link #invalidate} and {@link #release} installs a fresh token, and
 * an evaluation publishes its rows only by compare-and-set against the token it started from, so an
 * evaluation still running when the value is invalidated or released cannot put its rows back.
 * Neither takes a lock or waits for an evaluation. A cached read takes no lock; evaluation is
 * serialized.
 */
public final class LazyQueryValue implements VariableStore.LazyValue {
  /**
   * "Not evaluated". A new one is installed by every invalidation, so an evaluation can tell
   * whether the slot it started from is still there.
   */
  private static final class Unevaluated {}

  private static final AtomicReferenceFieldUpdater<LazyQueryValue, Object> CACHE =
      AtomicReferenceFieldUpdater.newUpdater(LazyQueryValue.class, Object.class, "cache");

  /** Stands for an evaluation that returned {@code null}, which still counts as evaluated. */
  private static final Object NULL_RESULT = new Object();

  private final Query query;
  private final WeakReference<SessionRef<JFRSession>> sessionRef;
  private final String queryString;
  private volatile Object cache = new Unevaluated();

  public LazyQueryValue(Query query, SessionRef<JFRSession> session, String queryString) {
    this.query = query;
    this.sessionRef = new WeakReference<>(session);
    this.queryString = queryString;
  }

  /**
   * Creates a copy of this LazyQueryValue with independent cache state. The copy shares the same
   * query and session reference but has its own cache.
   *
   * @return a new LazyQueryValue instance
   */
  public LazyQueryValue copy() {
    SessionRef<JFRSession> ref = sessionRef.get();
    if (ref == null) {
      throw new IllegalStateException("Cannot copy LazyQueryValue: session no longer available");
    }
    LazyQueryValue copy = new LazyQueryValue(query, ref, queryString);
    Object c = cache; // one read: the rows, or not evaluated
    if (!(c instanceof Unevaluated)) {
      copy.cache = c;
    }
    return copy;
  }

  @Override
  public Object get() throws Exception {
    Object c = cache;
    if (!(c instanceof Unevaluated)) {
      return c == NULL_RESULT ? null : c;
    }
    synchronized (this) {
      c = cache;
      if (!(c instanceof Unevaluated)) {
        return c == NULL_RESULT ? null : c;
      }
      SessionRef<JFRSession> ref = sessionRef.get();
      if (ref == null) {
        throw new IllegalStateException("Session no longer available (garbage collected)");
      }
      if (ref.session.isClosed()) {
        throw new IllegalStateException("Session has been closed");
      }
      JfrPathEvaluator evaluator = new JfrPathEvaluator();
      Object result = evaluator.evaluate(ref.session, query);
      // Only if nothing invalidated or released this value meanwhile; the caller still gets the
      // rows it asked for either way.
      CACHE.compareAndSet(this, c, result == null ? NULL_RESULT : result);
      return result;
    }
  }

  @Override
  public void invalidate() {
    cache = new Unevaluated();
  }

  /** Sets the cached result directly (used for eager evaluation). */
  public void setCachedResult(Object result) {
    cache = result == null ? NULL_RESULT : result;
  }

  @Override
  public boolean isCached() {
    return !(cache instanceof Unevaluated);
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
      return rowMap.isEmpty() ? null : rowMap.values().iterator().next();
    }
    return rowMap.get(field);
  }

  @Override
  public void release() {
    cache = new Unevaluated();
    sessionRef.clear();
  }

  @Override
  public String describe() {
    Object c = cache; // one read, so the status and the row count agree
    SessionRef<JFRSession> ref = sessionRef.get();
    String status;
    if (ref == null) {
      status = " (session gone)";
    } else if (ref.session.isClosed()) {
      status = " (closed)";
    } else if (!(c instanceof Unevaluated)) {
      int size = c instanceof List<?> list ? list.size() : 1;
      status = " (cached: " + size + " rows)";
    } else {
      status = " (not evaluated)";
    }
    return "lazy[" + queryString + "]" + status;
  }

  @Override
  public String getQueryString() {
    return queryString;
  }
}

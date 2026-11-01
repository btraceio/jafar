package io.jafar.shell.core.scripting;

import io.jafar.shell.core.QueryEvaluator;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager.SessionRef;
import io.jafar.shell.core.SessionResolver;
import io.jafar.shell.core.VariableStore;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A query-valued variable: {@code set big = objects[shallow > 1000]}, evaluated on first use by the
 * {@link QueryEvaluator} of the session it was defined against, and cached until invalidated.
 *
 * <p>It runs the query the way the shell's own {@code show} does — {@code evaluator.parse(query)}
 * at definition time, {@code evaluate(session, parsed, resolver)} on first access — so a variable
 * gives the same rows as typing its query, for every format with an evaluator (JFR, heap dump,
 * pprof, OTLP), cross-session joins included.
 *
 * <p>The variable is bound to the session that was current when it was defined, not to whichever
 * session is current when it is read: {@code set --global n = ...}, then {@code use 2}, still reads
 * session 1. Once that session is closed every read fails and says so, cached or not, and the cache
 * is dropped: rows can hold the session's own objects, so keeping them would keep a closed heap
 * dump in memory. The session itself is held weakly, as {@code jfr-shell}'s {@code LazyQueryValue}
 * does.
 *
 * <p>Thread-safe. The cache is one volatile slot, so a reader never sees a half-written state (a
 * "cached" flag from one evaluation and the rows of another); a cached read, {@link #describe} and
 * {@link #isCached} take no lock. Evaluation is serialized, so concurrent first reads run the query
 * once.
 *
 * <p>A query whose result is one map ({@link QueryEvaluator#producesMap}, i.e. JfrPath's {@code
 * toMap()}) stays lazy but is read as that map by {@link #asMap()}, so {@code ${v.size}} and {@code
 * ${v.key}} give what jfr-shell gives for the map it stores.
 */
public final class EvaluatorLazyValue implements VariableStore.LazyValue {
  /** The cache slot before evaluation, and after invalidation or release. */
  private static final Object NOT_EVALUATED = new Object();

  /** Stands for an evaluator that returned {@code null}, so null still counts as evaluated. */
  private static final Object NULL_RESULT = new Object();

  private final QueryEvaluator evaluator;
  private final Object query;
  private final String queryString;
  private final SessionResolver resolver;
  private final int sessionId;
  private final boolean mapQuery;
  private final WeakReference<SessionRef<? extends Session>> sessionRef;
  private final Object evaluationLock = new Object();
  private volatile Object cache = NOT_EVALUATED;

  /**
   * @param evaluator the evaluator of the session's module
   * @param query the parsed query, from {@code evaluator.parse(queryString)}
   * @param queryString the query as typed, for display
   * @param session the session to evaluate against
   * @param resolver resolves other sessions for cross-session operators; may be {@code null}
   */
  public EvaluatorLazyValue(
      QueryEvaluator evaluator,
      Object query,
      String queryString,
      SessionRef<? extends Session> session,
      SessionResolver resolver) {
    this(evaluator, query, queryString, session, resolver, NOT_EVALUATED);
  }

  private EvaluatorLazyValue(
      QueryEvaluator evaluator,
      Object query,
      String queryString,
      SessionRef<? extends Session> session,
      SessionResolver resolver,
      Object cache) {
    this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    this.query = query;
    this.queryString = queryString;
    this.resolver = resolver;
    this.sessionId = session.id;
    this.mapQuery = evaluator.producesMap(query);
    this.sessionRef = new WeakReference<>(session);
    this.cache = cache;
  }

  /**
   * A copy with its own cache, starting from this one's cached rows if it has them.
   *
   * @throws IllegalStateException if the session is gone or closed
   */
  public EvaluatorLazyValue copy() {
    SessionRef<? extends Session> ref = liveSession();
    return new EvaluatorLazyValue(evaluator, query, queryString, ref, resolver, cache);
  }

  @Override
  public Object get() throws Exception {
    liveSession();
    Object c = cache;
    if (c == NOT_EVALUATED) {
      synchronized (evaluationLock) {
        c = cache;
        if (c == NOT_EVALUATED) {
          SessionRef<? extends Session> ref = liveSession();
          Object result =
              resolver == null
                  ? evaluator.evaluate(ref.session, query)
                  : evaluator.evaluate(ref.session, query, resolver);
          c = result == null ? NULL_RESULT : result;
          cache = c;
        }
      }
    }
    return c == NULL_RESULT ? null : c;
  }

  /** The bound session, or an exception saying why it cannot be read; drops the cache if so. */
  private SessionRef<? extends Session> liveSession() {
    SessionRef<? extends Session> ref = sessionRef.get();
    if (ref == null) {
      cache = NOT_EVALUATED;
      throw new IllegalStateException("Session #" + sessionId + " no longer available");
    }
    if (ref.session.isClosed()) {
      cache = NOT_EVALUATED;
      throw new IllegalStateException("Session #" + sessionId + " has been closed");
    }
    return ref;
  }

  /**
   * The result as a map, when the query produces one ({@link QueryEvaluator#producesMap}) and the
   * evaluator returned the single map row that implies; {@code null} for any other query, so a
   * one-row result of an ordinary query is still read as rows.
   *
   * @throws Exception if evaluation fails, as {@link #get()}, which does the locking
   */
  public Map<String, Object> asMap() throws Exception {
    if (!mapQuery) {
      return null;
    }
    if (get() instanceof List<?> rows && rows.size() == 1 && rows.get(0) instanceof Map<?, ?> m) {
      @SuppressWarnings("unchecked")
      Map<String, Object> map = (Map<String, Object>) m;
      return map;
    }
    return null;
  }

  @Override
  public void invalidate() {
    cache = NOT_EVALUATED;
  }

  @Override
  public boolean isCached() {
    return cache != NOT_EVALUATED;
  }

  @Override
  public String getQueryString() {
    return queryString;
  }

  /** The id of the session this variable evaluates against. */
  public int sessionId() {
    return sessionId;
  }

  /**
   * The type of that session ({@code jfr}, {@code hdump}, ...), or {@code null} once it is gone.
   */
  public String sessionType() {
    SessionRef<? extends Session> ref = sessionRef.get();
    return ref == null ? null : ref.session.getType();
  }

  /**
   * Drops the cached rows of every variable in {@code store} bound to {@code sessionId}. A shell
   * calls this when it closes a session, so variables in other stores (the global one) stop holding
   * that session's rows at once rather than at their next read.
   */
  public static void forgetSession(VariableStore store, int sessionId) {
    for (String name : store.names()) {
      if (store.get(name) instanceof EvaluatorLazyValue lazy && lazy.sessionId == sessionId) {
        lazy.invalidate();
      }
    }
  }

  @Override
  public void release() {
    cache = NOT_EVALUATED;
    sessionRef.clear();
  }

  @Override
  public String describe() {
    SessionRef<? extends Session> ref = sessionRef.get();
    Object c = cache;
    String status;
    if (ref == null) {
      status = " (session gone)";
    } else if (ref.session.isClosed()) {
      status = " (closed)";
    } else if (c != NOT_EVALUATED) {
      int size = c instanceof List<?> list ? list.size() : 1;
      status = " (cached: " + size + " rows)";
    } else {
      status = " (not evaluated)";
    }
    return "lazy[" + queryString + "]" + status;
  }
}

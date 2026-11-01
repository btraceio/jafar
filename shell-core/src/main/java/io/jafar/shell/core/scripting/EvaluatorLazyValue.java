package io.jafar.shell.core.scripting;

import io.jafar.shell.core.QueryEvaluator;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager.SessionRef;
import io.jafar.shell.core.SessionResolver;
import io.jafar.shell.core.VariableStore;
import java.lang.ref.WeakReference;
import java.util.List;
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
 * session 1. The session is held weakly, as {@code jfr-shell}'s {@code LazyQueryValue} does, so a
 * variable does not keep a closed recording or heap dump in memory.
 */
public final class EvaluatorLazyValue implements VariableStore.LazyValue {
  private final QueryEvaluator evaluator;
  private final Object query;
  private final String queryString;
  private final SessionResolver resolver;
  private final int sessionId;
  private final WeakReference<SessionRef<? extends Session>> sessionRef;
  private Object cachedResult;
  private boolean evaluated;

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
    this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    this.query = query;
    this.queryString = queryString;
    this.resolver = resolver;
    this.sessionId = session.id;
    this.sessionRef = new WeakReference<>(session);
  }

  /** A copy with its own cache (holding the current cached result, if any). */
  public synchronized EvaluatorLazyValue copy() {
    SessionRef<? extends Session> ref = sessionRef.get();
    if (ref == null) {
      throw new IllegalStateException(
          "Cannot copy " + queryString + ": session no longer available");
    }
    EvaluatorLazyValue copy = new EvaluatorLazyValue(evaluator, query, queryString, ref, resolver);
    if (evaluated) {
      copy.cachedResult = cachedResult;
      copy.evaluated = true;
    }
    return copy;
  }

  @Override
  public synchronized Object get() throws Exception {
    if (evaluated) {
      return cachedResult;
    }
    SessionRef<? extends Session> ref = sessionRef.get();
    if (ref == null) {
      throw new IllegalStateException("Session #" + sessionId + " no longer available");
    }
    if (ref.session.isClosed()) {
      throw new IllegalStateException("Session #" + sessionId + " has been closed");
    }
    cachedResult =
        resolver == null
            ? evaluator.evaluate(ref.session, query)
            : evaluator.evaluate(ref.session, query, resolver);
    evaluated = true;
    return cachedResult;
  }

  @Override
  public synchronized void invalidate() {
    cachedResult = null;
    evaluated = false;
  }

  @Override
  public synchronized boolean isCached() {
    return evaluated;
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

  @Override
  public synchronized void release() {
    cachedResult = null;
    evaluated = false;
    sessionRef.clear();
  }

  @Override
  public synchronized String describe() {
    SessionRef<? extends Session> ref = sessionRef.get();
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
}

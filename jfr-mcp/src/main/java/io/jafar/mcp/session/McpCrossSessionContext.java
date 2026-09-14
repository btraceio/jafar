package io.jafar.mcp.session;

import io.jafar.shell.JFRSession;
import io.jafar.shell.JfrQueryEvaluator;
import io.jafar.shell.core.CrossSessionContext;
import io.jafar.shell.core.QueryEvaluator;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import java.util.Optional;

/**
 * Resolves sessions across the MCP server's per-format registries, and supplies the query evaluator
 * for a resolved session.
 *
 * <p>This is what makes cross-type joins reachable over MCP. {@code HdumpPathEvaluator} needs a
 * {@link CrossSessionContext} — not a bare {@code SessionResolver} — to run {@code
 * join(session=..., root="jdk.ObjectAllocationSample", by=class)}, because it has to evaluate a
 * JfrPath query against the *other* session. Without it the evaluator throws "Cross-type join
 * requires a CrossSessionContext", which made heap-to-JFR allocation correlation usable only from
 * the interactive shell.
 *
 * <p>Resolution order is heap first, then JFR: heap-to-heap diffs are the common case and heap
 * aliases are what a caller most often names. A reference that matches neither registry resolves to
 * empty, and the evaluator reports it as an unknown session.
 */
public final class McpCrossSessionContext implements CrossSessionContext {

  private final HeapSessionRegistry heapSessions;
  private final SessionRegistry jfrSessions;
  private final QueryEvaluator jfrEvaluator = new JfrQueryEvaluator();

  public McpCrossSessionContext(HeapSessionRegistry heapSessions, SessionRegistry jfrSessions) {
    this.heapSessions = heapSessions;
    this.jfrSessions = jfrSessions;
  }

  @Override
  public Optional<SessionManager.SessionRef<? extends Session>> resolve(String idOrAlias) {
    Optional<SessionManager.SessionRef<? extends Session>> heap =
        heapSessions
            .get(idOrAlias)
            .map(info -> new SessionManager.SessionRef<>(info.id(), info.alias(), info.session()));
    if (heap.isPresent()) {
      return heap;
    }
    return jfrSessions
        .get(idOrAlias)
        .map(info -> new SessionManager.SessionRef<>(info.id(), info.alias(), info.session()));
  }

  @Override
  public Optional<QueryEvaluator> evaluatorFor(Session session) {
    if (session instanceof JFRSession) {
      return Optional.of(jfrEvaluator);
    }
    // Heap sessions are evaluated by the caller (HdumpPathEvaluator itself); only the foreign
    // side of a cross-type join needs an evaluator from here.
    return Optional.empty();
  }
}

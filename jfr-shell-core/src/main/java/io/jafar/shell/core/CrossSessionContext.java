package io.jafar.shell.core;

import java.util.Optional;

/**
 * Extended session resolver that can also look up the appropriate {@link QueryEvaluator} for a
 * resolved session. This enables cross-type operations such as joining a heap session with a JFR
 * session.
 */
public interface CrossSessionContext extends SessionResolver {
  /**
   * Returns the query evaluator capable of querying the given session.
   *
   * @param session the session to find an evaluator for
   * @return the evaluator, or empty if no module handles this session type
   */
  Optional<QueryEvaluator> evaluatorFor(Session session);
}

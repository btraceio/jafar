package io.jafar.shell.cli;

/**
 * Thrown by {@link CommandDispatcher#dispatch(String)} when the condition of an {@code if} or
 * {@code elif} cannot be evaluated (a parse error, an unknown function, division by zero, a missing
 * condition).
 *
 * <p>The dispatcher has already updated its {@link ConditionalState} before throwing: the if-block
 * is entered with no branch active, so neither the failing branch nor any later {@code elif} or
 * {@code else} runs, and the matching {@code endif} still closes it. Callers decide what the
 * failure means for them — {@link ScriptRunner} records it as a command error (and stops unless
 * continue-on-error is set), the interactive shells print it.
 *
 * <p>It is deliberately not swallowed by the dispatcher's general error handler: a condition that
 * fails and silently selects the {@code else} branch looks exactly like a condition that was false.
 */
public final class ConditionEvaluationException extends RuntimeException {
  public ConditionEvaluationException(String message, Throwable cause) {
    super(message, cause);
  }
}

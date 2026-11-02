package io.jafar.shell.cli;

/**
 * Thrown by {@link CommandDispatcher#dispatch(String)} for an error in conditional control flow:
 *
 * <ul>
 *   <li>an {@code if} or {@code elif} whose condition cannot be evaluated (a parse error, a wrong
 *       number of function arguments, division by zero) or is missing;
 *   <li>an {@code elif}, {@code else} or {@code endif} with no open {@code if}.
 * </ul>
 *
 * <p>For a condition that fails, the dispatcher has already updated its {@link ConditionalState}
 * before throwing: the if-block is entered with no branch active, so neither the failing branch nor
 * any later {@code elif} or {@code else} runs, and the matching {@code endif} still closes it. A
 * keyword with no open {@code if} leaves the state unchanged.
 *
 * <p>Callers decide what the failure means for them — {@link ScriptRunner} records it as a command
 * error (and stops unless continue-on-error is set), the interactive shells print it. It is
 * deliberately not swallowed by the dispatcher's general error handler: a condition that fails and
 * silently selects the {@code else} branch looks exactly like a condition that was false, and a
 * stray {@code endif} that is only printed lets a broken script report success.
 */
public final class ConditionalException extends RuntimeException {
  public ConditionalException(String message, Throwable cause) {
    super(message, cause);
  }
}

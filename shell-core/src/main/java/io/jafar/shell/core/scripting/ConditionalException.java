package io.jafar.shell.core.scripting;

/**
 * Thrown by {@link Conditionals#handle} for an error in conditional control flow:
 *
 * <ul>
 *   <li>an {@code if} or {@code elif} whose condition cannot be evaluated (a parse error, a wrong
 *       number of function arguments, division by zero) or is missing;
 *   <li>an {@code elif}, {@code else} or {@code endif} with no open {@code if}.
 * </ul>
 *
 * <p>For a condition that fails, the {@link ConditionalState} has already been updated: the
 * if-block is entered with no branch active, so neither the failing branch nor any later {@code
 * elif} or {@code else} runs, and the matching {@code endif} still closes it. A keyword with no
 * open {@code if} leaves the state unchanged.
 *
 * <p>A shell must surface it as a failure of the line — {@link ScriptRunner} records an exception
 * from its line dispatcher as an error (and stops unless continue-on-error is set), and the
 * interactive shells print it. It must not be printed and treated as handled: a condition that
 * fails and silently selects the {@code else} branch looks exactly like a condition that was false,
 * and a stray {@code endif} that is only printed lets a broken script report success.
 */
public final class ConditionalException extends RuntimeException {
  public ConditionalException(String message, Throwable cause) {
    super(message, cause);
  }
}

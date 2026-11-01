package io.jafar.shell.core.scripting;

import java.util.function.Consumer;

/**
 * {@code if} / {@code elif} / {@code else} / {@code endif} for a line-at-a-time shell.
 *
 * <p>A shell passes every line to {@link #handle} first. A conditional keyword is consumed there
 * (even inside a branch that is not running, so nesting stays balanced); for any other line the
 * shell asks {@link #isActive} and skips the line when it is {@code false}.
 *
 * <p>A condition that cannot be evaluated is reported and counts as false; the block is still
 * entered, so the matching {@code endif} does not unbalance the stack. A condition is evaluated
 * only when its branch could run.
 *
 * <p>Not thread-safe: one instance per shell, used from the thread that dispatches its commands.
 */
public final class Conditionals {
  private final ConditionalState state = new ConditionalState();

  /** The underlying state, for prompts that show nesting depth and for resetting it. */
  public ConditionalState state() {
    return state;
  }

  /** Whether a non-conditional line should run now. */
  public boolean isActive() {
    return state.isActive();
  }

  /**
   * Handles a conditional keyword.
   *
   * @param line the input line
   * @param scopes variables the condition may reference
   * @param error receives messages about a missing or failing condition
   * @return {@code true} if the line was {@code if}, {@code elif}, {@code else} or {@code endif}
   * @throws IllegalStateException for {@code elif}, {@code else} or {@code endif} outside a block
   */
  public boolean handle(String line, VariableScopes scopes, Consumer<String> error) {
    // Called for every line a shell runs, so a line that is not a keyword allocates nothing.
    int start = 0;
    int end = line.length();
    while (start < end && Character.isWhitespace(line.charAt(start))) {
      start++;
    }
    int wordEnd = start;
    while (wordEnd < end && !Character.isWhitespace(line.charAt(wordEnd))) {
      wordEnd++;
    }
    int len = wordEnd - start;
    if (keyword(line, start, len, "if")) {
      String condition = line.substring(wordEnd).trim();
      if (condition.isEmpty()) {
        error.accept("if requires a condition");
        state.enterIf(false);
        return true;
      }
      // Only evaluate in a running branch; a nested if under a skipped one is skipped whole.
      boolean result = state.isActive() && evaluate(condition, scopes, error);
      // Always update state, even after errors
      state.enterIf(result);
      return true;
    }
    if (keyword(line, start, len, "elif")) {
      String condition = line.substring(wordEnd).trim();
      if (condition.isEmpty()) {
        error.accept("elif requires a condition");
        state.handleElif(false);
        return true;
      }
      // Only when this branch could still be taken; after a taken branch, or under a skipped
      // block, the condition is irrelevant and evaluating it may run a query or report an error.
      boolean result = state.awaitingBranch() && evaluate(condition, scopes, error);
      state.handleElif(result);
      return true;
    }
    if (keyword(line, start, len, "else")) {
      state.handleElse();
      return true;
    }
    if (keyword(line, start, len, "endif")) {
      state.exitIf();
      return true;
    }
    return false;
  }

  /**
   * Leaves every block opened after {@code depth}, so that a script that ends inside an if-block
   * does not leave the shell skipping the commands that follow it.
   *
   * @return how many blocks were closed
   */
  public int unwindTo(int depth) {
    int closed = 0;
    while (state.depth() > depth) {
      state.exitIf();
      closed++;
    }
    return closed;
  }

  private static boolean keyword(String line, int start, int len, String keyword) {
    return len == keyword.length() && line.regionMatches(true, start, keyword, 0, len);
  }

  private static boolean evaluate(String condition, VariableScopes scopes, Consumer<String> error) {
    try {
      return new ConditionEvaluator(scopes.session(), scopes.global()).evaluate(condition);
    } catch (Exception e) {
      error.accept("Condition evaluation failed: " + e.getMessage());
      return false;
    }
  }
}

package io.jafar.shell.core.scripting;

/**
 * {@code if} / {@code elif} / {@code else} / {@code endif} for a line-at-a-time shell.
 *
 * <p>A shell passes every line to {@link #handle} first. A conditional keyword is consumed there
 * (even inside a branch that is not running, so nesting stays balanced); for any other line the
 * shell asks {@link #isActive} and skips the line when it is {@code false}.
 *
 * <p>A condition that cannot be evaluated (or is missing) is an error, not a {@code false}: the
 * block is entered with no branch active — neither the failing branch nor any later {@code elif} or
 * {@code else} runs — and {@link #handle} throws {@link ConditionalException}, which the shell must
 * treat as a failure of the line. The matching {@code endif} still closes the block. A condition is
 * evaluated only when its branch could run; a missing one is reported either way.
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
   * @return {@code true} if the line was {@code if}, {@code elif}, {@code else} or {@code endif}
   * @throws ConditionalException for a missing or failing condition (after the state has been
   *     updated so that no branch of the block runs), or for {@code elif}, {@code else} or {@code
   *     endif} outside a block
   */
  public boolean handle(String line, VariableScopes scopes) {
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
      // Only evaluate in a running branch; a nested if under a skipped one is skipped whole.
      if (!state.isActive()) {
        state.enterIf(false);
        if (condition.isEmpty()) {
          throw new ConditionalException("if requires a condition", null);
        }
        return true;
      }
      boolean result;
      try {
        result = evaluate("if", condition, scopes);
      } catch (ConditionalException e) {
        // Still entered, so its elif/else/endif pair up; no branch of it runs.
        state.enterIfWithError();
        throw e;
      }
      state.enterIf(result);
      return true;
    }
    if (keyword(line, start, len, "elif")) {
      String condition = line.substring(wordEnd).trim();
      requireOpenIf("elif");
      // Only when this branch could still be taken; after a taken branch, or under a skipped
      // block, the condition is irrelevant and evaluating it may run a query or report an error.
      if (!state.awaitingBranch()) {
        state.handleElif(false);
        if (condition.isEmpty()) {
          throw new ConditionalException("elif requires a condition", null);
        }
        return true;
      }
      boolean result;
      try {
        result = evaluate("elif", condition, scopes);
      } catch (ConditionalException e) {
        state.handleElifWithError();
        throw e;
      }
      state.handleElif(result);
      return true;
    }
    if (keyword(line, start, len, "else")) {
      requireOpenIf("else");
      state.handleElse();
      return true;
    }
    if (keyword(line, start, len, "endif")) {
      requireOpenIf("endif");
      state.exitIf();
      return true;
    }
    return false;
  }

  private void requireOpenIf(String keyword) {
    if (!state.inConditional()) {
      throw new ConditionalException(keyword + " without if", null);
    }
  }

  private static boolean keyword(String line, int start, int len, String keyword) {
    return len == keyword.length() && line.regionMatches(true, start, keyword, 0, len);
  }

  private static boolean evaluate(String keyword, String condition, VariableScopes scopes) {
    if (condition.isEmpty()) {
      throw new ConditionalException(
          keyword + " requires a condition; skipping every branch of this if-block", null);
    }
    try {
      return new ConditionEvaluator(scopes.session(), scopes.global()).evaluate(condition);
    } catch (Exception e) {
      String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
      throw new ConditionalException(
          "Condition evaluation failed: " + reason + "; skipping every branch of this if-block", e);
    }
  }
}

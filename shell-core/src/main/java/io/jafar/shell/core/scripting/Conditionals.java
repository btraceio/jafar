package io.jafar.shell.core.scripting;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * {@code if} / {@code elif} / {@code else} / {@code endif} for a line-at-a-time shell.
 *
 * <p>A shell passes every line to {@link #handle} first. A conditional keyword is consumed there
 * (even inside a branch that is not running, so nesting stays balanced); for any other line the
 * shell asks {@link #isActive} and skips the line when it is {@code false}.
 *
 * <p>A condition that cannot be evaluated is reported and counts as false; the block is still
 * entered, so the matching {@code endif} does not unbalance the stack.
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
    String trimmed = line.trim();
    String[] parts = trimmed.split("\\s+", 2);
    String keyword = parts[0].toLowerCase(Locale.ROOT);
    String condition = parts.length > 1 ? parts[1].trim() : "";
    switch (keyword) {
      case "if" -> {
        if (condition.isEmpty()) {
          error.accept("if requires a condition");
          state.enterIf(false);
          return true;
        }
        // Only evaluate if we're in an active branch
        boolean result = false;
        if (state.isActive() || !state.inConditional()) {
          result = evaluate(condition, scopes, error);
        }
        // Always update state, even after errors
        state.enterIf(result);
        return true;
      }
      case "elif" -> {
        if (condition.isEmpty()) {
          error.accept("elif requires a condition");
          state.handleElif(false);
          return true;
        }
        boolean result = false;
        if (state.depth() > 0) {
          result = evaluate(condition, scopes, error);
        }
        state.handleElif(result);
        return true;
      }
      case "else" -> {
        state.handleElse();
        return true;
      }
      case "endif" -> {
        state.exitIf();
        return true;
      }
      default -> {
        return false;
      }
    }
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

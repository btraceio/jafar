package io.jafar.shell.cli;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Tracks the state of nested if-blocks for conditional execution. Uses a stack to handle arbitrary
 * nesting of if/elif/else/endif blocks.
 */
public final class ConditionalState {

  /** The state of a branch within an if-block. */
  public enum BranchState {
    /** This branch's condition was true and it is executing. */
    CONDITION_MET,
    /** This branch's condition was false, waiting for elif/else. */
    CONDITION_FAILED,
    /** A previous branch in this if-block already executed, skip remaining. */
    BRANCH_TAKEN,
    /**
     * A condition in this if-block could not be evaluated. No branch of the block runs: not the one
     * whose condition failed, and not any later {@code elif} or {@code else}, since choosing one
     * would mean guessing what the condition would have been.
     */
    CONDITION_ERROR
  }

  private record IfBlock(BranchState state) {}

  private final Deque<IfBlock> stack = new ArrayDeque<>();

  /**
   * Returns whether commands should currently be executed. Returns true if we're not in any
   * conditional block, or if the current branch is active.
   */
  public boolean isActive() {
    if (stack.isEmpty()) {
      return true;
    }
    return stack.peek().state == BranchState.CONDITION_MET;
  }

  /**
   * Enters a new if-block.
   *
   * @param condition the result of evaluating the if condition
   */
  public void enterIf(boolean condition) {
    if (!isActive()) {
      // Parent block is inactive, so this entire if-block is skipped
      stack.push(new IfBlock(BranchState.BRANCH_TAKEN));
    } else {
      stack.push(new IfBlock(condition ? BranchState.CONDITION_MET : BranchState.CONDITION_FAILED));
    }
  }

  /**
   * Enters a new if-block whose condition could not be evaluated. Every branch of the block is
   * skipped until the matching {@code endif}. (Inside an already-skipped branch this is equally
   * correct: a {@link BranchState#CONDITION_ERROR} block never becomes active.)
   */
  public void enterIfWithError() {
    stack.push(new IfBlock(BranchState.CONDITION_ERROR));
  }

  /**
   * Returns whether the condition of an {@code elif} at the current position would decide anything:
   * true only when inside an if-block whose branches so far have all been false. When a branch has
   * already run, the block is skipped, or a condition has failed, the {@code elif} condition must
   * not be evaluated.
   */
  public boolean elifNeedsEvaluation() {
    return !stack.isEmpty() && stack.peek().state == BranchState.CONDITION_FAILED;
  }

  /**
   * Handles an elif statement whose condition could not be evaluated. The remaining branches of the
   * block, including any {@code else}, are skipped.
   *
   * @throws IllegalStateException if not inside an if-block
   */
  public void handleElifWithError() {
    if (stack.isEmpty()) {
      throw new IllegalStateException("elif without if");
    }
    // Whatever the block's state was, nothing in it may run from here on.
    stack.pop();
    stack.push(new IfBlock(BranchState.CONDITION_ERROR));
  }

  /**
   * Handles an elif statement.
   *
   * @param condition the result of evaluating the elif condition
   * @throws IllegalStateException if not inside an if-block
   */
  public void handleElif(boolean condition) {
    if (stack.isEmpty()) {
      throw new IllegalStateException("elif without if");
    }
    IfBlock current = stack.pop();
    if (current.state != BranchState.CONDITION_FAILED) {
      // A branch already executed, or a condition failed: skip this elif
      stack.push(new IfBlock(skipState(current.state)));
    } else {
      // No branch executed yet, check this condition
      stack.push(new IfBlock(condition ? BranchState.CONDITION_MET : BranchState.CONDITION_FAILED));
    }
  }

  /**
   * Handles an else statement.
   *
   * @throws IllegalStateException if not inside an if-block
   */
  public void handleElse() {
    if (stack.isEmpty()) {
      throw new IllegalStateException("else without if");
    }
    IfBlock current = stack.pop();
    if (current.state != BranchState.CONDITION_FAILED) {
      // A branch already executed, or a condition failed: skip else
      stack.push(new IfBlock(skipState(current.state)));
    } else {
      // No branch executed, else is active
      stack.push(new IfBlock(BranchState.CONDITION_MET));
    }
  }

  /** Keeps a failed condition visible for the rest of its block; anything else is "taken". */
  private static BranchState skipState(BranchState current) {
    return current == BranchState.CONDITION_ERROR
        ? BranchState.CONDITION_ERROR
        : BranchState.BRANCH_TAKEN;
  }

  /**
   * Exits the current if-block.
   *
   * @throws IllegalStateException if not inside an if-block
   */
  public void exitIf() {
    if (stack.isEmpty()) {
      throw new IllegalStateException("endif without if");
    }
    stack.pop();
  }

  /**
   * Returns whether we are currently inside any if-block.
   *
   * @return true if inside a conditional block
   */
  public boolean inConditional() {
    return !stack.isEmpty();
  }

  /**
   * Returns the nesting depth of conditionals.
   *
   * @return the number of nested if-blocks
   */
  public int depth() {
    return stack.size();
  }

  /**
   * Closes if-blocks until at most {@code depth} remain. Used to discard blocks a script opened and
   * did not close, so they do not leak into the session that ran it.
   *
   * @param depth the nesting depth to return to
   */
  public void unwindTo(int depth) {
    while (stack.size() > depth) {
      stack.pop();
    }
  }

  /** Resets the conditional state, clearing all if-blocks. */
  public void reset() {
    stack.clear();
  }
}

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
    BRANCH_TAKEN
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
    if (current.state == BranchState.BRANCH_TAKEN || current.state == BranchState.CONDITION_MET) {
      // A branch already executed, skip this elif
      stack.push(new IfBlock(BranchState.BRANCH_TAKEN));
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
    if (current.state == BranchState.BRANCH_TAKEN || current.state == BranchState.CONDITION_MET) {
      // A branch already executed, skip else
      stack.push(new IfBlock(BranchState.BRANCH_TAKEN));
    } else {
      // No branch executed, else is active
      stack.push(new IfBlock(BranchState.CONDITION_MET));
    }
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

  /** Resets the conditional state, clearing all if-blocks. */
  public void reset() {
    stack.clear();
  }
}

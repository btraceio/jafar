package io.jafar.shell.cli.completion.stateful;

import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.VariableStore;
import java.nio.file.Path;

/**
 * Represents state-changing operations that can be performed on a session.
 *
 * <p>These actions are used to test completion behavior across session lifecycle changes, including
 * opening/closing sessions, switching between sessions, and variable operations.
 */
public sealed interface SessionAction {

  /**
   * Executes this action on the given session manager.
   *
   * @param sessionManager the session manager
   * @param variableStore the variable store for variable operations
   * @throws Exception if the action fails
   */
  void execute(SessionManager sessionManager, VariableStore variableStore) throws Exception;

  /** Returns a description of this action for debugging. */
  String describe();

  // ==================== Session Lifecycle Actions ====================

  /** Opens a JFR recording file. */
  record OpenRecording(Path path, String alias) implements SessionAction {
    @Override
    public void execute(SessionManager sessionManager, VariableStore variableStore)
        throws Exception {
      sessionManager.open(path, alias);
    }

    @Override
    public String describe() {
      return "open(" + path + ", alias=" + alias + ")";
    }
  }

  /** Closes a session by alias. */
  record CloseSession(String alias) implements SessionAction {
    @Override
    public void execute(SessionManager sessionManager, VariableStore variableStore)
        throws Exception {
      sessionManager.close(alias);
    }

    @Override
    public String describe() {
      return "close(" + alias + ")";
    }
  }

  /** Switches to a different session by alias. */
  record SwitchSession(String alias) implements SessionAction {
    @Override
    public void execute(SessionManager sessionManager, VariableStore variableStore)
        throws Exception {
      sessionManager.use(alias);
    }

    @Override
    public String describe() {
      return "use(" + alias + ")";
    }
  }

  // ==================== Variable Actions ====================

  /** Sets a string variable. */
  record SetVariable(String name, String value) implements SessionAction {
    @Override
    public void execute(SessionManager sessionManager, VariableStore variableStore) {
      variableStore.set(name, new VariableStore.ScalarValue(value));
    }

    @Override
    public String describe() {
      return "set " + name + " = \"" + value + "\"";
    }
  }

  /** Sets a numeric variable. */
  record SetNumericVariable(String name, Number value) implements SessionAction {
    @Override
    public void execute(SessionManager sessionManager, VariableStore variableStore) {
      variableStore.set(name, new VariableStore.ScalarValue(value));
    }

    @Override
    public String describe() {
      return "set " + name + " = " + value;
    }
  }

  /** Removes a variable. */
  record UnsetVariable(String name) implements SessionAction {
    @Override
    public void execute(SessionManager sessionManager, VariableStore variableStore) {
      variableStore.remove(name);
    }

    @Override
    public String describe() {
      return "unset " + name;
    }
  }

  // ==================== Cache Actions ====================

  /** Invalidates the metadata cache. */
  record InvalidateCache() implements SessionAction {
    @Override
    public void execute(SessionManager sessionManager, VariableStore variableStore) {
      // Metadata cache invalidation happens automatically on session changes
      // This is a marker action for test sequences
    }

    @Override
    public String describe() {
      return "invalidate";
    }
  }

  // ==================== Compound Actions ====================

  /** A sequence of actions executed together. */
  record ActionSequence(java.util.List<SessionAction> actions) implements SessionAction {
    @Override
    public void execute(SessionManager sessionManager, VariableStore variableStore)
        throws Exception {
      for (SessionAction action : actions) {
        action.execute(sessionManager, variableStore);
      }
    }

    @Override
    public String describe() {
      return "sequence["
          + actions.stream()
              .map(SessionAction::describe)
              .reduce((a, b) -> a + " -> " + b)
              .orElse("empty")
          + "]";
    }
  }

  /** A no-op action for testing. */
  record NoOp() implements SessionAction {
    @Override
    public void execute(SessionManager sessionManager, VariableStore variableStore) {
      // Do nothing
    }

    @Override
    public String describe() {
      return "no-op";
    }
  }
}

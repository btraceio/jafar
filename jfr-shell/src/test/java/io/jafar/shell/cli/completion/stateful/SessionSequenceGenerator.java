package io.jafar.shell.cli.completion.stateful;

import io.jafar.shell.cli.completion.stateful.SessionAction.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

/**
 * Generates realistic session action sequences for stateful completion testing.
 *
 * <p>The generator creates sequences that simulate real user workflows: open file → run commands →
 * switch sessions → close sessions.
 */
public class SessionSequenceGenerator {

  private final Path testJfrPath;
  private final List<String> availableAliases;

  public SessionSequenceGenerator(Path testJfrPath) {
    this.testJfrPath = testJfrPath;
    this.availableAliases = new ArrayList<>();
  }

  // ==================== Session Lifecycle Arbitraries ====================

  /** Generates an open recording action. */
  public Arbitrary<OpenRecording> openRecording() {
    return Arbitraries.strings()
        .alpha()
        .ofMinLength(3)
        .ofMaxLength(10)
        .map(
            alias -> {
              availableAliases.add(alias);
              return new OpenRecording(testJfrPath, alias);
            });
  }

  /** Generates a close session action (only if sessions exist). */
  public Arbitrary<CloseSession> closeSession() {
    return Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(10).map(CloseSession::new);
  }

  /** Generates a switch session action. */
  public Arbitrary<SwitchSession> switchSession() {
    return Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(10).map(SwitchSession::new);
  }

  // ==================== Variable Arbitraries ====================

  /** Generates a set string variable action. */
  public Arbitrary<SetVariable> setStringVariable() {
    return Combinators.combine(
            variableName(), Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20))
        .as(SetVariable::new);
  }

  /** Generates a set numeric variable action. */
  public Arbitrary<SetNumericVariable> setNumericVariable() {
    return Combinators.combine(variableName(), Arbitraries.integers().between(0, 10000))
        .as((name, num) -> new SetNumericVariable(name, num));
  }

  /** Generates an unset variable action. */
  public Arbitrary<UnsetVariable> unsetVariable() {
    return variableName().map(UnsetVariable::new);
  }

  /** Generates a variable name. */
  public Arbitrary<String> variableName() {
    return Arbitraries.strings().alpha().ofMinLength(2).ofMaxLength(15).map(s -> s.toLowerCase());
  }

  // ==================== Sequence Generators ====================

  /** Generates a simple workflow: open → actions → optional close. */
  public Arbitrary<List<SessionAction>> simpleWorkflow() {
    return Combinators.combine(openRecording(), middleActions(1, 3), Arbitraries.of(true, false))
        .as(
            (open, middle, shouldClose) -> {
              List<SessionAction> actions = new ArrayList<>();
              actions.add(open);
              actions.addAll(middle);
              if (shouldClose) {
                actions.add(new CloseSession(open.alias()));
              }
              return actions;
            });
  }

  /** Generates middle actions (variables, cache operations). */
  public Arbitrary<List<SessionAction>> middleActions(int min, int max) {
    Arbitrary<SessionAction> actionArbitrary =
        Arbitraries.oneOf(
            setStringVariable().map(a -> (SessionAction) a),
            setNumericVariable().map(a -> (SessionAction) a),
            unsetVariable().map(a -> (SessionAction) a),
            Arbitraries.just((SessionAction) new InvalidateCache()),
            Arbitraries.just((SessionAction) new NoOp()));
    return actionArbitrary.list().ofMinSize(min).ofMaxSize(max);
  }

  /** Generates a multi-session workflow: open multiple → switch between → close some. */
  public Arbitrary<List<SessionAction>> multiSessionWorkflow() {
    return Combinators.combine(
            openRecording(), openRecording(), middleActions(0, 2), Arbitraries.of(true, false))
        .as(
            (open1, open2, middle, switchBack) -> {
              List<SessionAction> actions = new ArrayList<>();
              actions.add(open1);
              actions.add(open2);
              actions.addAll(middle);
              if (switchBack) {
                actions.add(new SwitchSession(open1.alias()));
              }
              return actions;
            });
  }

  /** Generates a variable-heavy workflow for testing variable completion. */
  public Arbitrary<List<SessionAction>> variableWorkflow() {
    return Combinators.combine(
            openRecording(),
            setStringVariable(),
            setStringVariable(),
            setNumericVariable(),
            unsetVariable())
        .as(
            (open, var1, var2, var3, unset) -> {
              List<SessionAction> actions = new ArrayList<>();
              actions.add(open);
              actions.add(var1);
              actions.add(var2);
              actions.add(var3);
              actions.add(unset);
              return actions;
            });
  }

  /** Generates a cache invalidation workflow. */
  public Arbitrary<List<SessionAction>> cacheInvalidationWorkflow() {
    return Combinators.combine(
            openRecording(), Arbitraries.just(new InvalidateCache()), middleActions(0, 2))
        .as(
            (open, invalidate, middle) -> {
              List<SessionAction> actions = new ArrayList<>();
              actions.add(open);
              actions.add(invalidate);
              actions.addAll(middle);
              return actions;
            });
  }

  /** Generates any type of workflow. */
  public Arbitrary<List<SessionAction>> anyWorkflow() {
    return Arbitraries.oneOf(
        simpleWorkflow(),
        simpleWorkflow(), // 2x weight
        multiSessionWorkflow(),
        variableWorkflow(),
        cacheInvalidationWorkflow());
  }

  // ==================== Completion Scenario Generators ====================

  /** A completion scenario with state setup and completion check. */
  public record CompletionScenario(
      List<SessionAction> setupActions,
      String expression,
      int cursorPosition,
      String expectedContextType) {

    public String describe() {
      StringBuilder sb = new StringBuilder();
      sb.append("Setup:\n");
      for (SessionAction action : setupActions) {
        sb.append("  ").append(action.describe()).append("\n");
      }
      sb.append("Expression: ").append(expression).append("\n");
      sb.append("Cursor: ").append(cursorPosition).append("\n");
      sb.append("Expected: ").append(expectedContextType);
      return sb.toString();
    }
  }

  /** Generates a completion scenario with workflow setup. */
  public Arbitrary<CompletionScenario> completionScenario() {
    return anyWorkflow()
        .flatMap(
            actions ->
                Arbitraries.oneOf(
                    // Filter completion scenarios
                    Arbitraries.just(
                        new CompletionScenario(
                            actions, "events/jdk.ExecutionSample[", 27, "FILTER_FIELD")),
                    // Pipeline completion scenarios
                    Arbitraries.just(
                        new CompletionScenario(
                            actions, "events/jdk.ExecutionSample | ", 29, "PIPELINE_OPERATOR")),
                    // Field path completion scenarios
                    Arbitraries.just(
                        new CompletionScenario(
                            actions, "events/jdk.ExecutionSample/", 27, "FIELD_PATH")),
                    // Root completion scenarios
                    Arbitraries.just(new CompletionScenario(actions, "", 0, "ROOT"))));
  }

  /** Generates a variable completion scenario. */
  public Arbitrary<CompletionScenario> variableCompletionScenario() {
    return variableWorkflow()
        .map(
            actions -> {
              // Find a set variable action to reference
              String varName =
                  actions.stream()
                      .filter(a -> a instanceof SetVariable)
                      .map(a -> ((SetVariable) a).name())
                      .findFirst()
                      .orElse("myvar");

              return new CompletionScenario(
                  actions,
                  "echo ${" + varName.substring(0, Math.min(2, varName.length())),
                  8,
                  "VARIABLE");
            });
  }

  /** Generates a session switch completion scenario. */
  public Arbitrary<CompletionScenario> sessionSwitchScenario() {
    return multiSessionWorkflow()
        .flatMap(
            actions -> {
              // Add a switch action and then check completion
              List<SessionAction> actionsWithSwitch = new ArrayList<>(actions);

              return Arbitraries.of(
                  new CompletionScenario(
                      actionsWithSwitch, "events/jdk.ExecutionSample/", 27, "FIELD_PATH"),
                  new CompletionScenario(
                      actionsWithSwitch, "events/jdk.ExecutionSample[", 27, "FILTER_FIELD"));
            });
  }
}

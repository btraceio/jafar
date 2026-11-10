package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.scripting.ScriptRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class ScriptRunnerTest {

  @Test
  void testPositionalParameterSubstitution(@TempDir Path tempDir) throws IOException {
    // Create a simple script with positional parameter substitution
    Path scriptPath = tempDir.resolve("test.jfrs");
    Files.writeString(
        scriptPath,
        """
        # Comment line

        # Use positional parameters
        test $1 $2
        """);

    List<String> arguments = new ArrayList<>();
    arguments.add("value1");
    arguments.add("value2");

    StringBuilder capturedCommands = new StringBuilder();
    CommandDispatcher mockDispatcher =
        new CommandDispatcher(null, null, null) {
          @Override
          public boolean dispatch(String line) {
            capturedCommands.append(line).append("\n");
            return true;
          }
        };

    CommandDispatcher.IO mockIO =
        new CommandDispatcher.IO() {
          @Override
          public void println(String s) {}

          @Override
          public void printf(String fmt, Object... args) {}

          @Override
          public void error(String s) {}
        };

    ScriptRunner runner = new ScriptRunner(mockDispatcher::dispatch, arguments);
    ScriptRunner.ExecutionResult result = runner.execute(scriptPath);

    assertEquals(1, result.getSuccessCount());
    assertEquals(0, result.getErrors().size());
    assertEquals("test value1 value2\n", capturedCommands.toString());
  }

  @Test
  void testAllParametersExpansion(@TempDir Path tempDir) throws IOException {
    // Test $@ expansion
    Path scriptPath = tempDir.resolve("test.jfrs");
    Files.writeString(
        scriptPath,
        """
        # Test all parameters
        test $@
        """);

    List<String> arguments = new ArrayList<>();
    arguments.add("arg1");
    arguments.add("arg2");
    arguments.add("arg3");

    StringBuilder capturedCommands = new StringBuilder();
    CommandDispatcher mockDispatcher =
        new CommandDispatcher(null, null, null) {
          @Override
          public boolean dispatch(String line) {
            capturedCommands.append(line).append("\n");
            return true;
          }
        };

    CommandDispatcher.IO mockIO =
        new CommandDispatcher.IO() {
          @Override
          public void println(String s) {}

          @Override
          public void printf(String fmt, Object... args) {}

          @Override
          public void error(String s) {}
        };

    ScriptRunner runner = new ScriptRunner(mockDispatcher::dispatch, arguments);
    ScriptRunner.ExecutionResult result = runner.execute(scriptPath);

    assertEquals(1, result.getSuccessCount());
    assertEquals(0, result.getErrors().size());
    assertEquals("test arg1 arg2 arg3\n", capturedCommands.toString());
  }

  @Test
  void testOutOfBoundsParameter(@TempDir Path tempDir) throws IOException {
    Path scriptPath = tempDir.resolve("test.jfrs");
    Files.writeString(scriptPath, "test $1 $2\n");

    CommandDispatcher mockDispatcher =
        new CommandDispatcher(null, null, null) {
          @Override
          public boolean dispatch(String line) {
            return true;
          }
        };

    CommandDispatcher.IO mockIO =
        new CommandDispatcher.IO() {
          @Override
          public void println(String s) {}

          @Override
          public void printf(String fmt, Object... args) {}

          @Override
          public void error(String s) {}
        };

    ScriptRunner runner = new ScriptRunner(mockDispatcher::dispatch, new ArrayList<>());
    ScriptRunner.ExecutionResult result = runner.execute(scriptPath);

    assertEquals(0, result.getSuccessCount());
    assertEquals(1, result.getErrors().size());
    assertTrue(
        result.getErrors().get(0).getMessage().contains("Positional parameter $1 out of bounds"));
  }

  @Test
  void testCommentAndBlankLines(@TempDir Path tempDir) throws IOException {
    Path scriptPath = tempDir.resolve("test.jfrs");
    Files.writeString(
        scriptPath,
        """
        # This is a comment

        command1

        # Another comment
        command2
        """);

    int[] commandCount = {0};
    CommandDispatcher mockDispatcher =
        new CommandDispatcher(null, null, null) {
          @Override
          public boolean dispatch(String line) {
            commandCount[0]++;
            return true;
          }
        };

    CommandDispatcher.IO mockIO =
        new CommandDispatcher.IO() {
          @Override
          public void println(String s) {}

          @Override
          public void printf(String fmt, Object... args) {}

          @Override
          public void error(String s) {}
        };

    ScriptRunner runner = new ScriptRunner(mockDispatcher::dispatch, new ArrayList<>());
    ScriptRunner.ExecutionResult result = runner.execute(scriptPath);

    assertEquals(2, result.getSuccessCount());
    assertEquals(2, commandCount[0]);
  }

  @Test
  void testContinueOnError(@TempDir Path tempDir) throws IOException {
    Path scriptPath = tempDir.resolve("test.jfrs");
    Files.writeString(
        scriptPath,
        """
        command1
        command2
        command3
        """);

    int[] commandCount = {0};
    CommandDispatcher mockDispatcher =
        new CommandDispatcher(null, null, null) {
          @Override
          public boolean dispatch(String line) {
            commandCount[0]++;
            if (line.equals("command2")) {
              throw new RuntimeException("Test error");
            }
            return true;
          }
        };

    CommandDispatcher.IO mockIO =
        new CommandDispatcher.IO() {
          @Override
          public void println(String s) {}

          @Override
          public void printf(String fmt, Object... args) {}

          @Override
          public void error(String s) {}
        };

    // Test with continueOnError = true
    ScriptRunner runner = new ScriptRunner(mockDispatcher::dispatch, new ArrayList<>());
    runner.setContinueOnError(true);
    ScriptRunner.ExecutionResult result = runner.execute(scriptPath);

    assertEquals(2, result.getSuccessCount());
    assertEquals(1, result.getErrors().size());
    assertEquals(3, commandCount[0]); // All commands attempted

    // Test with continueOnError = false (default)
    commandCount[0] = 0;
    ScriptRunner runner2 = new ScriptRunner(mockDispatcher::dispatch, new ArrayList<>());
    ScriptRunner.ExecutionResult result2 = runner2.execute(scriptPath);

    assertEquals(1, result2.getSuccessCount());
    assertEquals(1, result2.getErrors().size());
    assertEquals(2, commandCount[0]); // Stopped after error
  }

  // ==================== Optional Parameter Tests ====================

  @Test
  void testOptionalParameterWithDefault(@TempDir Path tempDir) throws IOException {
    Path scriptPath = tempDir.resolve("test.jfrs");
    Files.writeString(scriptPath, "test ${2:-default}\n");

    StringBuilder capturedCommands = new StringBuilder();
    CommandDispatcher mockDispatcher =
        new CommandDispatcher(null, null, null) {
          @Override
          public boolean dispatch(String line) {
            capturedCommands.append(line).append("\n");
            return true;
          }
        };

    CommandDispatcher.IO mockIO =
        new CommandDispatcher.IO() {
          @Override
          public void println(String s) {}

          @Override
          public void printf(String fmt, Object... args) {}

          @Override
          public void error(String s) {}
        };

    List<String> arguments = new ArrayList<>();
    arguments.add("arg1");

    ScriptRunner runner = new ScriptRunner(mockDispatcher::dispatch, arguments);
    ScriptRunner.ExecutionResult result = runner.execute(scriptPath);

    assertEquals(1, result.getSuccessCount());
    assertEquals(0, result.getErrors().size());
    assertEquals("test default\n", capturedCommands.toString());
  }

  @Test
  void testOptionalParameterProvided(@TempDir Path tempDir) throws IOException {
    Path scriptPath = tempDir.resolve("test.jfrs");
    Files.writeString(scriptPath, "test ${2:-default}\n");

    StringBuilder capturedCommands = new StringBuilder();
    CommandDispatcher mockDispatcher =
        new CommandDispatcher(null, null, null) {
          @Override
          public boolean dispatch(String line) {
            capturedCommands.append(line).append("\n");
            return true;
          }
        };

    CommandDispatcher.IO mockIO =
        new CommandDispatcher.IO() {
          @Override
          public void println(String s) {}

          @Override
          public void printf(String fmt, Object... args) {}

          @Override
          public void error(String s) {}
        };

    List<String> arguments = new ArrayList<>();
    arguments.add("arg1");
    arguments.add("arg2");

    ScriptRunner runner = new ScriptRunner(mockDispatcher::dispatch, arguments);
    ScriptRunner.ExecutionResult result = runner.execute(scriptPath);

    assertEquals(1, result.getSuccessCount());
    assertEquals(0, result.getErrors().size());
    assertEquals("test arg2\n", capturedCommands.toString());
  }

  @Test
  void testOptionalParameterEmpty(@TempDir Path tempDir) throws IOException {
    Path scriptPath = tempDir.resolve("test.jfrs");
    Files.writeString(scriptPath, "test ${2}\n");

    StringBuilder capturedCommands = new StringBuilder();
    CommandDispatcher mockDispatcher =
        new CommandDispatcher(null, null, null) {
          @Override
          public boolean dispatch(String line) {
            capturedCommands.append(line).append("\n");
            return true;
          }
        };

    CommandDispatcher.IO mockIO =
        new CommandDispatcher.IO() {
          @Override
          public void println(String s) {}

          @Override
          public void printf(String fmt, Object... args) {}

          @Override
          public void error(String s) {}
        };

    List<String> arguments = new ArrayList<>();
    arguments.add("arg1");

    ScriptRunner runner = new ScriptRunner(mockDispatcher::dispatch, arguments);
    ScriptRunner.ExecutionResult result = runner.execute(scriptPath);

    assertEquals(1, result.getSuccessCount());
    assertEquals(0, result.getErrors().size());
    assertEquals("test \n", capturedCommands.toString());
  }

  @Test
  void testRequiredParameterWithCustomError(@TempDir Path tempDir) throws IOException {
    Path scriptPath = tempDir.resolve("test.jfrs");
    Files.writeString(scriptPath, "test ${1:?recording file required}\n");

    CommandDispatcher mockDispatcher =
        new CommandDispatcher(null, null, null) {
          @Override
          public boolean dispatch(String line) {
            return true;
          }
        };

    CommandDispatcher.IO mockIO =
        new CommandDispatcher.IO() {
          @Override
          public void println(String s) {}

          @Override
          public void printf(String fmt, Object... args) {}

          @Override
          public void error(String s) {}
        };

    ScriptRunner runner = new ScriptRunner(mockDispatcher::dispatch, new ArrayList<>());
    ScriptRunner.ExecutionResult result = runner.execute(scriptPath);

    assertEquals(0, result.getSuccessCount());
    assertEquals(1, result.getErrors().size());
    assertTrue(result.getErrors().get(0).getMessage().contains("recording file required"));
  }

  @Test
  void testBackwardCompatibilitySimpleForm(@TempDir Path tempDir) throws IOException {
    // Old syntax $1 should still be required
    Path scriptPath = tempDir.resolve("test.jfrs");
    Files.writeString(scriptPath, "test $1\n");

    CommandDispatcher mockDispatcher =
        new CommandDispatcher(null, null, null) {
          @Override
          public boolean dispatch(String line) {
            return true;
          }
        };

    CommandDispatcher.IO mockIO =
        new CommandDispatcher.IO() {
          @Override
          public void println(String s) {}

          @Override
          public void printf(String fmt, Object... args) {}

          @Override
          public void error(String s) {}
        };

    ScriptRunner runner = new ScriptRunner(mockDispatcher::dispatch, new ArrayList<>());
    ScriptRunner.ExecutionResult result = runner.execute(scriptPath);

    assertEquals(0, result.getSuccessCount());
    assertEquals(1, result.getErrors().size());
    assertTrue(
        result.getErrors().get(0).getMessage().contains("Positional parameter $1 out of bounds"));
  }

  @Test
  void testMultipleOptionalParameters(@TempDir Path tempDir) throws IOException {
    Path scriptPath = tempDir.resolve("test.jfrs");
    Files.writeString(scriptPath, "test $1 ${2:-100} ${3:-table}\n");

    StringBuilder capturedCommands = new StringBuilder();
    CommandDispatcher mockDispatcher =
        new CommandDispatcher(null, null, null) {
          @Override
          public boolean dispatch(String line) {
            capturedCommands.append(line).append("\n");
            return true;
          }
        };

    CommandDispatcher.IO mockIO =
        new CommandDispatcher.IO() {
          @Override
          public void println(String s) {}

          @Override
          public void printf(String fmt, Object... args) {}

          @Override
          public void error(String s) {}
        };

    List<String> arguments = new ArrayList<>();
    arguments.add("file.jfr");

    ScriptRunner runner = new ScriptRunner(mockDispatcher::dispatch, arguments);
    ScriptRunner.ExecutionResult result = runner.execute(scriptPath);

    assertEquals(1, result.getSuccessCount());
    assertEquals(0, result.getErrors().size());
    assertEquals("test file.jfr 100 table\n", capturedCommands.toString());
  }

  @Test
  void testMixedRequiredAndOptional(@TempDir Path tempDir) throws IOException {
    Path scriptPath = tempDir.resolve("test.jfrs");
    Files.writeString(
        scriptPath,
        """
        test $1 ${2:-default2} ${3}
        """);

    StringBuilder capturedCommands = new StringBuilder();
    CommandDispatcher mockDispatcher =
        new CommandDispatcher(null, null, null) {
          @Override
          public boolean dispatch(String line) {
            capturedCommands.append(line).append("\n");
            return true;
          }
        };

    CommandDispatcher.IO mockIO =
        new CommandDispatcher.IO() {
          @Override
          public void println(String s) {}

          @Override
          public void printf(String fmt, Object... args) {}

          @Override
          public void error(String s) {}
        };

    List<String> arguments = new ArrayList<>();
    arguments.add("arg1");
    arguments.add("arg2");

    ScriptRunner runner = new ScriptRunner(mockDispatcher::dispatch, arguments);
    ScriptRunner.ExecutionResult result = runner.execute(scriptPath);

    assertEquals(1, result.getSuccessCount());
    assertEquals(0, result.getErrors().size());
    assertEquals("test arg1 arg2 \n", capturedCommands.toString());
  }

  @Test
  void testDefaultValueWithSpaces(@TempDir Path tempDir) throws IOException {
    Path scriptPath = tempDir.resolve("test.jfrs");
    Files.writeString(scriptPath, "test ${1:-default value with spaces}\n");

    StringBuilder capturedCommands = new StringBuilder();
    CommandDispatcher mockDispatcher =
        new CommandDispatcher(null, null, null) {
          @Override
          public boolean dispatch(String line) {
            capturedCommands.append(line).append("\n");
            return true;
          }
        };

    CommandDispatcher.IO mockIO =
        new CommandDispatcher.IO() {
          @Override
          public void println(String s) {}

          @Override
          public void printf(String fmt, Object... args) {}

          @Override
          public void error(String s) {}
        };

    ScriptRunner runner = new ScriptRunner(mockDispatcher::dispatch, new ArrayList<>());
    ScriptRunner.ExecutionResult result = runner.execute(scriptPath);

    assertEquals(1, result.getSuccessCount());
    assertEquals(0, result.getErrors().size());
    assertEquals("test default value with spaces\n", capturedCommands.toString());
  }

  @Test
  void testAllParametersWithOptionals(@TempDir Path tempDir) throws IOException {
    Path scriptPath = tempDir.resolve("test.jfrs");
    Files.writeString(scriptPath, "test $@ ${1:-default}\n");

    StringBuilder capturedCommands = new StringBuilder();
    CommandDispatcher mockDispatcher =
        new CommandDispatcher(null, null, null) {
          @Override
          public boolean dispatch(String line) {
            capturedCommands.append(line).append("\n");
            return true;
          }
        };

    CommandDispatcher.IO mockIO =
        new CommandDispatcher.IO() {
          @Override
          public void println(String s) {}

          @Override
          public void printf(String fmt, Object... args) {}

          @Override
          public void error(String s) {}
        };

    List<String> arguments = new ArrayList<>();
    arguments.add("arg1");
    arguments.add("arg2");

    ScriptRunner runner = new ScriptRunner(mockDispatcher::dispatch, arguments);
    ScriptRunner.ExecutionResult result = runner.execute(scriptPath);

    assertEquals(1, result.getSuccessCount());
    assertEquals(0, result.getErrors().size());
    assertEquals("test arg1 arg2 arg1\n", capturedCommands.toString());
  }

  @Test
  void testEmptyDefaultValue(@TempDir Path tempDir) throws IOException {
    Path scriptPath = tempDir.resolve("test.jfrs");
    Files.writeString(scriptPath, "test ${1:-}\n");

    StringBuilder capturedCommands = new StringBuilder();
    CommandDispatcher mockDispatcher =
        new CommandDispatcher(null, null, null) {
          @Override
          public boolean dispatch(String line) {
            capturedCommands.append(line).append("\n");
            return true;
          }
        };

    CommandDispatcher.IO mockIO =
        new CommandDispatcher.IO() {
          @Override
          public void println(String s) {}

          @Override
          public void printf(String fmt, Object... args) {}

          @Override
          public void error(String s) {}
        };

    ScriptRunner runner = new ScriptRunner(mockDispatcher::dispatch, new ArrayList<>());
    ScriptRunner.ExecutionResult result = runner.execute(scriptPath);

    assertEquals(1, result.getSuccessCount());
    assertEquals(0, result.getErrors().size());
    assertEquals("test \n", capturedCommands.toString());
  }

  /**
   * A shell reports a failing command by printing it and returning normally; with the line wrapped
   * by {@code failingOnReportedErrors}, such a line fails instead of counting as a success.
   */
  @Test
  void reportedErrorFailsTheLine() {
    ScriptRunner.ErrorTally tally = new ScriptRunner.ErrorTally();
    List<String> ran = new ArrayList<>();
    ScriptRunner.LineDispatcher shell =
        line -> {
          ran.add(line);
          if (line.startsWith("bad")) {
            tally.record("Error: Invalid query: " + line);
          }
          return true;
        };
    List<String> script = List.of("good 1", "bad 2", "good 3");

    ScriptRunner.ExecutionResult failFast =
        new ScriptRunner(ScriptRunner.failingOnReportedErrors(shell, tally), List.of())
            .execute(script);

    assertEquals(List.of("good 1", "bad 2"), ran);
    assertEquals(1, failFast.getSuccessCount());
    assertEquals(1, failFast.getErrors().size());
    assertEquals(2, failFast.getErrors().get(0).getLineNumber());
    assertEquals("Invalid query: bad 2", failFast.getErrors().get(0).getMessage());

    ran.clear();
    ScriptRunner carryOn =
        new ScriptRunner(ScriptRunner.failingOnReportedErrors(shell, tally), List.of());
    carryOn.setContinueOnError(true);
    ScriptRunner.ExecutionResult all = carryOn.execute(script);

    assertEquals(script, ran);
    assertEquals(2, all.getSuccessCount());
    assertEquals(1, all.getErrors().size());

    // Unwrapped, the same shell reports the error and the runner still counts the line a success
    ScriptRunner.ExecutionResult unwrapped = new ScriptRunner(shell, List.of()).execute(script);
    assertEquals(0, unwrapped.getErrors().size());
  }

  // ---- A condition that cannot be evaluated is a command error ----

  /** A real dispatcher (conditionals live there) whose output is captured. */
  private static CommandDispatcher realDispatcher(List<String> out, List<String> err) {
    SessionManager<Session> sm =
        new SessionManager<>((path, c) -> Mockito.mock(JFRSession.class), ParsingContext.create());
    return new CommandDispatcher(
        sm,
        new CommandDispatcher.IO() {
          @Override
          public void println(String s) {
            out.add(s);
          }

          @Override
          public void printf(String fmt, Object... args) {
            out.add(String.format(fmt, args));
          }

          @Override
          public void error(String s) {
            err.add(s);
          }
        },
        ref -> {});
  }

  private static final String FAILING_CONDITIONAL =
      """
      if 1 / 0 > 1
      echo THEN
      else
      echo ELSE
      endif
      echo AFTER
      """;

  @Test
  void failedConditionStopsScriptAndIsReported() {
    List<String> out = new ArrayList<>();
    List<String> err = new ArrayList<>();
    ScriptRunner.ExecutionResult result =
        realDispatcher(out, err).runScript(FAILING_CONDITIONAL.lines().toList(), List.of(), false);

    assertTrue(result.hasErrors(), "a failed condition must make the script fail");
    assertEquals(1, result.getErrors().size());
    ScriptRunner.ScriptError error = result.getErrors().get(0);
    assertEquals(1, error.getLineNumber());
    assertEquals("if 1 / 0 > 1", error.getLine());
    assertTrue(error.getMessage().contains("Division by zero"), error.getMessage());
    assertEquals(0, result.getSuccessCount());
    assertEquals(List.of(), out, "fail-fast: nothing after the failed condition runs");
    assertEquals(List.of(), err, "the error is reported once, through the result");
  }

  @Test
  void failedConditionWithContinueOnErrorRunsNeitherBranch() {
    List<String> out = new ArrayList<>();
    List<String> err = new ArrayList<>();
    ScriptRunner.ExecutionResult result =
        realDispatcher(out, err).runScript(FAILING_CONDITIONAL.lines().toList(), List.of(), true);

    assertEquals(1, result.getErrors().size(), "the failed condition is counted");
    assertEquals(1, result.getErrors().get(0).getLineNumber());
    // else, endif and the echo after it; the skipped 'echo THEN'/'echo ELSE' count as handled
    assertEquals(5, result.getSuccessCount());
    assertEquals(List.of("AFTER"), out, "neither branch runs; the script resumes after endif");
  }

  @Test
  void malformedReferenceInConditionIsAnError() {
    // A malformed reference is an error (#144), and the else branch used to run anyway. (The
    // originally reported exists(rows[1].thread) now parses, so it no longer exercises this.)
    List<String> out = new ArrayList<>();
    List<String> err = new ArrayList<>();
    ScriptRunner.ExecutionResult result =
        realDispatcher(out, err)
            .runScript(
                List.of("if empty(cfg..db)", "echo EMPTY", "else", "echo SET", "endif"),
                List.of(),
                true);

    assertEquals(1, result.getErrors().size());
    assertTrue(
        result
            .getErrors()
            .get(0)
            .getMessage()
            .startsWith("Condition evaluation failed: Invalid variable reference: cfg..db"),
        result.getErrors().get(0).getMessage());
    assertEquals(List.of(), out, "neither EMPTY nor SET may be printed");
  }

  // ---- A script runs with an if-block stack of its own ----

  @Test
  void scriptStoppedInsideBlockDoesNotLeaveItOpen() {
    // The interactive shell runs scripts on its own dispatcher; a script that stopped at a failed
    // condition used to leave the shell inside that block, silently skipping what was typed next.
    List<String> out = new ArrayList<>();
    List<String> err = new ArrayList<>();
    CommandDispatcher dispatcher = realDispatcher(out, err);

    ScriptRunner.ExecutionResult result =
        dispatcher.runScript(FAILING_CONDITIONAL.lines().toList(), List.of(), false);

    assertTrue(result.hasErrors());
    assertFalse(dispatcher.getConditionalState().inConditional());
    dispatcher.dispatch("echo TYPED_AFTERWARDS");
    assertEquals(List.of("TYPED_AFTERWARDS"), out);
  }

  @Test
  void scriptHasItsOwnIfBlockStack() {
    List<String> out = new ArrayList<>();
    List<String> err = new ArrayList<>();
    CommandDispatcher dispatcher = realDispatcher(out, err);
    dispatcher.dispatch("if 1 == 1"); // typed interactively before running the script

    // The script's failed block is closed; the caller's is untouched
    dispatcher.runScript(List.of("if 1 / 0 > 1", "echo X"), List.of(), false);
    assertEquals(1, dispatcher.getConditionalState().depth());
    assertTrue(dispatcher.getConditionalState().isActive());

    // A stray endif is the script's error; it must not close the caller's block
    ScriptRunner.ExecutionResult stray = dispatcher.runScript(List.of("endif"), List.of(), false);
    assertEquals("endif without if", stray.getErrors().get(0).getMessage());
    assertEquals(1, dispatcher.getConditionalState().depth());

    dispatcher.dispatch("echo STILL_IN_CALLERS_BLOCK");
    dispatcher.dispatch("endif");
    assertEquals(List.of("STILL_IN_CALLERS_BLOCK"), out);
    assertFalse(dispatcher.getConditionalState().inConditional());
  }

  @Test
  void missingEndifIsReportedAndClosed() {
    List<String> out = new ArrayList<>();
    CommandDispatcher dispatcher = realDispatcher(out, new ArrayList<>());

    ScriptRunner.ExecutionResult result =
        dispatcher.runScript(
            List.of("if 1 == 1", "echo INSIDE", "if 1 == 2", "echo NESTED"), List.of(), false);

    assertEquals(1, result.getErrors().size());
    ScriptRunner.ScriptError error = result.getErrors().get(0);
    assertEquals(4, error.getLineNumber());
    assertEquals("2 if-block(s) not closed with endif by end of script", error.getMessage());
    assertEquals(List.of("INSIDE"), out);
    assertFalse(dispatcher.getConditionalState().inConditional());
  }

  @Test
  void strayEndifFailsTheScript() {
    List<String> out = new ArrayList<>();
    ScriptRunner.ExecutionResult result =
        realDispatcher(out, new ArrayList<>())
            .runScript(List.of("endif", "echo AFTER"), List.of(), false);

    assertEquals(1, result.getErrors().size());
    assertEquals("endif without if", result.getErrors().get(0).getMessage());
    assertEquals(List.of(), out, "fail-fast");
  }
}

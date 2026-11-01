package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
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

    ScriptRunner runner = new ScriptRunner(mockDispatcher, mockIO, arguments);
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

    ScriptRunner runner = new ScriptRunner(mockDispatcher, mockIO, arguments);
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

    ScriptRunner runner = new ScriptRunner(mockDispatcher, mockIO, new ArrayList<>());
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

    ScriptRunner runner = new ScriptRunner(mockDispatcher, mockIO, new ArrayList<>());
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
    ScriptRunner runner = new ScriptRunner(mockDispatcher, mockIO, new ArrayList<>());
    runner.setContinueOnError(true);
    ScriptRunner.ExecutionResult result = runner.execute(scriptPath);

    assertEquals(2, result.getSuccessCount());
    assertEquals(1, result.getErrors().size());
    assertEquals(3, commandCount[0]); // All commands attempted

    // Test with continueOnError = false (default)
    commandCount[0] = 0;
    ScriptRunner runner2 = new ScriptRunner(mockDispatcher, mockIO, new ArrayList<>());
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

    ScriptRunner runner = new ScriptRunner(mockDispatcher, mockIO, arguments);
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

    ScriptRunner runner = new ScriptRunner(mockDispatcher, mockIO, arguments);
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

    ScriptRunner runner = new ScriptRunner(mockDispatcher, mockIO, arguments);
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

    ScriptRunner runner = new ScriptRunner(mockDispatcher, mockIO, new ArrayList<>());
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

    ScriptRunner runner = new ScriptRunner(mockDispatcher, mockIO, new ArrayList<>());
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

    ScriptRunner runner = new ScriptRunner(mockDispatcher, mockIO, arguments);
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

    ScriptRunner runner = new ScriptRunner(mockDispatcher, mockIO, arguments);
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

    ScriptRunner runner = new ScriptRunner(mockDispatcher, mockIO, new ArrayList<>());
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

    ScriptRunner runner = new ScriptRunner(mockDispatcher, mockIO, arguments);
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

    ScriptRunner runner = new ScriptRunner(mockDispatcher, mockIO, new ArrayList<>());
    ScriptRunner.ExecutionResult result = runner.execute(scriptPath);

    assertEquals(1, result.getSuccessCount());
    assertEquals(0, result.getErrors().size());
    assertEquals("test \n", capturedCommands.toString());
  }

  // ---- A condition that cannot be evaluated is a command error ----

  /** A real dispatcher (conditionals live there) whose output is captured. */
  private static CommandDispatcher realDispatcher(List<String> out, List<String> err) {
    SessionManager<JFRSession> sm =
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
    ScriptRunner runner = new ScriptRunner(realDispatcher(out, err), null, List.of());

    ScriptRunner.ExecutionResult result = runner.execute(FAILING_CONDITIONAL.lines().toList());

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
    ScriptRunner runner = new ScriptRunner(realDispatcher(out, err), null, List.of());
    runner.setContinueOnError(true);

    ScriptRunner.ExecutionResult result = runner.execute(FAILING_CONDITIONAL.lines().toList());

    assertEquals(1, result.getErrors().size(), "the failed condition is counted");
    assertEquals(1, result.getErrors().get(0).getLineNumber());
    // else, endif and the echo after it; the skipped 'echo THEN'/'echo ELSE' count as handled
    assertEquals(5, result.getSuccessCount());
    assertEquals(List.of("AFTER"), out, "neither branch runs; the script resumes after endif");
  }

  @Test
  void unparseableConditionFromReportIsAnError() {
    // The reported case: exists(rows[1].thread) could not be parsed, and the else branch ran.
    List<String> out = new ArrayList<>();
    List<String> err = new ArrayList<>();
    ScriptRunner runner = new ScriptRunner(realDispatcher(out, err), null, List.of());
    runner.setContinueOnError(true);

    ScriptRunner.ExecutionResult result =
        runner.execute(
            List.of("if exists(rows[1].thread)", "echo EXISTS", "else", "echo MISSING", "endif"));

    assertEquals(1, result.getErrors().size());
    assertTrue(
        result
            .getErrors()
            .get(0)
            .getMessage()
            .startsWith("Condition evaluation failed: Expected ')' after function argument"),
        result.getErrors().get(0).getMessage());
    assertEquals(List.of(), out, "MISSING must not be printed");
  }
}

package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScriptRunnerTest {

  @Test
  void testVariableSubstitution(@TempDir Path tempDir) throws IOException {
    // Create a simple script with variable substitution
    Path scriptPath = tempDir.resolve("test.jfrs");
    Files.writeString(
        scriptPath,
        """
        # Comment line

        # Use variable
        test ${var1} ${var2}
        """);

    Map<String, String> variables = new HashMap<>();
    variables.put("var1", "value1");
    variables.put("var2", "value2");

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

    ScriptRunner runner = new ScriptRunner(mockDispatcher, mockIO, variables);
    ScriptRunner.ExecutionResult result = runner.execute(scriptPath);

    assertEquals(1, result.getSuccessCount());
    assertEquals(0, result.getErrors().size());
    assertEquals("test value1 value2\n", capturedCommands.toString());
  }

  @Test
  void testUndefinedVariable(@TempDir Path tempDir) throws IOException {
    Path scriptPath = tempDir.resolve("test.jfrs");
    Files.writeString(scriptPath, "test ${undefined}\n");

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

    ScriptRunner runner = new ScriptRunner(mockDispatcher, mockIO, new HashMap<>());
    ScriptRunner.ExecutionResult result = runner.execute(scriptPath);

    assertEquals(0, result.getSuccessCount());
    assertEquals(1, result.getErrors().size());
    assertTrue(result.getErrors().get(0).getMessage().contains("Undefined variable: undefined"));
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

    ScriptRunner runner = new ScriptRunner(mockDispatcher, mockIO, new HashMap<>());
    ScriptRunner.ExecutionResult result = runner.execute(scriptPath);

    assertEquals(2, result.getSuccessCount());
    assertEquals(2, commandCount[0]);
  }

  @Test
  void testContinueOnError(@TempDir Path tempDir) throws IOException {
    Path scriptPath = tempDir.resolve("test.jfrs");
    Files.writeString(
        scriptPath, """
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
    ScriptRunner runner = new ScriptRunner(mockDispatcher, mockIO, new HashMap<>());
    runner.setContinueOnError(true);
    ScriptRunner.ExecutionResult result = runner.execute(scriptPath);

    assertEquals(2, result.getSuccessCount());
    assertEquals(1, result.getErrors().size());
    assertEquals(3, commandCount[0]); // All commands attempted

    // Test with continueOnError = false (default)
    commandCount[0] = 0;
    ScriptRunner runner2 = new ScriptRunner(mockDispatcher, mockIO, new HashMap<>());
    ScriptRunner.ExecutionResult result2 = runner2.execute(scriptPath);

    assertEquals(1, result2.getSuccessCount());
    assertEquals(1, result2.getErrors().size());
    assertEquals(2, commandCount[0]); // Stopped after error
  }
}

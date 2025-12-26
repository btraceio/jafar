package io.jafar.shell.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes JFR shell script files with variable substitution.
 *
 * <p>Scripts are line-based text files containing shell commands and JfrPath queries. Lines
 * starting with '#' are comments. Variables in the form ${varname} are substituted with values
 * provided via the constructor.
 *
 * <p>Example script:
 *
 * <pre>
 * # Analysis script
 * open ${recording_path}
 * show events/jdk.FileRead[bytes>=${threshold}] --limit 10
 * close
 * </pre>
 */
public class ScriptRunner {
  private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

  private final CommandDispatcher dispatcher;
  private final CommandDispatcher.IO io;
  private final Map<String, String> variables;
  private boolean continueOnError;

  /**
   * Creates a script runner.
   *
   * @param dispatcher command dispatcher for executing commands
   * @param io IO interface for output
   * @param variables variable map for substitution
   */
  public ScriptRunner(
      CommandDispatcher dispatcher, CommandDispatcher.IO io, Map<String, String> variables) {
    this.dispatcher = dispatcher;
    this.io = io;
    this.variables = variables;
    this.continueOnError = false;
  }

  /**
   * Sets whether to continue execution on errors.
   *
   * @param continueOnError if true, continue executing after command failures
   */
  public void setContinueOnError(boolean continueOnError) {
    this.continueOnError = continueOnError;
  }

  /**
   * Executes a script file.
   *
   * @param scriptPath path to script file
   * @return execution result
   * @throws IOException if script file cannot be read
   */
  public ExecutionResult execute(Path scriptPath) throws IOException {
    return execute(Files.readAllLines(scriptPath));
  }

  /**
   * Executes a script from lines.
   *
   * @param lines script lines to execute
   * @return execution result
   */
  public ExecutionResult execute(List<String> lines) {
    int successCount = 0;
    List<ScriptError> errors = new ArrayList<>();

    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i).trim();
      int lineNumber = i + 1;

      // Skip comments and blank lines
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }

      // Substitute variables
      String processed;
      try {
        processed = substituteVariables(line);
      } catch (IllegalArgumentException e) {
        errors.add(new ScriptError(lineNumber, line, e.getMessage()));
        if (!continueOnError) {
          break;
        }
        continue;
      }

      // Dispatch command
      try {
        boolean handled = dispatcher.dispatch(processed);
        if (!handled) {
          errors.add(new ScriptError(lineNumber, line, "Unknown command"));
          if (!continueOnError) {
            break;
          }
        } else {
          successCount++;
        }
      } catch (Exception e) {
        String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        errors.add(new ScriptError(lineNumber, line, errorMsg));
        if (!continueOnError) {
          break;
        }
      }
    }

    return new ExecutionResult(successCount, errors);
  }

  /**
   * Substitutes variables in a line.
   *
   * @param line line with potential variable references
   * @return line with variables substituted
   * @throws IllegalArgumentException if a referenced variable is undefined
   */
  private String substituteVariables(String line) {
    StringBuffer result = new StringBuffer();
    Matcher matcher = VAR_PATTERN.matcher(line);

    while (matcher.find()) {
      String varName = matcher.group(1);
      String value = variables.get(varName);

      if (value == null) {
        throw new IllegalArgumentException(
            "Undefined variable: " + varName + ". Define with --var " + varName + "=value");
      }

      matcher.appendReplacement(result, Matcher.quoteReplacement(value));
    }

    matcher.appendTail(result);
    return result.toString();
  }

  /** Result of script execution. */
  public static class ExecutionResult {
    private final int successCount;
    private final List<ScriptError> errors;

    public ExecutionResult(int successCount, List<ScriptError> errors) {
      this.successCount = successCount;
      this.errors = errors;
    }

    public int getSuccessCount() {
      return successCount;
    }

    public List<ScriptError> getErrors() {
      return errors;
    }

    public boolean hasErrors() {
      return !errors.isEmpty();
    }
  }

  /** Error encountered during script execution. */
  public static class ScriptError {
    private final int lineNumber;
    private final String line;
    private final String message;

    public ScriptError(int lineNumber, String line, String message) {
      this.lineNumber = lineNumber;
      this.line = line;
      this.message = message;
    }

    public int getLineNumber() {
      return lineNumber;
    }

    public String getLine() {
      return line;
    }

    public String getMessage() {
      return message;
    }

    @Override
    public String toString() {
      return String.format("Line %d: %s%n  Command: %s", lineNumber, message, line);
    }
  }
}

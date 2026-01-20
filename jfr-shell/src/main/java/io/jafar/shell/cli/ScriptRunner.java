package io.jafar.shell.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes JFR shell script files with positional parameter substitution.
 *
 * <p>Scripts are line-based text files containing shell commands and JfrPath queries. Lines
 * starting with '#' are comments. Positional parameters are substituted with values provided via
 * the constructor.
 *
 * <p>Supported parameter syntax:
 *
 * <ul>
 *   <li>$1, $2, ... - Required positional parameters (1-indexed)
 *   <li>${1}, ${2}, ... - Optional positional parameters (empty string if missing)
 *   <li>${1:-default} - Parameter with default value
 *   <li>${1:?error message} - Required parameter with custom error message
 *   <li>$@ - All arguments space-separated
 * </ul>
 *
 * <p>Example script:
 *
 * <pre>
 * # Analysis script with optional parameters
 * # Usage: script analysis.jfrs /path/to/recording.jfr [limit] [format]
 * open $1
 * set limit = ${2:-100}
 * set format = ${3:-table}
 * show events/jdk.FileRead[bytes>=1000] --limit ${limit} --format ${format}
 * close
 * </pre>
 */
public class ScriptRunner {
  // Match patterns:
  // Group 1: Simple form $N - captures N
  // Group 2: Bracketed form ${N...} - captures N
  // Group 3: Operator :- or :?
  // Group 4: Default value or error message (can be empty)
  private static final Pattern VAR_PATTERN =
      Pattern.compile("\\$(?:(\\d+|@)|\\{(\\d+|@)(?:(:[-?])(.*?))?\\})");

  private final CommandDispatcher dispatcher;
  private final CommandDispatcher.IO io;
  private final List<String> arguments;
  private boolean continueOnError;

  /**
   * Creates a script runner.
   *
   * @param dispatcher command dispatcher for executing commands
   * @param io IO interface for output
   * @param arguments positional arguments for substitution ($1, $2, etc.)
   */
  public ScriptRunner(
      CommandDispatcher dispatcher, CommandDispatcher.IO io, List<String> arguments) {
    this.dispatcher = dispatcher;
    this.io = io;
    this.arguments = arguments;
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
   * Substitutes positional parameters in a line.
   *
   * @param line line with potential parameter references ($1, $2, $@, ${1:-default}, etc.)
   * @return line with parameters substituted
   * @throws IllegalArgumentException if a required parameter is out of bounds
   */
  private String substituteVariables(String line) {
    StringBuffer result = new StringBuffer();
    Matcher matcher = VAR_PATTERN.matcher(line);

    while (matcher.find()) {
      String simpleRef = matcher.group(1); // $N form
      String bracketedRef = matcher.group(2); // ${N} form
      String operator = matcher.group(3); // :- or :?
      String operand = matcher.group(4); // default value or error message

      String varRef = simpleRef != null ? simpleRef : bracketedRef;
      String value;

      if ("@".equals(varRef)) {
        // $@ expands to all arguments space-separated
        value = String.join(" ", arguments);
      } else {
        // $1, $2, etc. - positional parameter (1-indexed)
        int index = Integer.parseInt(varRef) - 1;

        if (index < 0 || index >= arguments.size()) {
          // Parameter not provided
          if (operator != null) {
            switch (operator) {
              case ":-":
                // Use default value (operand can be empty string)
                value = operand != null ? operand : "";
                break;
              case ":?":
                // Throw custom error
                String errorMsg =
                    operand != null && !operand.isEmpty()
                        ? operand
                        : "required parameter not provided";
                throw new IllegalArgumentException(
                    "Positional parameter $" + varRef + ": " + errorMsg);
              default:
                throw new IllegalArgumentException(
                    "Unknown operator: " + operator + " in positional parameter");
            }
          } else if (bracketedRef != null) {
            // ${N} without operator - use empty string for missing parameters
            value = "";
          } else {
            // $N form without braces - still required (backward compatibility)
            throw new IllegalArgumentException(
                "Positional parameter $"
                    + varRef
                    + " out of bounds. "
                    + "Script has "
                    + arguments.size()
                    + " argument(s).");
          }
        } else {
          // Parameter provided
          value = arguments.get(index);
        }
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

package io.jafar.shell.core.scripting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes shell script files with positional parameter substitution.
 *
 * <p>Scripts are line-based text files containing shell commands and queries in the current
 * session's language (JfrPath, HdumpPath, ...). Lines starting with '#' are comments. Positional
 * parameters are substituted with values provided via the constructor.
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
 * show events/jdk.FileRead[bytesRead>=1000] --limit ${limit} --format ${format}
 * close
 * </pre>
 */
public final class ScriptRunner {
  // Match patterns:
  // Group 1: Simple form $N - captures N
  // Group 2: Bracketed form ${N...} - captures N
  // Group 3: Operator :- or :?
  // Group 4: Default value or error message (can be empty)
  // Note: Using non-greedy .*? to support multiple parameters on same line.
  // This means } cannot appear in default values - use variables for complex defaults.
  private static final Pattern VAR_PATTERN =
      Pattern.compile("\\$(?:(\\d+|@)|\\{(\\d+|@)(?:(:[-?])(.*?))?\\})");

  /**
   * Runs one script line in the shell that owns the script. Both shells provide one: {@code
   * jfr-shell}'s {@code CommandDispatcher::dispatch} and {@code jafar-shell}'s {@code Shell}.
   */
  @FunctionalInterface
  public interface LineDispatcher {
    /**
     * Executes one line.
     *
     * @return {@code false} when the line is not a command the shell knows
     */
    boolean dispatch(String line) throws Exception;
  }

  /**
   * Counts the errors a shell reports. Both shells report a failing command by printing it (a bad
   * query, a missing file, no session open) and returning normally, so the runner cannot see the
   * failure from the dispatch itself; the shell records each report here instead, and {@link
   * #failingOnReportedErrors} turns a report into a failed line.
   */
  public static final class ErrorTally {
    private int count;
    private String last;

    /** Records one reported error, as printed. */
    public void record(String message) {
      count++;
      last = message;
    }

    public int count() {
      return count;
    }

    /** The most recently reported error, as printed, or {@code null}. */
    public String last() {
      return last;
    }
  }

  /** Thrown for a line that reported an error rather than throwing one. */
  static final class ReportedError extends Exception {
    ReportedError(String message) {
      super(message, null, false, false);
    }
  }

  /**
   * Wraps a dispatcher so that a line during which the shell reported an error fails, and so stops
   * the script unless {@link #setContinueOnError} is set, is listed in the error report, and is not
   * counted as successful.
   */
  public static LineDispatcher failingOnReportedErrors(
      LineDispatcher dispatcher, ErrorTally tally) {
    return line -> {
      int before = tally.count();
      boolean handled = dispatcher.dispatch(line);
      if (tally.count() > before) {
        String message = tally.last() == null ? "" : tally.last().strip();
        if (message.startsWith("Error: ")) {
          message = message.substring("Error: ".length());
        }
        throw new ReportedError(message.isEmpty() ? "command reported an error" : message);
      }
      return handled;
    };
  }

  /**
   * The script a shebang line names, for {@code script -}.
   *
   * <p>A script starting {@code #!/usr/bin/env -S jbang jfr-shell@btraceio script -} and run as
   * {@code ./analyze.jfrs app.jfr} is executed by the kernel as {@code ... script - ./analyze.jfrs
   * app.jfr}: the script's own path arrives as the first argument, and stdin is whatever the caller
   * had — usually the terminal. Read literally, {@code -} then waits on stdin and {@code $1} is the
   * script itself. So when the path is {@code -} and the first argument is a file whose first line
   * is a shebang, that file is the script and the arguments after it are {@code $1}, {@code $2}...
   *
   * @return the script file, or empty when this is not such an invocation
   */
  public static java.util.Optional<Path> shebangScript(String scriptPath, List<String> arguments) {
    if (!"-".equals(scriptPath) || arguments.isEmpty()) {
      return java.util.Optional.empty();
    }
    Path candidate = Path.of(arguments.get(0));
    if (!Files.isRegularFile(candidate)) {
      return java.util.Optional.empty();
    }
    try (java.io.BufferedReader reader = Files.newBufferedReader(candidate)) {
      String first = reader.readLine();
      return first != null && first.startsWith("#!")
          ? java.util.Optional.of(candidate)
          : java.util.Optional.empty();
    } catch (IOException | java.io.UncheckedIOException e) {
      return java.util.Optional.empty();
    }
  }

  private final LineDispatcher dispatcher;
  private final List<String> arguments;
  private boolean continueOnError;

  /**
   * Creates a script runner.
   *
   * @param dispatcher executes each line
   * @param arguments positional arguments for substitution ($1, $2, etc.)
   */
  public ScriptRunner(LineDispatcher dispatcher, List<String> arguments) {
    this.dispatcher = dispatcher;
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

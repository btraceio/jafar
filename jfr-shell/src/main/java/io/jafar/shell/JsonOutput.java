package io.jafar.shell;

import io.jafar.shell.cli.CommandDispatcher;
import io.jafar.shell.core.JsonText;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code --json} contract of {@code show} and {@code script} (#234): stdout is one JSON object
 * and nothing else, whatever the exit status; everything else goes to stderr.
 *
 * <ul>
 *   <li>{@code show}, success: {@code {"result": <value>, "truncated": <bool>, "limit": <n|null>}},
 *       where {@code truncated} says {@code --limit} left rows out.
 *   <li>{@code script}, success: {@code {"results": [{"command", "result", "truncated", "limit"},
 *       ...]}}, one entry per {@code show} the script ran.
 *   <li>Failure, exit status not 0: {@code {"error": <message>}}, plus {@code "results"} for a
 *       script, with the results of the lines before the failure.
 * </ul>
 *
 * <p>The message is the failure's own text, without the {@code Error: } prefix the stderr lines
 * have: the dispatcher's error, the script's failed lines, the usage error or the exception. Only
 * when nothing reported one is it what went to stderr while the command ran; the command's other
 * output, which goes to stderr too, is never part of it.
 */
final class JsonOutput {

  private final PrintStream stdout;
  private final PrintStream stderr;
  private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
  private final List<Map<String, Object>> results = new ArrayList<>();
  private String error;

  private JsonOutput(PrintStream stdout, PrintStream stderr) {
    this.stdout = stdout;
    this.stderr = stderr;
  }

  /** Starts capturing stderr; {@code finishShow} or {@code finishScript} restores it. */
  static JsonOutput start() {
    JsonOutput out = new JsonOutput(System.out, System.err);
    OutputStream tee =
        new OutputStream() {
          @Override
          public void write(int b) {
            out.stderr.write(b);
            out.captured.write(b);
          }

          @Override
          public void write(byte[] b, int off, int len) {
            out.stderr.write(b, off, len);
            out.captured.write(b, off, len);
          }

          @Override
          public void flush() {
            out.stderr.flush();
          }
        };
    System.setErr(new PrintStream(tee, true, out.stderr.charset()));
    return out;
  }

  /** Where output that is neither the result nor the error goes: stderr, not captured. */
  PrintStream diagnostics() {
    return stderr;
  }

  /** Records the failure's message; the first one recorded is the one reported. */
  void error(String message) {
    if (error == null && message != null && !message.isBlank()) {
      error = message;
    }
  }

  /** The message for {@code failure}, which escaped the command. */
  void error(Throwable failure) {
    error(failure.getMessage() != null ? failure.getMessage() : failure.toString());
  }

  /** The one object for a usage error picocli reported before the command ran. */
  static String usageError(String command, String message) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("error", normalize(message));
    if ("script".equals(command)) {
      result.put("results", List.of());
    }
    return JsonText.pretty(result);
  }

  /** Where the dispatcher sends {@code show} results. */
  CommandDispatcher.ShowSink sink() {
    return (command, value, truncated, limit) -> {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("command", command.trim());
      entry.put("result", value);
      entry.put("truncated", truncated);
      entry.put("limit", limit);
      results.add(entry);
    };
  }

  /**
   * Writes the one object for a {@code show} that ended with {@code rc}; returns the exit status.
   */
  int finishShow(int rc) {
    restore();
    if (rc == 0 && results.size() == 1) {
      Map<String, Object> result = new LinkedHashMap<>(results.get(0));
      result.remove("command");
      return print(result) ? 0 : 1;
    }
    if (rc == 0) {
      // Nothing failed, yet there is no one result to give: say so rather than print nothing
      rc = 1;
      String message = "show gave " + results.size() + " results, expected 1";
      stderr.println("Error: " + message);
      print(Map.of("error", message));
      return rc;
    }
    print(Map.of("error", message()));
    return rc;
  }

  /**
   * Writes the one object for a {@code script} that ended with {@code rc}; returns the exit status.
   */
  int finishScript(int rc) {
    restore();
    Map<String, Object> result = new LinkedHashMap<>();
    if (rc != 0) {
      result.put("error", message());
    }
    result.put("results", results);
    return print(result) ? rc : 1;
  }

  /** The recorded message, else what went to stderr; a placeholder when there is neither. */
  private String message() {
    String text = error != null ? error : captured.toString(stderr.charset());
    text = normalize(text);
    return text.isEmpty() ? "failed (no message)" : text;
  }

  /** {@code text} trimmed, without the {@code Error: } that starts stderr lines. */
  static String normalize(String text) {
    return text.replaceAll("(?m)^Error: ", "").strip();
  }

  private void restore() {
    System.err.flush();
    System.setErr(stderr);
  }

  /**
   * Prints {@code value} as JSON; when that fails (a result too large to write, say), prints an
   * error object instead and returns false.
   */
  private boolean print(Object value) {
    String text;
    boolean printed = true;
    try {
      text = JsonText.pretty(value);
    } catch (Throwable e) {
      // The text being built is garbage by now, so the small error object fits again
      printed = false;
      String message = "could not write the result as JSON: " + e;
      stderr.println("Error: " + message);
      text = JsonText.pretty(Map.of("error", message));
    }
    stdout.println(text);
    stdout.flush();
    return printed;
  }
}

package io.jafar.shell;

import io.jafar.shell.cli.CommandDispatcher;
import io.jafar.shell.core.JsonText;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
 * <p>The message is what went to stderr while the command ran, which keeps getting it there too.
 */
final class JsonOutput {

  private final PrintStream stdout;
  private final PrintStream stderr;
  private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
  private final List<Map<String, Object>> results = new ArrayList<>();

  private JsonOutput(PrintStream stdout, PrintStream stderr) {
    this.stdout = stdout;
    this.stderr = stderr;
  }

  /** Starts capturing stderr; {@link #finish} restores it. */
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
    System.setErr(new PrintStream(tee, true, StandardCharsets.UTF_8));
    return out;
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
      print(result);
      return 0;
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
    print(result);
    return rc;
  }

  /** What went to stderr, up to a usage text, trimmed; a placeholder when nothing did. */
  private String message() {
    String text = captured.toString(StandardCharsets.UTF_8);
    int usage = text.indexOf("\nUsage:");
    if (usage < 0 && text.startsWith("Usage:")) {
      usage = 0;
    }
    if (usage >= 0) {
      text = text.substring(0, usage);
    }
    text = text.strip();
    return text.isEmpty() ? "failed (no message)" : text;
  }

  private void restore() {
    System.err.flush();
    System.setErr(stderr);
  }

  private void print(Object value) {
    stdout.println(JsonText.pretty(value));
    stdout.flush();
  }
}

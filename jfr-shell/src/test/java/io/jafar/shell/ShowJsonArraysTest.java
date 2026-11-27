package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.stream.JsonReader;
import io.jafar.shell.backend.BackendRegistry;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * #266: {@code show ... --format json} wrote a stack trace's {@code frames}, an {@code ArrayType}
 * in both backends' untyped model, as a JSON string of its {@code toString} text, and printed an
 * "Opened session" line before the JSON. The whole stdout must parse, strictly.
 */
class ShowJsonArraysTest {

  private static final Path RECORDING =
      Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr")
          .toAbsolutePath()
          .normalize();

  @Test
  void stackTraceFramesAreAJsonArrayOnTheJafarBackend() throws Exception {
    assertFramesAreAJsonArray("jafar");
  }

  @Test
  void stackTraceFramesAreAJsonArrayOnTheJdkBackend() throws Exception {
    assertFramesAreAJsonArray("jdk");
  }

  private static void assertFramesAreAJsonArray(String backend) throws Exception {
    String out = show(backend, "events/datadog.ExecutionSample | head(1) | select(stackTrace)");

    JsonElement json;
    try {
      JsonReader reader = new JsonReader(new StringReader(out));
      reader.setLenient(false);
      json = new Gson().getAdapter(JsonElement.class).read(reader);
    } catch (java.io.IOException e) {
      throw new AssertionError(backend + ": not JSON: " + out, e);
    }
    JsonElement frames =
        json.getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("stackTrace").get("frames");
    assertTrue(frames.isJsonArray(), backend + ": " + frames);
    JsonArray array = frames.getAsJsonArray();
    assertTrue(array.size() > 0, backend + ": " + out);
    assertTrue(array.get(0).getAsJsonObject().has("method"), backend + ": " + array.get(0));
  }

  private static String show(String backend, String query) {
    PrintStream originalOut = System.out;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    String previous = System.getProperty("jfr.shell.backend");
    try {
      System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
      // The registry picks its backend once per JVM; start this run from a fresh choice. '-b' goes
      // after 'show': before the subcommand it is ignored (#268).
      System.setProperty("jfr.shell.backend", backend);
      BackendRegistry.getInstance().rediscover();
      int exit =
          new CommandLine(new Main())
              .execute("show", "-b", backend, RECORDING.toString(), query, "--format", "json");
      assertEquals(0, exit, out.toString(StandardCharsets.UTF_8));
      assertEquals(backend, BackendRegistry.getInstance().getCurrent().getId());
    } finally {
      System.setOut(originalOut);
      if (previous == null) {
        System.clearProperty("jfr.shell.backend");
      } else {
        System.setProperty("jfr.shell.backend", previous);
      }
      BackendRegistry.getInstance().rediscover();
    }
    return out.toString(StandardCharsets.UTF_8);
  }
}

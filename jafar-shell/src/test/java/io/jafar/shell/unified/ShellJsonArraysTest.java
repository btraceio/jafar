package io.jafar.shell.unified;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.stream.JsonReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #266 in the unified shell: {@code show ... --format json} wrote a stack trace's {@code frames}, a
 * Java array, as a JSON string of {@code toString} text.
 */
class ShellJsonArraysTest {

  private static final Path RECORDING =
      Path.of("../jfr-shell-tck/src/main/resources/tck-test.jfr").toAbsolutePath().normalize();

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private Terminal terminal;
  private Shell shell;

  @BeforeEach
  void setUp() throws Exception {
    terminal =
        new DumbTerminal(
            "test", "dumb", new ByteArrayInputStream(new byte[0]), out, StandardCharsets.UTF_8);
    shell = new Shell(terminal);
  }

  @AfterEach
  void tearDown() throws Exception {
    shell.close();
  }

  @Test
  void stackTraceFramesAreAJsonArray() throws Exception {
    run("open " + RECORDING);

    String output =
        run("show events/datadog.ExecutionSample | head(1) | select(stackTrace) --format json");

    JsonReader reader = new JsonReader(new StringReader(output));
    reader.setLenient(false);
    JsonElement json = new Gson().getAdapter(JsonElement.class).read(reader);
    JsonElement frames =
        json.getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("stackTrace").get("frames");
    assertTrue(frames.isJsonArray(), output);
    assertTrue(frames.getAsJsonArray().get(0).getAsJsonObject().has("method"), output);
  }

  private String run(String line) {
    out.reset();
    shell.handleLine(line);
    terminal.flush();
    return out.toString(StandardCharsets.UTF_8);
  }
}

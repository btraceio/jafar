package io.jafar.shell.unified;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A bare {@code flamegraph()} query typed at the prompt writes the HTML flamegraph, as jfr-shell
 * and {@code jafar-shell show} do, rather than printing the call tree's {@code toString()} in a
 * table.
 */
class ShellFlamegraphTest {
  // Generated before every test task, with multi-frame jdk.ExecutionSample stacks; the TCK
  // recording's samples carry no frames, so its flamegraph is empty.
  private static final Path RECORDING =
      Path.of("../parser-core/src/test/resources/test-ap.jfr").toAbsolutePath();
  private static final Pattern URL = Pattern.compile("Flamegraph: (file:\\S+\\.html)");

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
  void bareFlamegraphQueryWritesHtml() throws Exception {
    run("open " + RECORDING);

    String output = run("events/jdk.ExecutionSample | flamegraph()");

    assertFalse(output.contains("FlameNode@"), output);
    Matcher m = URL.matcher(output);
    assertTrue(m.find(), output);
    Path html = Path.of(java.net.URI.create(m.group(1)));
    try {
      assertTrue(Files.readString(html).contains("<html"), "not an HTML page: " + html);
    } finally {
      Files.deleteIfExists(html);
    }
  }

  @Test
  void flamegraphWithNoSamplesSaysSo() {
    run("open " + RECORDING);

    String output =
        run("events/jdk.ExecutionSample[sampledThread/javaName=\"no-such\"] | flamegraph()");

    assertFalse(output.contains("FlameNode@"), output);
    assertTrue(output.contains("(no samples)"), output);
  }

  private String run(String line) {
    out.reset();
    shell.handleLine(line);
    terminal.flush();
    return out.toString(StandardCharsets.UTF_8);
  }
}

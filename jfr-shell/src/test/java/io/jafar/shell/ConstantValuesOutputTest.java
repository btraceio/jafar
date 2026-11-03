package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import jdk.jfr.Recording;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * {@code --format csv} and {@code --format json} print constant-pool strings as their value, as the
 * table does: {@code System.gc()}, not {@code {cause=System.gc()}}. {@code metadata/} output keeps
 * its single-entry maps, which are data. See {@code doc/agents/DataShapes.md}.
 *
 * <p>Records its own recording, so it does not depend on the downloaded test recordings.
 */
class ConstantValuesOutputTest {

  @TempDir static Path dir;
  private static Path recording;

  @BeforeAll
  static void record() throws Exception {
    recording = dir.resolve("constants.jfr");
    try (Recording r = new Recording()) {
      r.enable("jdk.GarbageCollection");
      r.enable("jdk.JavaMonitorWait").withThreshold(Duration.ZERO);
      r.start();
      System.gc();
      Object lock = new Object();
      synchronized (lock) {
        lock.wait(20);
      }
      r.stop();
      r.dump(recording);
    }
  }

  private static String show(String query, String format) {
    PrintStream original = System.out;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    try {
      int exit =
          new CommandLine(new Main())
              .execute("show", recording.toString(), query, "--format", format);
      assertEquals(0, exit, out.toString(StandardCharsets.UTF_8));
    } finally {
      System.setOut(original);
    }
    return out.toString(StandardCharsets.UTF_8);
  }

  private static void assertNoWrapper(String output) {
    assertFalse(output.contains("{cause="), output);
    assertFalse(output.contains("{name="), output);
    assertFalse(output.contains("{string="), output);
    assertFalse(output.contains("\"cause\": {"), output);
    assertFalse(output.contains("\"name\": {"), output);
    assertFalse(output.contains("\"key\": {"), output);
  }

  @Test
  void csvRowsPrintConstantsAsValues() {
    String output = show("events/jdk.GarbageCollection", "csv");
    assertNoWrapper(output);
    assertTrue(output.contains(",System.gc(),"), output);
  }

  @Test
  void jsonRowsPrintConstantsAsValues() {
    String output = show("events/jdk.GarbageCollection", "json");
    assertNoWrapper(output);
    assertTrue(output.contains("\"cause\": \"System.gc()\""), output);
  }

  @Test
  void pipelineResultsPrintConstantsAsValues() {
    String json = show("events/jdk.JavaMonitorWait | groupBy(monitorClass/name)", "json");
    assertNoWrapper(json);
    assertTrue(json.contains("\"key\": \"java/lang/Object\""), json);
    String csv = show("events/jdk.GarbageCollection | groupBy(cause)", "csv");
    assertNoWrapper(csv);
    assertTrue(csv.contains("System.gc()"), csv);
  }

  @Test
  void fieldValuesPrintConstantsAsValues() {
    String json = show("events/jdk.GarbageCollection/cause", "json");
    assertNoWrapper(json);
    assertTrue(json.contains("\"System.gc()\""), json);
    String csv = show("events/jdk.GarbageCollection/cause", "csv");
    assertNoWrapper(csv);
    assertTrue(csv.contains("System.gc()"), csv);
  }

  @Test
  void metadataKeepsSingleEntryMaps() {
    // jdk.types.Symbol has one field, so fieldsByName is {string={...}}: data, not a wrapper
    String json = show("metadata/jdk.types.Symbol", "json");
    assertTrue(json.matches("(?s).*\"fieldsByName\": \\{\\s*\"string\": \\{.*"), json);
  }
}

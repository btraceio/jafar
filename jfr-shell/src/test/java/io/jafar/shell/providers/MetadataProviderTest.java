package io.jafar.shell.providers;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.cli.CommandDispatcher;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class MetadataProviderTest {

  private static Path resource(String name) {
    Path p =
        Paths.get("..", "parser-core", "src", "test", "resources", name)
            .normalize()
            .toAbsolutePath();
    assertTrue(Files.exists(p), "Missing test resource: " + p);
    return p;
  }

  private static boolean isJfrRecording(Path path) {
    if (!Files.isRegularFile(path)) {
      return false;
    }
    try (InputStream in = Files.newInputStream(path)) {
      byte[] magic = in.readNBytes(4);
      return magic.length == 4
          && magic[0] == 'F'
          && magic[1] == 'L'
          && magic[2] == 'R'
          && magic[3] == 0;
    } catch (IOException e) {
      return false;
    }
  }

  @Test
  void loadMethodMetadata_from_test_ap() throws Exception {
    Path jfr = resource("test-ap.jfr");
    Map<String, Object> meta = MetadataProvider.loadClass(jfr, "jdk.types.Method");
    assertNotNull(meta, "jdk.types.Method metadata should be present");
    assertEquals("jdk.types.Method", meta.get("name"));
    // minimal presence check; avoid assuming full structure varies by file

    // Verify type ID is present
    assertNotNull(meta.get("id"), "Type ID should be present");
    assertTrue(meta.get("id") instanceof Long, "Type ID should be a Long");

    // Verify well-known field is present
    Map<String, Object> field = MetadataProvider.loadField(jfr, "jdk.types.Method", "name");
    assertNotNull(field, "Field 'name' metadata should be present");
    assertEquals("name", field.get("name"));
    assertNotNull(field.get("type"));
  }

  @Test
  void loadMethodMetadata_from_test_jfr() throws Exception {
    Path jfr = resource("test-jfr.jfr");
    Map<String, Object> meta = MetadataProvider.loadClass(jfr, "jdk.types.Method");
    assertNotNull(meta, "jdk.types.Method metadata should be present");
    assertEquals("jdk.types.Method", meta.get("name"));
    // minimal presence check; avoid assuming full structure varies by file

    // Verify type ID is present
    assertNotNull(meta.get("id"), "Type ID should be present");
    assertTrue(meta.get("id") instanceof Long, "Type ID should be a Long");
  }

  @Test
  void multiChunkRecording_chunkLocalTypesResolveThroughProvider() throws Exception {
    // Multi-chunk fixture with per-chunk type ids (Datadog profiler output). Git-ignored;
    // downloaded by ./get_resources.sh. Both built-in backends resolve chunk-local types
    // (jafar per-chunk; jdk via the recovery path), so the assertion holds regardless of
    // which backend the registry picks.
    Path dd =
        Paths.get("..", "demo", "src", "test", "resources", "test-dd.jfr")
            .normalize()
            .toAbsolutePath();
    Assumptions.assumeTrue(
        isJfrRecording(dd), "multi-chunk fixture not available, run ./get_resources.sh: " + dd);

    List<Map<String, Object>> all = MetadataProvider.loadAllClasses(dd);
    java.util.Set<String> names =
        all.stream().map(m -> String.valueOf(m.get("name"))).collect(Collectors.toSet());
    org.junit.jupiter.api.Assertions.assertTrue(
        names.contains("datadog.ExecutionSample"),
        "chunk-local datadog.ExecutionSample must be visible, got: " + names);

    Map<String, Object> meta = MetadataProvider.loadClass(dd, "datadog.ExecutionSample");
    org.junit.jupiter.api.Assertions.assertNotNull(
        meta, "chunk-local datadog.ExecutionSample must resolve through the provider");
    org.junit.jupiter.api.Assertions.assertEquals("datadog.ExecutionSample", meta.get("name"));
  }

  @Test
  void commandDispatcher_metadata_class_json_outputs() throws Exception {
    Path jfr = resource("test-ap.jfr");
    ParsingContext ctx = ParsingContext.create();
    SessionManager<JFRSession> sessions =
        new SessionManager<>((path, c) -> new JFRSession(path, (ParsingContext) c), ctx);
    SessionManager.SessionRef<JFRSession> ref = sessions.open(jfr, null);

    StringBuilder out = new StringBuilder();
    StringBuilder err = new StringBuilder();
    AtomicReference<SessionManager.SessionRef<? extends Session>> current =
        new AtomicReference<>(ref);
    CommandDispatcher dispatcher =
        new CommandDispatcher(
            sessions,
            new CommandDispatcher.IO() {
              @Override
              public void println(String s) {
                out.append(s).append('\n');
              }

              @Override
              public void printf(String fmt, Object... args) {
                out.append(String.format(fmt, args));
              }

              @Override
              public void error(String s) {
                err.append(s).append('\n');
              }
            },
            current::set);

    boolean handled = dispatcher.dispatch("metadata class jdk.types.Method --json");
    assertTrue(handled, "Command should be handled");
    assertEquals(0, err.length(), "No errors expected: " + err);
    String output = out.toString();
    assertTrue(
        output.contains("\"name\": \"jdk.types.Method\""),
        "JSON should include the class name. Output: " + output);
  }
}

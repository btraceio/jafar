package io.jafar.shell.providers;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.cli.CommandDispatcher;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MetadataProviderTest {

  private static Path resource(String name) {
    Path p =
        Paths.get("..", "parser", "src", "test", "resources", name).normalize().toAbsolutePath();
    assertTrue(Files.exists(p), "Missing test resource: " + p);
    return p;
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
  void commandDispatcher_metadata_class_json_outputs() throws Exception {
    Path jfr = resource("test-ap.jfr");
    ParsingContext ctx = ParsingContext.create();
    SessionManager sessions = new SessionManager(ctx, (path, c) -> new JFRSession(path, c));
    SessionManager.SessionRef ref = sessions.open(jfr, null);

    StringBuilder out = new StringBuilder();
    StringBuilder err = new StringBuilder();
    AtomicReference<SessionManager.SessionRef> current = new AtomicReference<>(ref);
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

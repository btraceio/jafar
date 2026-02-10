package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class ShowMetadataJfrPathTest {
  private static Path resource(String name) {
    return Paths.get("..", "parser", "src", "test", "resources", name).normalize().toAbsolutePath();
  }

  @Test
  void showMetadataFieldsAndFieldLookup() throws Exception {
    Path jfr = resource("test-ap.jfr");
    ParsingContext ctx = ParsingContext.create();
    SessionManager sessions = new SessionManager((path, c) -> new JFRSession(path, (ParsingContext) c), ctx);
    sessions.open(jfr, null);

    StringBuilder out = new StringBuilder();
    StringBuilder err = new StringBuilder();
    CommandDispatcher dispatcher =
        new CommandDispatcher(
            sessions,
            new CommandDispatcher.IO() {
              @Override
              public void println(String s) {
                out.append(s).append("\n");
              }

              @Override
              public void printf(String fmt, Object... args) {
                out.append(String.format(fmt, args));
              }

              @Override
              public void error(String s) {
                err.append(s).append("\n");
              }
            },
            (current) -> {});

    // List fields
    out.setLength(0);
    dispatcher.dispatch("show metadata/jdk.ExecutionSample/fields");
    String output = out.toString();
    assertTrue(
        output.toLowerCase().contains("stacktrace") || output.contains("stackTrace"),
        "Expected 'stackTrace' among fields. Output:\n" + output);

    // Lookup specific field metadata
    out.setLength(0);
    dispatcher.dispatch("show metadata/jdk.ExecutionSample/fields/stackTrace --format json");
    output = out.toString();
    assertTrue(
        output.contains("\"name\": \"stackTrace\""),
        "Expected JSON with field name 'stackTrace'. Output:\n" + output);
  }
}

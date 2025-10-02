package io.jafar.shell.cli;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataClassDepthOptionTest {

    private static Path resource(String name) {
        return Paths.get("..", "parser", "src", "test", "resources", name).normalize().toAbsolutePath();
    }

    @Test
    void depthZeroSuppressesNestedTypes() throws Exception {
        Path jfr = resource("test-ap.jfr");
        ParsingContext ctx = ParsingContext.create();
        SessionManager sessions = new SessionManager(ctx, (path, c) -> new JFRSession(path, c));
        sessions.open(jfr, null);

        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        CommandDispatcher dispatcher = new CommandDispatcher(
                sessions,
                new CommandDispatcher.IO() {
                    @Override public void println(String s) { out.append(s).append("\n"); }
                    @Override public void printf(String fmt, Object... args) { out.append(String.format(fmt, args)); }
                    @Override public void error(String s) { err.append(s).append("\n"); }
                },
                (current) -> {}
        );

        dispatcher.dispatch("metadata class jdk.types.StackTrace --tree --depth 0");
        String output = out.toString();
        assertTrue(output.contains("jdk.types.StackTrace"), "Should include root type");
        assertFalse(output.contains("  jdk.types."), "Should not include nested types when depth=0. Output: \n" + output);
    }


    @Test
    void depthOneShowsNestedTypes() throws Exception {
        Path jfr = resource("test-ap.jfr");
        ParsingContext ctx = ParsingContext.create();
        SessionManager sessions = new SessionManager(ctx, (path, c) -> new JFRSession(path, c));
        sessions.open(jfr, null);

        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        CommandDispatcher dispatcher = new CommandDispatcher(
                sessions,
                new CommandDispatcher.IO() {
                    @Override public void println(String s) { out.append(s).append("\n"); }
                    @Override public void printf(String fmt, Object... args) { out.append(String.format(fmt, args)); }
                    @Override public void error(String s) { err.append(s).append("\n"); }
                },
                (current) -> {}
        );

        dispatcher.dispatch("metadata class jdk.types.StackTrace --tree --depth 1");
        String output = out.toString();
        // With depth 1, we should see at least one nested type header line
        assertTrue(output.contains("  jdk.types."), "Expected nested type header when depth=1. Output:\n" + output);
    }
}

package io.jafar.shell.cli;

import io.jafar.shell.providers.MetadataProvider;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TreeRendererRecursiveTest {

    private static Path resource(String name) {
        Path p = Paths.get("..", "parser", "src", "test", "resources", name).normalize().toAbsolutePath();
        if (!Files.exists(p)) {
            throw new AssertionError("Missing test resource: " + p);
        }
        return p;
    }

    @Test
    void rendersNestedTypesForStackTrace_ap() {
        Path jfr = resource("test-ap.jfr");
        StringBuilder out = new StringBuilder();
        CommandDispatcher.IO io = new CommandDispatcher.IO() {
            @Override public void println(String s) { out.append(s).append('\n'); }
            @Override public void printf(String fmt, Object... args) { out.append(String.format(fmt, args)); }
            @Override public void error(String s) { out.append("ERR: ").append(s).append('\n'); }
        };
        TreeRenderer.renderMetadataRecursive(jfr, "jdk.types.StackTrace", io);
        String text = out.toString();
        assertTrue(text.contains("jdk.types.StackTrace"), "Should include root type");
        assertTrue(text.contains("fields:"), "Should include fields block");
        // Nested class line should appear with indentation
        assertTrue(text.contains("  jdk.types."), "Should include nested metadata types");
    }

    @Test
    void rendersNestedTypesForStackTrace_jfr() {
        Path jfr = resource("test-jfr.jfr");
        StringBuilder out = new StringBuilder();
        CommandDispatcher.IO io = new CommandDispatcher.IO() {
            @Override public void println(String s) { out.append(s).append('\n'); }
            @Override public void printf(String fmt, Object... args) { out.append(String.format(fmt, args)); }
            @Override public void error(String s) { out.append("ERR: ").append(s).append('\n'); }
        };
        TreeRenderer.renderMetadataRecursive(jfr, "jdk.types.StackTrace", io);
        String text = out.toString();
        assertTrue(text.contains("jdk.types.StackTrace"), "Should include root type");
        assertTrue(text.contains("fields:"), "Should include fields block");
        assertTrue(text.contains("  jdk.types."), "Should include nested metadata types");
    }

    @Test
    void repeatsNestedTypeForMultipleFields_ap() {
        Path jfr = resource("test-ap.jfr");
        StringBuilder out = new StringBuilder();
        CommandDispatcher.IO io = new CommandDispatcher.IO() {
            @Override public void println(String s) { out.append(s).append('\n'); }
            @Override public void printf(String fmt, Object... args) { out.append(String.format(fmt, args)); }
            @Override public void error(String s) { out.append("ERR: ").append(s).append('\n'); }
        };
        TreeRenderer.renderMetadataRecursive(jfr, "jdk.types.Method", io);
        String text = out.toString();
        // Expect Symbol subtree to be rendered for at least two fields (e.g., descriptor and name)
        long count = text.lines().filter(l -> l.stripLeading().equals("jdk.types.Symbol")).count();
        assertTrue(count >= 2, "Expected nested jdk.types.Symbol printed at least twice, got " + count + "\n" + text);
    }

    @Test
    void repeatsNestedTypeForMultipleFields_jfr() {
        Path jfr = resource("test-jfr.jfr");
        StringBuilder out = new StringBuilder();
        CommandDispatcher.IO io = new CommandDispatcher.IO() {
            @Override public void println(String s) { out.append(s).append('\n'); }
            @Override public void printf(String fmt, Object... args) { out.append(String.format(fmt, args)); }
            @Override public void error(String s) { out.append("ERR: ").append(s).append('\n'); }
        };
        TreeRenderer.renderMetadataRecursive(jfr, "jdk.types.Method", io);
        String text = out.toString();
        long count = text.lines().filter(l -> l.stripLeading().equals("jdk.types.Symbol")).count();
        assertTrue(count >= 2, "Expected nested jdk.types.Symbol printed at least twice, got " + count);
    }
}

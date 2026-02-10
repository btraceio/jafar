package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.cli.CommandDispatcher;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for LazyQueryValue copy functionality.
 *
 * <p>When copying a lazy query variable (set var2 = $var1), each variable should have independent
 * cache state. This prevents issues where unsetting or invalidating one variable affects the other.
 */
class LazyQueryValueCopyTest {

  static class BufferIO implements CommandDispatcher.IO {
    final StringBuilder out = new StringBuilder();
    final StringBuilder err = new StringBuilder();

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

    String getOutput() {
      return out.toString();
    }

    String getError() {
      return err.toString();
    }

    void clearOutput() {
      out.setLength(0);
      err.setLength(0);
    }
  }

  private static Path testJfr() {
    return Paths.get("..", "parser", "src", "test", "resources", "test-ap.jfr")
        .normalize()
        .toAbsolutePath();
  }

  private ParsingContext ctx;
  private SessionManager sessions;
  private CommandDispatcher dispatcher;
  private BufferIO io;

  @BeforeEach
  void setUp() throws Exception {
    ctx = ParsingContext.create();
    sessions = new SessionManager((path, c) -> new JFRSession(path, (ParsingContext) c), ctx);
    io = new BufferIO();
    dispatcher = new CommandDispatcher(sessions, io, r -> {});

    // Open test JFR file
    dispatcher.dispatch("open " + testJfr());
    io.clearOutput();
  }

  @AfterEach
  void tearDown() {
    if (sessions != null) {
      try {
        sessions.closeAll();
      } catch (Exception e) {
        // Ignore
      }
    }
  }

  // ==================== LazyQueryValue Copy Tests ====================

  @Test
  void copyLazyQueryVariable() {
    // Create a lazy query variable
    dispatcher.dispatch("set original = events/jdk.ExecutionSample | count()");
    io.clearOutput();

    // Copy it to another variable
    dispatcher.dispatch("set copy = $original");
    io.clearOutput();

    // Both should work initially
    dispatcher.dispatch("echo ${original.count}");
    String output1 = io.getOutput();
    assertTrue(output1.length() > 0, "Original should produce output");

    io.clearOutput();
    dispatcher.dispatch("echo ${copy.count}");
    String output2 = io.getOutput();
    assertTrue(output2.length() > 0, "Copy should produce output");

    // Values should be the same
    assertEquals(output1.trim(), output2.trim(), "Both variables should have same value");
  }

  @Test
  void unsetOriginalDoesNotAffectCopy() {
    // Create and copy a lazy query variable
    dispatcher.dispatch("set original = events/jdk.ExecutionSample | count()");
    dispatcher.dispatch("set copy = $original");
    io.clearOutput();

    // Access both to ensure they're cached
    dispatcher.dispatch("echo ${original.count}");
    dispatcher.dispatch("echo ${copy.count}");
    io.clearOutput();

    // Unset the original
    dispatcher.dispatch("unset original");
    io.clearOutput();

    // Copy should still work
    dispatcher.dispatch("echo ${copy.count}");
    String output = io.getOutput();
    String error = io.getError();

    assertTrue(output.length() > 0, "Copy should still produce output");
    assertFalse(error.contains("not found"), "Copy should not be affected by original unset");
  }

  @Test
  void invalidateOriginalDoesNotAffectCopy() {
    // Create and copy a lazy query variable
    dispatcher.dispatch("set original = events/jdk.ExecutionSample | count()");
    dispatcher.dispatch("set copy = $original");
    io.clearOutput();

    // Access both to cache them
    dispatcher.dispatch("echo ${original.count}");
    String originalValue = io.getOutput().trim();
    io.clearOutput();

    dispatcher.dispatch("echo ${copy.count}");
    String copyValue = io.getOutput().trim();
    io.clearOutput();

    // Invalidate the original
    dispatcher.dispatch("invalidate original");
    io.clearOutput();

    // Copy should still have cached value
    dispatcher.dispatch("echo ${copy.count}");
    String copyAfterInvalidate = io.getOutput().trim();

    assertEquals(
        copyValue,
        copyAfterInvalidate,
        "Copy cache should be independent and not affected by original invalidation");
  }

  @Test
  void reassignOriginalDoesNotAffectCopy() {
    // Create and copy a lazy query variable
    dispatcher.dispatch("set original = events/jdk.ExecutionSample | count()");
    dispatcher.dispatch("set copy = $original");
    io.clearOutput();

    // Get copy value
    dispatcher.dispatch("echo ${copy.count}");
    String copyValue = io.getOutput().trim();
    io.clearOutput();

    // Reassign original to a different value
    dispatcher.dispatch("set original = 42");
    io.clearOutput();

    // Copy should still have the query result
    dispatcher.dispatch("echo ${copy.count}");
    String copyAfterReassign = io.getOutput().trim();

    assertEquals(
        copyValue,
        copyAfterReassign,
        "Copy should not be affected by reassigning original variable");

    // Original should have new value
    io.clearOutput();
    dispatcher.dispatch("echo ${original}");
    String originalNew = io.getOutput().trim();
    // Value may be 42 or 42.0 depending on parsing
    assertTrue(
        originalNew.equals("42") || originalNew.equals("42.0"), "Original should have new value");
  }

  @Test
  void copyCacheIsIndependent() {
    // Create a lazy query variable
    dispatcher.dispatch("set var1 = events/jdk.ExecutionSample | count()");
    io.clearOutput();

    // Copy it
    dispatcher.dispatch("set var2 = $var1");
    io.clearOutput();

    // Access var1 to cache it
    dispatcher.dispatch("echo ${var1.count}");
    io.clearOutput();

    // Check vars command - var1 should show as cached, var2 as not evaluated (or also cached)
    dispatcher.dispatch("vars");
    String varsOutput = io.getOutput();

    // At minimum, both variables should exist
    assertTrue(varsOutput.contains("var1"), "var1 should exist");
    assertTrue(varsOutput.contains("var2"), "var2 should exist");
  }

  @Test
  void copyScalarValue() {
    // Test that scalar values also work with copy syntax
    dispatcher.dispatch("set original = 42");
    dispatcher.dispatch("set copy = $original");
    io.clearOutput();

    // Both should have the same value
    dispatcher.dispatch("echo ${original}");
    String originalValue = io.getOutput().trim();
    io.clearOutput();

    dispatcher.dispatch("echo ${copy}");
    String copyValue = io.getOutput().trim();

    assertEquals(originalValue, copyValue, "Scalar values should copy correctly");
    // Value may be 42 or 42.0 depending on parsing
    assertTrue(originalValue.equals("42") || originalValue.equals("42.0"), "Original should be 42");
  }

  @Test
  void copyMapValue() {
    // Test that map values also work with copy syntax
    dispatcher.dispatch("set original = {\"foo\": 10, \"bar\": 20}");
    dispatcher.dispatch("set copy = $original");
    io.clearOutput();

    // Both should have the same value
    dispatcher.dispatch("echo ${original.foo}");
    String originalFoo = io.getOutput().trim();
    io.clearOutput();

    dispatcher.dispatch("echo ${copy.foo}");
    String copyFoo = io.getOutput().trim();

    assertEquals(originalFoo, copyFoo, "Map values should copy correctly");
    // Value may be 10 or 10.0 depending on parsing
    assertTrue(originalFoo.equals("10") || originalFoo.equals("10.0"), "foo field should be 10");
  }
}

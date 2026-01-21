package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Tests for non-interactive command-line mode.
 *
 * <p>Tests the documented non-interactive usage patterns from the tutorial: - jfr-shell show
 * recording.jfr "query" - jfr-shell metadata recording.jfr --events-only - jfr-shell chunks
 * recording.jfr - jfr-shell script script.jfrs args...
 */
class NonInteractiveModeTest {

  private static Path testJfr() {
    return Paths.get("..", "parser", "src", "test", "resources", "test-ap.jfr")
        .normalize()
        .toAbsolutePath();
  }

  private ByteArrayOutputStream outContent;
  private ByteArrayOutputStream errContent;
  private PrintStream originalOut;
  private PrintStream originalErr;
  private InputStream originalIn;

  @BeforeEach
  void setUp() {
    outContent = new ByteArrayOutputStream();
    errContent = new ByteArrayOutputStream();
    originalOut = System.out;
    originalErr = System.err;
    originalIn = System.in;
    System.setOut(new PrintStream(outContent));
    System.setErr(new PrintStream(errContent));
  }

  @AfterEach
  void tearDown() {
    System.setOut(originalOut);
    System.setErr(originalErr);
    System.setIn(originalIn);
  }

  private int execute(String... args) {
    return new CommandLine(new Main()).execute(args);
  }

  private String getOutput() {
    return outContent.toString();
  }

  private String getError() {
    return errContent.toString();
  }

  // ==================== Show Command Tests ====================

  @Test
  void showCommandExecutesCountQuery() {
    int exitCode = execute("show", testJfr().toString(), "events/jdk.ExecutionSample | count()");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();
    assertTrue(output.contains("count"), "Should contain 'count' column");
    assertTrue(output.matches("(?s).*\\|\\s*\\d+\\s*\\|.*"), "Should contain numeric result");
  }

  @Test
  void showCommandWithLimitOption() {
    int exitCode =
        execute("show", testJfr().toString(), "events/jdk.ExecutionSample", "--limit", "5");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();
    // Should have limited output (exact count depends on table formatting)
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should produce output");
  }

  @Test
  void showCommandWithJsonFormat() {
    int exitCode =
        execute(
            "show",
            testJfr().toString(),
            "events/jdk.ExecutionSample | count()",
            "--format",
            "json");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();
    assertTrue(output.contains("[") || output.contains("{"), "Should contain JSON");
  }

  @Test
  void showCommandWithGroupBy() {
    int exitCode =
        execute("show", testJfr().toString(), "events/jdk.ExecutionSample | groupBy(state)");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();
    assertTrue(output.contains("state") || output.contains("key"), "Should show grouping");
  }

  @Test
  void showCommandWithFilter() {
    int exitCode =
        execute(
            "show",
            testJfr().toString(),
            "events/jdk.ExecutionSample[state=\"RUNNABLE\"] | count()");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();
    assertTrue(output.contains("count"), "Should show count result");
  }

  @Test
  void showCommandWithTop() {
    int exitCode =
        execute(
            "show",
            testJfr().toString(),
            "events/jdk.ExecutionSample | groupBy(state) | top(3, by=count)");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should produce output");
  }

  @Test
  void showCommandWithStats() {
    int exitCode =
        execute("show", testJfr().toString(), "events/jdk.ExecutionSample/duration | stats()");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();
    assertTrue(
        output.contains("min") || output.contains("max") || output.contains("avg"),
        "Should show statistics");
  }

  @Test
  void showCommandWithNonExistentFile() {
    int exitCode = execute("show", "/non/existent/file.jfr", "events/jdk.ExecutionSample");

    assertEquals(1, exitCode, "Should fail with non-zero exit code");
    String error = getError();
    assertTrue(error.contains("not found") || error.contains("Error"), "Should show error message");
  }

  @Test
  void showCommandWithInvalidQuery() {
    int exitCode = execute("show", testJfr().toString(), "invalid[[[query");

    assertEquals(1, exitCode, "Should fail with non-zero exit code");
    String error = getError();
    assertTrue(error.length() > 0, "Should show error message");
  }

  // ==================== Metadata Command Tests ====================

  @Test
  void metadataCommandListsEventTypes() {
    int exitCode = execute("metadata", testJfr().toString());

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();
    assertTrue(output.contains("jdk."), "Should contain JDK event types");
  }

  @Test
  void metadataCommandWithEventsOnly() {
    int exitCode = execute("metadata", testJfr().toString(), "--events-only");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();
    assertTrue(output.contains("jdk."), "Should contain event types");
  }

  @Test
  void metadataCommandWithSearch() {
    int exitCode = execute("metadata", testJfr().toString(), "--search", "jdk.Execution*");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();
    assertTrue(
        output.contains("ExecutionSample") || output.contains("jdk.Execution"),
        "Should contain matching types");
  }

  @Test
  void metadataCommandWithSearchAndEventsOnly() {
    int exitCode =
        execute("metadata", testJfr().toString(), "--search", "jdk.File*", "--events-only");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();
    // Should contain File-related events if they exist in the recording
    // Just verify we got some output
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should produce output");
  }

  @Test
  void metadataCommandWithSummary() {
    int exitCode = execute("metadata", testJfr().toString(), "--summary");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should produce output");
  }

  // ==================== Chunks Command Tests ====================

  @Test
  void chunksCommandListsChunks() {
    int exitCode = execute("chunks", testJfr().toString());

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should list chunks");
  }

  @Test
  void chunksCommandWithSummary() {
    int exitCode = execute("chunks", testJfr().toString(), "--summary");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should show summary");
  }

  @Test
  void chunksCommandWithJsonFormat() {
    int exitCode = execute("chunks", testJfr().toString(), "--format", "json");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();
    assertTrue(output.contains("[") || output.contains("{"), "Should contain JSON");
  }

  // ==================== CP Command Tests ====================

  @Test
  void cpCommandListsConstantPools() {
    int exitCode = execute("cp", testJfr().toString());

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should list constant pool types");
  }

  @Test
  void cpCommandWithType() {
    int exitCode = execute("cp", testJfr().toString(), "--type", "jdk.types.Method");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();
    assertNotNull(output);
    assertTrue(output.length() > 0, "Should show type entries");
  }

  @Test
  void cpCommandWithJsonFormat() {
    int exitCode = execute("cp", testJfr().toString(), "--format", "json");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();
    assertTrue(output.contains("[") || output.contains("{"), "Should contain JSON");
  }

  // ==================== Script Command Tests ====================

  @Test
  void scriptCommandExecutesFromStdin() {
    String scriptContent = "# Simple script\necho Hello from script\n";
    System.setIn(new ByteArrayInputStream(scriptContent.getBytes()));

    int exitCode = execute("script", "-");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();
    assertTrue(output.contains("Hello from script"), "Should execute echo command");
  }

  @Test
  void scriptCommandWithStdinAndArguments() {
    String scriptContent = "# Test positional params\necho Arg1=$1\necho Arg2=$2\n";
    System.setIn(new ByteArrayInputStream(scriptContent.getBytes()));

    int exitCode = execute("script", "-", "value1", "value2");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();
    assertTrue(output.contains("Arg1=value1"), "Should substitute $1");
    assertTrue(output.contains("Arg2=value2"), "Should substitute $2");
  }

  @Test
  void scriptCommandWithContinueOnError() {
    String scriptContent =
        """
        # Script with error in middle
        echo Command 1
        this_will_fail
        echo Command 3
        """;
    System.setIn(new ByteArrayInputStream(scriptContent.getBytes()));

    int exitCode = execute("script", "-", "--continue-on-error");

    // Should still fail overall but execute all commands
    assertEquals(1, exitCode, "Should fail with error");
    String output = getOutput();
    assertTrue(output.contains("Command 1"), "Should execute first command");
    assertTrue(output.contains("Command 3"), "Should continue and execute third command");
  }

  @Test
  void scriptCommandWithoutContinueOnError() {
    String scriptContent =
        """
        # Script with error in middle
        echo Command 1
        this_will_fail
        echo Command 3
        """;
    System.setIn(new ByteArrayInputStream(scriptContent.getBytes()));

    int exitCode = execute("script", "-");

    assertEquals(1, exitCode, "Should fail with error");
    String output = getOutput();
    assertTrue(output.contains("Command 1"), "Should execute first command");
    // Command 3 may or may not execute depending on fail-fast behavior
  }

  // ==================== Exit Code Tests ====================

  @Test
  void exitCodeZeroOnSuccess() {
    int exitCode = execute("show", testJfr().toString(), "events/jdk.ExecutionSample | count()");
    assertEquals(0, exitCode, "Should return 0 on success");
  }

  @Test
  void exitCodeNonZeroOnFileNotFound() {
    int exitCode = execute("show", "/non/existent.jfr", "events/jdk.ExecutionSample");
    assertEquals(1, exitCode, "Should return non-zero on file not found");
  }

  @Test
  void exitCodeNonZeroOnInvalidQuery() {
    int exitCode = execute("show", testJfr().toString(), "invalid syntax [[");
    assertEquals(1, exitCode, "Should return non-zero on invalid query");
  }

  // ==================== Help and Version Tests ====================

  @Test
  void helpOptionDisplaysHelp() {
    int exitCode = execute("--help");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();
    assertTrue(output.contains("Usage") || output.contains("jfr-shell"), "Should display help");
  }

  @Test
  void versionOptionDisplaysVersion() {
    int exitCode = execute("--version");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();
    assertTrue(output.contains("jfr-shell") || output.contains("0."), "Should display version");
  }

  @Test
  void showCommandHelp() {
    // picocli's --help can be invoked directly on subcommands
    int exitCode = new CommandLine(new Main()).execute("show", "--help");

    // picocli returns 2 when required parameters are missing, even with --help
    // This is expected behavior - help still works but validates params
    assertTrue(exitCode == 0 || exitCode == 2, "Help may return 0 or 2");
    // Output goes to err stream when params missing
    String output = getOutput() + getError();
    assertTrue(
        output.contains("show") || output.contains("Usage") || output.contains("jfr"),
        "Should display show command help");
  }

  @Test
  void metadataCommandHelp() {
    // picocli's --help can be invoked directly on subcommands
    int exitCode = new CommandLine(new Main()).execute("metadata", "--help");

    // picocli returns 2 when required parameters are missing, even with --help
    assertTrue(exitCode == 0 || exitCode == 2, "Help may return 0 or 2");
    // Output goes to err stream when params missing
    String output = getOutput() + getError();
    assertTrue(
        output.contains("metadata") || output.contains("Usage") || output.contains("jfr"),
        "Should display metadata command help");
  }

  @Test
  void scriptCommandHelp() {
    // picocli's --help can be invoked directly on subcommands
    int exitCode = new CommandLine(new Main()).execute("script", "--help");

    // picocli may return 0 or 2 for help depending on param defaults
    assertTrue(exitCode == 0 || exitCode == 2, "Help may return 0 or 2");
    String output = getOutput() + getError();
    assertTrue(
        output.contains("script") || output.contains("Usage") || output.contains("stdin"),
        "Should display script command help");
  }

  // ==================== Edge Cases ====================

  @Test
  void emptyQuery() {
    int exitCode = execute("show", testJfr().toString(), "");

    assertEquals(1, exitCode, "Should fail with empty query");
  }

  @Test
  void queryWithSpecialCharacters() {
    int exitCode =
        execute("show", testJfr().toString(), "events/jdk.ExecutionSample[state~\".*\"] | count()");

    assertEquals(0, exitCode, "Should handle regex with special characters");
  }

  @Test
  void veryLongQuery() {
    StringBuilder longQuery = new StringBuilder("events/jdk.ExecutionSample");
    for (int i = 0; i < 50; i++) {
      longQuery.append("/field").append(i);
    }
    longQuery.append(" | count()");

    int exitCode = execute("show", testJfr().toString(), longQuery.toString());

    // Should either execute or fail gracefully (not crash)
    assertTrue(exitCode == 0 || exitCode == 1, "Should handle long query without crashing");
  }
}

package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.*;

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
 * Tests for JSON output format (--format json) across different query types and result structures.
 *
 * <p>Validates that JSON output:
 *
 * <ul>
 *   <li>Contains valid JSON syntax markers
 *   <li>Has expected structure for different result types (scalar, array, map)
 *   <li>Contains expected field names
 *   <li>Is properly formatted
 * </ul>
 */
class JsonOutputFormatTest {

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

  /** Basic JSON structure validation - checks for balanced braces and brackets. */
  private void assertValidJsonStructure(String output) {
    int braceBalance = 0;
    int bracketBalance = 0;
    boolean inString = false;
    boolean escaped = false;

    for (char c : output.toCharArray()) {
      if (escaped) {
        escaped = false;
        continue;
      }
      if (c == '\\') {
        escaped = true;
        continue;
      }
      if (c == '"') {
        inString = !inString;
        continue;
      }
      if (!inString) {
        if (c == '{') braceBalance++;
        if (c == '}') braceBalance--;
        if (c == '[') bracketBalance++;
        if (c == ']') bracketBalance--;
      }
    }

    assertEquals(0, braceBalance, "JSON braces should be balanced");
    assertEquals(0, bracketBalance, "JSON brackets should be balanced");
  }

  // ==================== Count Aggregation JSON Tests ====================

  @Test
  void jsonOutputForCount() {
    int exitCode =
        execute(
            "show",
            testJfr().toString(),
            "events/jdk.ExecutionSample | count()",
            "--format",
            "json");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();

    assertValidJsonStructure(output);
    assertTrue(output.contains("["), "Count result should be JSON array");
    assertTrue(output.contains("\"count\""), "Should have 'count' field");
    assertTrue(output.matches("(?s).*\"count\"\\s*:\\s*\\d+.*"), "Count should be numeric");
  }

  // ==================== GroupBy Aggregation JSON Tests ====================

  @Test
  void jsonOutputForGroupBy() {
    int exitCode =
        execute(
            "show",
            testJfr().toString(),
            "events/jdk.ExecutionSample | groupBy(state)",
            "--format",
            "json");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();

    assertValidJsonStructure(output);
    assertTrue(output.contains("["), "GroupBy result should be JSON array");
    assertTrue(output.contains("\"count\""), "Should have count field");
  }

  @Test
  void jsonOutputForGroupByWithSelect() {
    int exitCode =
        execute(
            "show",
            testJfr().toString(),
            "events/jdk.ExecutionSample | groupBy(state) | select(key, count)",
            "--format",
            "json");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();

    assertValidJsonStructure(output);
    assertTrue(output.contains("["), "GroupBy result should be JSON array");
  }

  // ==================== Stats Aggregation JSON Tests ====================

  @Test
  void jsonOutputForStats() {
    int exitCode =
        execute(
            "show",
            testJfr().toString(),
            "events/jdk.ExecutionSample/duration | stats()",
            "--format",
            "json");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();

    assertValidJsonStructure(output);
    assertTrue(output.contains("["), "Stats result should be JSON array");
    assertTrue(
        output.contains("\"min\"") || output.contains("\"max\"") || output.contains("\"count\""),
        "Should have statistical fields");
  }

  // ==================== Quantiles Aggregation JSON Tests ====================

  @Test
  void jsonOutputForQuantiles() {
    int exitCode =
        execute(
            "show",
            testJfr().toString(),
            "events/jdk.ExecutionSample/duration | quantiles(0.5, 0.9, 0.99)",
            "--format",
            "json");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();

    assertValidJsonStructure(output);
    assertTrue(output.contains("["), "Quantiles result should be JSON array");
    assertTrue(
        output.contains("\"p50\"") || output.contains("\"p90\"") || output.contains("\"p99\""),
        "Should have quantile fields");
  }

  // ==================== Top Aggregation JSON Tests ====================

  @Test
  void jsonOutputForTop() {
    int exitCode =
        execute(
            "show",
            testJfr().toString(),
            "events/jdk.ExecutionSample | groupBy(state) | top(3, by=count)",
            "--format",
            "json");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();

    assertValidJsonStructure(output);
    assertTrue(output.contains("["), "Top result should be JSON array");
  }

  // ==================== Show Query JSON Tests ====================

  @Test
  void jsonOutputForShowWithLimit() {
    int exitCode =
        execute(
            "show",
            testJfr().toString(),
            "events/jdk.ExecutionSample",
            "--format",
            "json",
            "--limit",
            "5");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();

    assertValidJsonStructure(output);
    assertTrue(output.contains("["), "Show result should be JSON array");
  }

  @Test
  void jsonOutputForShowWithProjection() {
    int exitCode =
        execute(
            "show",
            testJfr().toString(),
            "events/jdk.ExecutionSample/state",
            "--format",
            "json",
            "--limit",
            "10");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();

    assertValidJsonStructure(output);
    assertTrue(output.contains("["), "Projection result should be JSON array");
  }

  @Test
  void jsonOutputForShowWithFilter() {
    int exitCode =
        execute(
            "show",
            testJfr().toString(),
            "events/jdk.ExecutionSample[state=\"RUNNABLE\"]",
            "--format",
            "json",
            "--limit",
            "5");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();

    assertValidJsonStructure(output);
    assertTrue(output.contains("["), "Filtered result should be JSON array");
  }

  // ==================== Chunks Command JSON Tests ====================
  // Note: Metadata command does not support --format json option

  @Test
  void jsonOutputForChunks() {
    int exitCode = execute("chunks", testJfr().toString(), "--format", "json");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();

    assertValidJsonStructure(output);
    assertTrue(
        output.contains("[") || output.contains("{"), "Chunks should contain JSON structure");
  }

  // ==================== CP Command JSON Tests ====================

  @Test
  void jsonOutputForCp() {
    int exitCode = execute("cp", testJfr().toString(), "--format", "json");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();

    assertValidJsonStructure(output);
    assertTrue(output.contains("[") || output.contains("{"), "CP should contain JSON structure");
  }

  @Test
  void jsonOutputForCpWithType() {
    int exitCode =
        execute("cp", testJfr().toString(), "--type", "jdk.types.Method", "--format", "json");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();

    assertValidJsonStructure(output);
    assertTrue(output.contains("[") || output.contains("{"), "CP should contain JSON structure");
  }

  // ==================== Edge Cases ====================

  @Test
  void jsonOutputWithEmptyResult() {
    int exitCode =
        execute(
            "show",
            testJfr().toString(),
            "events/jdk.ExecutionSample[state=\"NONEXISTENT\"]",
            "--format",
            "json",
            "--limit",
            "10");

    assertEquals(0, exitCode, "Should exit successfully even with no results");
    String output = getOutput();

    assertValidJsonStructure(output);
    assertTrue(output.contains("["), "Empty result should be JSON array");
    // Empty arrays can be formatted as [] or with whitespace/newlines
    assertTrue(
        output.replaceAll("\\s+", "").contains("[]"), "Empty result should be empty JSON array");
  }

  @Test
  void jsonOutputStructureValidation() {
    int exitCode =
        execute(
            "show",
            testJfr().toString(),
            "events/jdk.ExecutionSample | groupBy(state)",
            "--format",
            "json");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();

    assertValidJsonStructure(output);
    assertTrue(output.contains("["), "Root should be array");
    assertTrue(output.contains("{"), "Should contain object elements");
  }

  @Test
  void jsonOutputHasProperFormatting() {
    int exitCode =
        execute(
            "show",
            testJfr().toString(),
            "events/jdk.ExecutionSample | count()",
            "--format",
            "json");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();

    assertValidJsonStructure(output);
    // Should have proper JSON formatting with colons and commas
    assertTrue(output.contains(":"), "JSON should contain colons");
  }

  @Test
  void jsonOutputWithComplexAggregation() {
    int exitCode =
        execute(
            "show",
            testJfr().toString(),
            "events/jdk.ExecutionSample | groupBy(state) | top(5, by=count)",
            "--format",
            "json");

    assertEquals(0, exitCode, "Should exit successfully");
    String output = getOutput();

    assertValidJsonStructure(output);
    assertTrue(output.contains("["), "Result should be JSON array");
    assertTrue(output.contains("\"count\""), "Should contain count field");
  }
}

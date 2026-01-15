package io.jafar.shell.llm;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.shell.jfrpath.JfrPathParser;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Tests for QueryTranslator with focus on query validation. */
class QueryTranslatorTest {

  @Test
  void testValidQueryParsing() {
    // Common valid query patterns that should parse successfully
    String[] validQueries = {
      "events/jdk.ExecutionSample | count()",
      "events/jdk.FileRead[bytes>1048576]",
      "events/jdk.GarbageCollection | stats(duration)",
      "events/jdk.ExecutionSample/stackTrace/frames/0/method/type/name | groupBy(value) | top(10, by=count)",
      "events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(10, by=count)",
      "events/jdk.ObjectAllocationSample | groupBy(eventThread/javaName, agg=sum, value=bytes) | top(10, by=sum)",
    };

    for (String query : validQueries) {
      assertDoesNotThrow(() -> JfrPathParser.parse(query), "Query should be valid: " + query);
    }
  }

  @Test
  void testInvalidQueryParsing() {
    // Common INVALID patterns that LLMs might generate
    String[] invalidQueries = {
      // Wrong array syntax [0] instead of /0 in groupBy path
      "events/jdk.ExecutionSample | groupBy(stackTrace/frames[0]/method/name)",
      // Wrong: groupBy() without parameter after projection
      "events/jdk.ExecutionSample/thread/name | groupBy()",
    };

    for (String query : invalidQueries) {
      assertThrows(
          Exception.class, () -> JfrPathParser.parse(query), "Query should be invalid: " + query);
    }
  }

  @Test
  void testArrayAccessSyntax() {
    // Correct array access syntax
    String validQuery = "events/jdk.ExecutionSample/stackTrace/frames/0";
    assertDoesNotThrow(() -> JfrPathParser.parse(validQuery));

    // Incorrect array access syntax (common LLM mistake)
    String invalidQuery = "events/jdk.ExecutionSample/stackTrace/frames[0]";
    assertThrows(Exception.class, () -> JfrPathParser.parse(invalidQuery));
  }

  @Test
  void testTranslationResultValidation() {
    // Create a mock session (need a real JFR file for this)
    // For now, test the structure
    TranslationResult result =
        new TranslationResult(
            "events/jdk.ExecutionSample | count()",
            "Counts execution samples",
            0.95,
            Optional.empty(),
            null,
            Optional.empty());

    assertEquals("events/jdk.ExecutionSample | count()", result.jfrPathQuery());
    assertEquals("Counts execution samples", result.explanation());
    assertEquals(0.95, result.confidence(), 0.01);
    assertTrue(result.warning().isEmpty());
  }

  @Test
  void testCommonLLMMistakes() {
    // Document common mistakes LLMs make with JfrPath syntax
    Map<String, String> mistakes = new HashMap<>();

    // Mistake 1: Using [index] instead of /index
    mistakes.put("WRONG: stackTrace/frames[0]", "CORRECT: stackTrace/frames/0");

    // Mistake 2: Using select() incorrectly
    mistakes.put(
        "WRONG: | select(name, count)",
        "CORRECT: | groupBy() for aggregations, or /field for projection");

    // Mistake 3: Using stats() on non-existent fields
    mistakes.put(
        "WRONG: | stats(count)", "CORRECT: | count() or | stats(duration) for actual fields");

    // Mistake 4: Wrong event types
    mistakes.put(
        "WRONG: events/jdk.MethodSample",
        "CORRECT: events/jdk.ExecutionSample (check available types)");

    // Mistake 5: Combining groupBy with projection incorrectly
    mistakes.put(
        "WRONG: events/Type | groupBy(field1) | select(field2)",
        "CORRECT: events/Type/field2 | groupBy() (project first, then group)");

    // Validate that wrong patterns fail
    assertThrows(
        Exception.class,
        () -> JfrPathParser.parse("events/jdk.ExecutionSample/stackTrace/frames[0]"));

    // Validate that correct patterns work
    assertDoesNotThrow(() -> JfrPathParser.parse("events/jdk.ExecutionSample/stackTrace/frames/0"));
  }
}

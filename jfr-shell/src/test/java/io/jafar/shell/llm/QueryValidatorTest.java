package io.jafar.shell.llm;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.shell.llm.QueryValidator.IssueType;
import io.jafar.shell.llm.QueryValidator.ValidationResult;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for QueryValidator rule-based validation. */
class QueryValidatorTest {

  private QueryValidator validator;

  @BeforeEach
  void setUp() {
    // Mock available event types for testing
    Set<String> availableEventTypes =
        Set.of(
            "jdk.GarbageCollection",
            "jdk.ExecutionSample",
            "jdk.FileRead",
            "jdk.SocketRead",
            "jdk.ObjectAllocationInNewTLAB",
            "jdk.OldObjectSample");

    validator = new QueryValidator(availableEventTypes);
  }

  @Test
  void testValidate_ValidQuery() {
    // Valid query should pass all rules
    String query = "events/jdk.GarbageCollection[duration>100000000] | count()";

    ValidationResult result = validator.validate(query);

    assertTrue(result.valid(), "Valid query should pass validation");
    assertTrue(result.issues().isEmpty(), "Should have no issues");
    assertFalse(result.needsRepair(), "Should not need repair");
  }

  @Test
  void testValidate_ArraySyntax() {
    // Query with array syntax error
    String query = "events/jdk.ExecutionSample/stackTrace/frames[0]/method";

    ValidationResult result = validator.validate(query);

    assertFalse(result.valid(), "Should fail validation");
    assertTrue(result.needsRepair(), "Should need repair");
    assertEquals(1, result.issues().size(), "Should have one issue");
    assertEquals(IssueType.ARRAY_SYNTAX, result.issues().get(0).type());
  }

  @Test
  void testValidate_InvalidOperator() {
    // Query with filter() operator (not supported)
    String query = "events/jdk.GarbageCollection | filter(duration>1000)";

    ValidationResult result = validator.validate(query);

    assertFalse(result.valid());
    assertTrue(result.needsRepair());
    assertEquals(1, result.issues().size());
    assertEquals(IssueType.INVALID_OPERATOR, result.issues().get(0).type());
  }

  @Test
  void testValidate_DecoratorPrefix() {
    // Query missing $decorator prefix
    String query =
        "events/jdk.ExecutionSample | decorateByTime(jdk.GarbageCollection, fields=name) | groupBy(decorator.name)";

    ValidationResult result = validator.validate(query);

    assertFalse(result.valid());
    assertTrue(result.needsRepair());
    assertEquals(1, result.issues().size());
    assertEquals(IssueType.DECORATOR_PREFIX, result.issues().get(0).type());
  }

  @Test
  void testValidate_EventTypeNamespace() {
    // Query missing jdk. prefix on event type
    String query = "events/ExecutionSample | count()";

    ValidationResult result = validator.validate(query);

    assertFalse(result.valid());
    assertTrue(result.needsRepair());
    // Note: This triggers TWO issues:
    // 1. EVENT_TYPE - missing namespace
    // 2. EVENT_TYPE_NOT_FOUND - "ExecutionSample" doesn't exist (should be "jdk.ExecutionSample")
    assertEquals(2, result.issues().size());
    assertTrue(
        result.issues().stream().anyMatch(i -> i.type() == IssueType.EVENT_TYPE),
        "Should detect missing namespace");
    assertTrue(
        result.issues().stream().anyMatch(i -> i.type() == IssueType.EVENT_TYPE_NOT_FOUND),
        "Should detect event type not found");
  }

  @Test
  void testValidate_Multiline() {
    // Query with newlines
    String query = "events/jdk.GarbageCollection\n| count()";

    ValidationResult result = validator.validate(query);

    assertFalse(result.valid());
    assertTrue(result.needsRepair());
    assertEquals(1, result.issues().size());
    assertEquals(IssueType.MULTILINE, result.issues().get(0).type());
  }

  @Test
  void testValidate_FieldName_BytesRead() {
    // Query using wrong field name for FileRead
    String query = "events/jdk.FileRead[bytes>1048576]";

    ValidationResult result = validator.validate(query);

    assertFalse(result.valid());
    assertTrue(result.needsRepair());
    assertEquals(1, result.issues().size());
    assertEquals(IssueType.FIELD_NAME, result.issues().get(0).type());
    assertTrue(result.issues().get(0).description().contains("bytesRead"));
  }

  @Test
  void testValidate_FieldName_SampledThread() {
    // Query using wrong thread field for ExecutionSample
    String query = "events/jdk.ExecutionSample | groupBy(eventThread/javaName)";

    ValidationResult result = validator.validate(query);

    assertFalse(result.valid());
    assertTrue(result.needsRepair());
    assertEquals(1, result.issues().size());
    assertEquals(IssueType.FIELD_NAME, result.issues().get(0).type());
    assertTrue(result.issues().get(0).description().contains("sampledThread"));
  }

  @Test
  void testValidate_MultipleIssues() {
    // Query with multiple issues
    String query = "events/ExecutionSample/stackTrace[0]\n| filter(duration>100)";

    ValidationResult result = validator.validate(query);

    assertFalse(result.valid());
    assertTrue(result.needsRepair());
    assertTrue(result.issues().size() >= 2, "Should have multiple issues");

    // Should detect array syntax, multiline, invalid operator, and event type
    boolean hasArrayIssue =
        result.issues().stream().anyMatch(i -> i.type() == IssueType.ARRAY_SYNTAX);
    boolean hasMultilineIssue =
        result.issues().stream().anyMatch(i -> i.type() == IssueType.MULTILINE);

    assertTrue(hasArrayIssue || hasMultilineIssue, "Should detect at least one issue type");
  }

  @Test
  void testValidate_CorrectBytesReadUsage() {
    // Query correctly using bytesRead for FileRead
    String query = "events/jdk.FileRead[bytesRead>1048576]";

    ValidationResult result = validator.validate(query);

    assertTrue(result.valid(), "Should pass validation with correct field name");
  }

  @Test
  void testValidate_CorrectSampledThreadUsage() {
    // Query correctly using sampledThread for ExecutionSample
    String query = "events/jdk.ExecutionSample | groupBy(sampledThread/javaName)";

    ValidationResult result = validator.validate(query);

    assertTrue(result.valid(), "Should pass validation with correct field name");
  }

  @Test
  void testValidate_CorrectDecoratorPrefix() {
    // Query correctly using $decorator prefix
    String query =
        "events/jdk.ExecutionSample | decorateByTime(jdk.GarbageCollection, fields=name) | groupBy($decorator.name)";

    ValidationResult result = validator.validate(query);

    assertTrue(result.valid(), "Should pass validation with correct decorator prefix");
  }

  @Test
  void testValidate_EventTypeNotFound() {
    // Query using event type that doesn't exist in recording
    String query = "events/datadog.ExecutionSample | groupBy(sampledThread/javaName)";

    ValidationResult result = validator.validate(query);

    assertFalse(result.valid(), "Should fail validation");
    assertTrue(result.needsRepair(), "Should need repair");
    assertEquals(1, result.issues().size(), "Should have one issue");
    assertEquals(IssueType.EVENT_TYPE_NOT_FOUND, result.issues().get(0).type());
    assertTrue(
        result.issues().get(0).description().contains("datadog.ExecutionSample"),
        "Issue should mention missing event type");
  }

  @Test
  void testValidate_ExistingEventType() {
    // Query using event type that exists in recording
    String query = "events/jdk.ExecutionSample | groupBy(sampledThread/javaName, agg=count)";

    ValidationResult result = validator.validate(query);

    assertTrue(result.valid(), "Should pass validation with existing event type");
  }

  @Test
  void testValidate_ProjectionBeforeGroupBy() {
    // Query with projection before groupBy anti-pattern
    String query =
        "events/jdk.ExecutionSample/stackTrace/frames/0/method/type/name | groupBy(value) | top(10, by=count)";

    ValidationResult result = validator.validate(query);

    assertFalse(result.valid(), "Should fail validation");
    assertTrue(result.needsRepair(), "Should need repair");
    assertTrue(
        result.issues().stream().anyMatch(i -> i.type() == IssueType.PROJECTION_BEFORE_GROUPBY),
        "Should detect projection before groupBy anti-pattern");
  }

  @Test
  void testValidate_CorrectGroupByPattern() {
    // Query with correct groupBy pattern (no projection before)
    String query =
        "events/jdk.ExecutionSample | groupBy(stackTrace/frames/0/method/type/name, agg=count) | top(10, by=count)";

    ValidationResult result = validator.validate(query);

    assertTrue(
        result.valid(),
        "Should pass validation with correct groupBy pattern (assuming event type exists)");
  }
}

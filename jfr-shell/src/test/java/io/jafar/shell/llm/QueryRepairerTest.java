package io.jafar.shell.llm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.jafar.shell.llm.QueryValidator.Issue;
import io.jafar.shell.llm.QueryValidator.IssueType;
import io.jafar.shell.llm.QueryValidator.ValidationResult;
import io.jafar.shell.llm.schemas.ResponseSchemas;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for QueryRepairer with mocked LLM responses. */
class QueryRepairerTest {

  @Mock private LLMProvider mockProvider;

  private QueryRepairer repairer;
  private Set<String> availableEventTypes;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);

    // Mock available event types for testing
    availableEventTypes =
        Set.of(
            "jdk.GarbageCollection",
            "jdk.ExecutionSample",
            "jdk.FileRead",
            "jdk.SocketRead",
            "jdk.ObjectAllocationInNewTLAB",
            "jdk.OldObjectSample",
            "datadog.ExecutionSample",
            "datadog.HeapLiveObject");

    repairer = new QueryRepairer(mockProvider);
  }

  @Test
  void testRepair_ArraySyntax() throws LLMException {
    // Setup
    String originalQuery = "events/jdk.ExecutionSample/stackTrace/frames[0]";
    ValidationResult validation =
        new ValidationResult(
            false,
            List.of(
                new Issue(
                    IssueType.ARRAY_SYNTAX,
                    "Array syntax: use frames/0 not frames[0]",
                    "Replace [index] with /index")));

    // Mock LLM response
    String mockResponse =
        """
            {
              "query": "events/jdk.ExecutionSample/stackTrace/frames/0",
              "changes": ["Changed frames[0] to frames/0"],
              "confidence": 0.95
            }
            """;

    when(mockProvider.completeStructured(
            any(LLMProvider.LLMRequest.class), eq(ResponseSchemas.REPAIR)))
        .thenReturn(new LLMProvider.LLMResponse(mockResponse, "test", 100, 500));

    // Execute
    QueryRepairer.RepairResult result = repairer.repair(originalQuery, validation);

    // Verify
    assertNotNull(result);
    assertEquals("events/jdk.ExecutionSample/stackTrace/frames/0", result.query());
    assertEquals(1, result.changes().size());
    assertEquals("Changed frames[0] to frames/0", result.changes().get(0));
    assertEquals(0.95, result.confidence(), 0.001);
    assertNull(result.warning());

    verify(mockProvider, times(1)).completeStructured(any(), eq(ResponseSchemas.REPAIR));
  }

  @Test
  void testRepair_EventTypeNamespace() throws LLMException {
    // Setup
    String originalQuery = "events/ExecutionSample | count()";
    ValidationResult validation =
        new ValidationResult(
            false,
            List.of(
                new Issue(
                    IssueType.EVENT_TYPE,
                    "Event type missing namespace: ExecutionSample",
                    "Add jdk. prefix (e.g., jdk.ExecutionSample)")));

    // Mock LLM response
    String mockResponse =
        """
            {
              "query": "events/jdk.ExecutionSample | count()",
              "changes": ["Added jdk. prefix to ExecutionSample"],
              "confidence": 0.98
            }
            """;

    when(mockProvider.completeStructured(any(), eq(ResponseSchemas.REPAIR)))
        .thenReturn(new LLMProvider.LLMResponse(mockResponse, "test", 80, 400));

    // Execute
    QueryRepairer.RepairResult result = repairer.repair(originalQuery, validation);

    // Verify
    assertEquals("events/jdk.ExecutionSample | count()", result.query());
    assertEquals(1, result.changes().size());
    assertTrue(result.changes().get(0).contains("jdk."));
    assertEquals(0.98, result.confidence(), 0.001);
  }

  @Test
  void testRepair_MultipleIssues() throws LLMException {
    // Setup
    String originalQuery = "events/ExecutionSample/stackTrace[0]";
    ValidationResult validation =
        new ValidationResult(
            false,
            List.of(
                new Issue(
                    IssueType.ARRAY_SYNTAX,
                    "Array syntax: use frames/0 not frames[0]",
                    "Replace [index] with /index"),
                new Issue(
                    IssueType.EVENT_TYPE,
                    "Event type missing namespace: ExecutionSample",
                    "Add jdk. prefix")));

    // Mock LLM response
    String mockResponse =
        """
            {
              "query": "events/jdk.ExecutionSample/stackTrace/0",
              "changes": [
                "Added jdk. prefix to ExecutionSample",
                "Changed stackTrace[0] to stackTrace/0"
              ],
              "confidence": 0.92
            }
            """;

    when(mockProvider.completeStructured(any(), eq(ResponseSchemas.REPAIR)))
        .thenReturn(new LLMProvider.LLMResponse(mockResponse, "test", 120, 600));

    // Execute
    QueryRepairer.RepairResult result = repairer.repair(originalQuery, validation);

    // Verify
    assertEquals("events/jdk.ExecutionSample/stackTrace/0", result.query());
    assertEquals(2, result.changes().size());
    assertEquals(0.92, result.confidence(), 0.001);
  }

  @Test
  void testRepair_WithWarning() throws LLMException {
    // Setup
    String originalQuery = "events/jdk.FileRead[bytes>1048576]";
    ValidationResult validation =
        new ValidationResult(
            false,
            List.of(
                new Issue(
                    IssueType.FIELD_NAME,
                    "FileRead should use bytesRead not bytes",
                    "Change bytes to bytesRead")));

    // Mock LLM response with warning
    String mockResponse =
        """
            {
              "query": "events/jdk.FileRead[bytesRead>1048576]",
              "changes": ["Changed bytes to bytesRead"],
              "confidence": 0.85,
              "warning": "Field name changed - verify this is the intended field"
            }
            """;

    when(mockProvider.completeStructured(any(), eq(ResponseSchemas.REPAIR)))
        .thenReturn(new LLMProvider.LLMResponse(mockResponse, "test", 110, 550));

    // Execute
    QueryRepairer.RepairResult result = repairer.repair(originalQuery, validation);

    // Verify
    assertEquals("events/jdk.FileRead[bytesRead>1048576]", result.query());
    assertEquals(0.85, result.confidence(), 0.001);
    assertNotNull(result.warning());
    assertTrue(result.warning().contains("Field name changed"));
  }

  @Test
  void testRepair_LLMException() throws LLMException {
    // Setup
    String originalQuery = "events/invalid";
    ValidationResult validation =
        new ValidationResult(
            false,
            List.of(
                new Issue(
                    IssueType.EVENT_TYPE, "Event type missing namespace", "Add jdk. prefix")));

    // Mock LLM exception
    when(mockProvider.completeStructured(any(), eq(ResponseSchemas.REPAIR)))
        .thenThrow(new LLMException(LLMException.ErrorType.NETWORK_ERROR, "Connection timeout"));

    // Execute & Verify
    assertThrows(LLMException.class, () -> repairer.repair(originalQuery, validation));

    verify(mockProvider, times(1)).completeStructured(any(), eq(ResponseSchemas.REPAIR));
  }

  @Test
  void testRepair_AutomaticEventTypeSubstitution_JdkToDatadog() throws LLMException {
    // Setup: Recording only has datadog.ExecutionSample, query uses jdk.ExecutionSample
    Set<String> ddOnlyEvents =
        Set.of("datadog.ExecutionSample", "datadog.HeapLiveObject", "jdk.GarbageCollection");

    repairer = new QueryRepairer(mockProvider);

    String originalQuery =
        "events/jdk.ExecutionSample | groupBy(sampledThread/javaName, agg=count)";
    ValidationResult validation =
        new ValidationResult(
            false,
            List.of(
                new Issue(
                    IssueType.EVENT_TYPE_NOT_FOUND,
                    "Event type not found in recording: jdk.ExecutionSample",
                    "Use alternative event type (will be auto-substituted)")));

    // Execute - should NOT call LLM, should substitute automatically
    QueryRepairer.RepairResult result = repairer.repair(originalQuery, validation);

    // Verify
    assertNotNull(result);
    assertEquals(
        "events/datadog.ExecutionSample | groupBy(sampledThread/javaName, agg=count)",
        result.query(),
        "Should substitute jdk.ExecutionSample with datadog.ExecutionSample");
    assertEquals(1, result.changes().size());
    assertTrue(
        result.changes().get(0).contains("datadog.ExecutionSample"),
        "Change log should mention substitution");
    assertEquals(
        0.95, result.confidence(), 0.001, "Automatic substitution should be high confidence");

    // Verify LLM was NOT called (automatic substitution)
    verify(mockProvider, never()).completeStructured(any(), any());
  }

  @Test
  void testRepair_AutomaticEventTypeSubstitution_DatadogToJdk() throws LLMException {
    // Setup: Recording only has jdk.ExecutionSample, query uses datadog.ExecutionSample
    Set<String> jdkOnlyEvents =
        Set.of("jdk.ExecutionSample", "jdk.OldObjectSample", "jdk.GarbageCollection");

    repairer = new QueryRepairer(mockProvider);

    String originalQuery =
        "events/datadog.ExecutionSample | groupBy(sampledThread/javaName, agg=count)";
    ValidationResult validation =
        new ValidationResult(
            false,
            List.of(
                new Issue(
                    IssueType.EVENT_TYPE_NOT_FOUND,
                    "Event type not found in recording: datadog.ExecutionSample",
                    "Use alternative event type (will be auto-substituted)")));

    // Execute
    QueryRepairer.RepairResult result = repairer.repair(originalQuery, validation);

    // Verify
    assertEquals(
        "events/jdk.ExecutionSample | groupBy(sampledThread/javaName, agg=count)",
        result.query(),
        "Should substitute datadog.ExecutionSample with jdk.ExecutionSample");
    assertTrue(result.changes().get(0).contains("jdk.ExecutionSample"));

    verify(mockProvider, never()).completeStructured(any(), any());
  }

  @Test
  void testRepair_AutomaticEventTypeSubstitution_HeapProfiling() throws LLMException {
    // Setup: Recording has datadog.HeapLiveObject, query uses jdk.OldObjectSample
    Set<String> ddHeapEvents = Set.of("datadog.HeapLiveObject", "jdk.GarbageCollection");

    repairer = new QueryRepairer(mockProvider);

    String originalQuery =
        "events/jdk.OldObjectSample | groupBy(objectClass/name, agg=sum, value=objectSize)";
    ValidationResult validation =
        new ValidationResult(
            false,
            List.of(
                new Issue(
                    IssueType.EVENT_TYPE_NOT_FOUND,
                    "Event type not found in recording: jdk.OldObjectSample",
                    "Use alternative event type")));

    // Execute
    QueryRepairer.RepairResult result = repairer.repair(originalQuery, validation);

    // Verify
    assertEquals(
        "events/datadog.HeapLiveObject | groupBy(objectClass/name, agg=sum, value=objectSize)",
        result.query(),
        "Should substitute jdk.OldObjectSample with datadog.HeapLiveObject");

    verify(mockProvider, never()).completeStructured(any(), any());
  }

  @Test
  void testRepair_NoAlternativeAvailable_FallbackToLLM() throws LLMException {
    // Setup: No alternative available for missing event type
    Set<String> limitedEvents = Set.of("jdk.GarbageCollection");

    repairer = new QueryRepairer(mockProvider);

    String originalQuery = "events/jdk.ExecutionSample | count()";
    ValidationResult validation =
        new ValidationResult(
            false,
            List.of(
                new Issue(
                    IssueType.EVENT_TYPE_NOT_FOUND,
                    "Event type not found in recording: jdk.ExecutionSample",
                    "Use alternative event type")));

    // Mock LLM response (fallback)
    String mockResponse =
        """
            {
              "query": "events/jdk.GarbageCollection | count()",
              "changes": ["Changed to available event type"],
              "confidence": 0.6,
              "warning": "No alternative profiling events available"
            }
            """;

    when(mockProvider.completeStructured(any(), eq(ResponseSchemas.REPAIR)))
        .thenReturn(new LLMProvider.LLMResponse(mockResponse, "test", 100, 500));

    // Execute - should fall back to LLM since no alternative available
    QueryRepairer.RepairResult result = repairer.repair(originalQuery, validation);

    // Verify LLM was called (fallback)
    verify(mockProvider, times(1)).completeStructured(any(), eq(ResponseSchemas.REPAIR));
  }

  @Test
  void testRepair_AutomaticProjectionBeforeGroupByFix() throws LLMException {
    // Setup: Query with projection before groupBy anti-pattern
    String originalQuery =
        "events/jdk.ExecutionSample/stackTrace/frames/0/method/type/name | groupBy(value) | top(10, by=count)";
    ValidationResult validation =
        new ValidationResult(
            false,
            List.of(
                new Issue(
                    IssueType.PROJECTION_BEFORE_GROUPBY,
                    "Invalid pattern: projection before groupBy",
                    "Use: events/<type> | groupBy(field/path) not events/<type>/field | groupBy(value)")));

    // Execute - should fix automatically without calling LLM
    QueryRepairer.RepairResult result = repairer.repair(originalQuery, validation);

    // Verify
    assertNotNull(result);
    assertEquals(
        "events/jdk.ExecutionSample | groupBy(stackTrace/frames/0/method/type/name, agg=count) | top(10, by=count)",
        result.query(),
        "Should fix projection before groupBy pattern");
    assertEquals(1, result.changes().size());
    assertTrue(
        result.changes().get(0).contains("projection before groupBy"),
        "Change log should mention the fix");
    assertEquals(0.95, result.confidence(), 0.001);

    // Verify LLM was NOT called (automatic fix)
    verify(mockProvider, never()).completeStructured(any(), any());
  }

  @Test
  void testRepair_CombinedEventTypeAndProjectionFix() throws LLMException {
    // Setup: Recording has datadog events, query uses jdk events with wrong pattern
    Set<String> ddOnlyEvents = Set.of("datadog.ExecutionSample", "jdk.GarbageCollection");

    repairer = new QueryRepairer(mockProvider);

    String originalQuery =
        "events/jdk.ExecutionSample/stackTrace/frames/0/method/type/name | groupBy(value) | top(10, by=count)";
    ValidationResult validation =
        new ValidationResult(
            false,
            List.of(
                new Issue(
                    IssueType.EVENT_TYPE_NOT_FOUND,
                    "Event type not found in recording: jdk.ExecutionSample",
                    "Use alternative event type"),
                new Issue(
                    IssueType.PROJECTION_BEFORE_GROUPBY,
                    "Invalid pattern: projection before groupBy",
                    "Use correct pattern")));

    // Execute - should fix BOTH issues automatically
    QueryRepairer.RepairResult result = repairer.repair(originalQuery, validation);

    // Verify both fixes applied
    assertNotNull(result);
    String expectedQuery =
        "events/datadog.ExecutionSample | groupBy(stackTrace/frames/0/method/type/name, agg=count) | top(10, by=count)";
    assertEquals(
        expectedQuery, result.query(), "Should fix both event type and projection pattern");
    assertEquals(2, result.changes().size(), "Should have two changes");

    // Verify LLM was NOT called (both fixes are automatic)
    verify(mockProvider, never()).completeStructured(any(), any());
  }
}

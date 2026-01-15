package io.jafar.shell.llm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.jafar.shell.llm.QueryValidator.Issue;
import io.jafar.shell.llm.QueryValidator.IssueType;
import io.jafar.shell.llm.QueryValidator.ValidationResult;
import io.jafar.shell.llm.schemas.ResponseSchemas;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for QueryRepairer with mocked LLM responses. */
class QueryRepairerTest {

  @Mock private LLMProvider mockProvider;

  private QueryRepairer repairer;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
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

    when(mockProvider.completeStructured(any(LLMProvider.LLMRequest.class), eq(ResponseSchemas.REPAIR)))
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
                    IssueType.EVENT_TYPE,
                    "Event type missing namespace",
                    "Add jdk. prefix")));

    // Mock LLM exception
    when(mockProvider.completeStructured(any(), eq(ResponseSchemas.REPAIR)))
        .thenThrow(
            new LLMException(
                LLMException.ErrorType.NETWORK_ERROR, "Connection timeout"));

    // Execute & Verify
    assertThrows(LLMException.class, () -> repairer.repair(originalQuery, validation));

    verify(mockProvider, times(1)).completeStructured(any(), eq(ResponseSchemas.REPAIR));
  }
}

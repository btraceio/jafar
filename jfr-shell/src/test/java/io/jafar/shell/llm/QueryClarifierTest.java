package io.jafar.shell.llm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.jafar.shell.llm.schemas.ResponseSchemas;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for QueryClarifier with mocked LLM responses. */
class QueryClarifierTest {

  @Mock private LLMProvider mockProvider;

  private QueryClarifier clarifier;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    clarifier = new QueryClarifier(mockProvider);
  }

  @Test
  void testClarify_AmbiguousThreadQuery() throws LLMException {
    // Setup
    String userQuery = "show threads";
    QueryCategory category = QueryCategory.SIMPLE_FILTER;

    // Mock LLM response
    String mockResponse =
        """
            {
              "clarificationQuestion": "What do you want to see about threads?",
              "suggestedChoices": [
                "List of thread names with sample counts",
                "Thread states over time",
                "CPU usage by thread",
                "Thread creation and termination events"
              ],
              "ambiguityScore": 0.8
            }
            """;

    when(mockProvider.completeStructured(
            any(LLMProvider.LLMRequest.class), eq(ResponseSchemas.CLARIFICATION)))
        .thenReturn(new LLMProvider.LLMResponse(mockResponse, "test", 150, 700));

    // Execute
    QueryClarifier.ClarificationResult result = clarifier.clarify(userQuery, category);

    // Verify
    assertNotNull(result);
    assertEquals("What do you want to see about threads?", result.question());
    assertEquals(4, result.choices().size());
    assertTrue(result.choices().contains("List of thread names with sample counts"));
    assertTrue(result.choices().contains("Thread states over time"));
    assertEquals(0.8, result.ambiguityScore(), 0.001);

    verify(mockProvider, times(1)).completeStructured(any(), eq(ResponseSchemas.CLARIFICATION));
  }

  @Test
  void testClarify_AmbiguousGCQuery() throws LLMException {
    // Setup
    String userQuery = "GC events";
    QueryCategory category = QueryCategory.SIMPLE_COUNT;

    // Mock LLM response
    String mockResponse =
        """
            {
              "clarificationQuestion": "How do you want to analyze GC events?",
              "suggestedChoices": [
                "Count total GC events",
                "Show GC events with longest pause times",
                "Calculate average GC pause duration"
              ],
              "ambiguityScore": 0.9
            }
            """;

    when(mockProvider.completeStructured(any(), eq(ResponseSchemas.CLARIFICATION)))
        .thenReturn(new LLMProvider.LLMResponse(mockResponse, "test", 140, 650));

    // Execute
    QueryClarifier.ClarificationResult result = clarifier.clarify(userQuery, category);

    // Verify
    assertEquals("How do you want to analyze GC events?", result.question());
    assertEquals(3, result.choices().size());
    assertEquals(0.9, result.ambiguityScore(), 0.001);
  }

  @Test
  void testClarify_AmbiguousAllocationQuery() throws LLMException {
    // Setup
    String userQuery = "allocations";
    QueryCategory category = QueryCategory.GROUPBY_AGGREGATED;

    // Mock LLM response
    String mockResponse =
        """
            {
              "clarificationQuestion": "What aspect of allocations do you want to see?",
              "suggestedChoices": [
                "Total allocation size",
                "Allocations grouped by class"
              ],
              "ambiguityScore": 0.85
            }
            """;

    when(mockProvider.completeStructured(any(), eq(ResponseSchemas.CLARIFICATION)))
        .thenReturn(new LLMProvider.LLMResponse(mockResponse, "test", 130, 600));

    // Execute
    QueryClarifier.ClarificationResult result = clarifier.clarify(userQuery, category);

    // Verify
    assertEquals("What aspect of allocations do you want to see?", result.question());
    assertEquals(2, result.choices().size());
    assertEquals(0.85, result.ambiguityScore(), 0.001);
  }

  @Test
  void testClarify_LowAmbiguity() throws LLMException {
    // Setup
    String userQuery = "show me something";
    QueryCategory category = QueryCategory.SIMPLE_FILTER;

    // Mock LLM response with low ambiguity score
    String mockResponse =
        """
            {
              "clarificationQuestion": "What would you like to see?",
              "suggestedChoices": [
                "GC events",
                "Thread samples",
                "File I/O operations",
                "Network events"
              ],
              "ambiguityScore": 0.3
            }
            """;

    when(mockProvider.completeStructured(any(), eq(ResponseSchemas.CLARIFICATION)))
        .thenReturn(new LLMProvider.LLMResponse(mockResponse, "test", 140, 650));

    // Execute
    QueryClarifier.ClarificationResult result = clarifier.clarify(userQuery, category);

    // Verify
    assertEquals(0.3, result.ambiguityScore(), 0.001);
    assertEquals(4, result.choices().size());
  }

  @Test
  void testClarify_LLMException() throws LLMException {
    // Setup
    String userQuery = "show threads";
    QueryCategory category = QueryCategory.SIMPLE_FILTER;

    // Mock LLM exception
    when(mockProvider.completeStructured(any(), eq(ResponseSchemas.CLARIFICATION)))
        .thenThrow(
            new LLMException(LLMException.ErrorType.INVALID_RESPONSE, "Malformed JSON"));

    // Execute & Verify
    assertThrows(
        LLMException.class,
        () -> clarifier.clarify(userQuery, category));

    verify(mockProvider, times(1)).completeStructured(any(), eq(ResponseSchemas.CLARIFICATION));
  }

  @Test
  void testClarify_VerifyRequestContent() throws LLMException {
    // Setup
    String userQuery = "show data";
    QueryCategory category = QueryCategory.SIMPLE_FILTER;

    String mockResponse =
        """
            {
              "clarificationQuestion": "What data?",
              "suggestedChoices": ["Option 1", "Option 2"],
              "ambiguityScore": 0.7
            }
            """;

    when(mockProvider.completeStructured(any(), eq(ResponseSchemas.CLARIFICATION)))
        .thenReturn(new LLMProvider.LLMResponse(mockResponse, "test", 100, 500));

    // Execute
    clarifier.clarify(userQuery, category);

    // Verify request structure
    verify(mockProvider)
        .completeStructured(
            argThat(
                request -> {
                  // Check that user message contains the query and category
                  String userMessage = request.messages().get(0).content();
                  return userMessage.contains(userQuery)
                      && userMessage.contains(category.name());
                }),
            eq(ResponseSchemas.CLARIFICATION));
  }
}

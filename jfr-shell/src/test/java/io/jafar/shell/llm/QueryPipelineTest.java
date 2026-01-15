package io.jafar.shell.llm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager.SessionRef;
import io.jafar.shell.llm.schemas.ResponseSchemas;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for QueryPipeline orchestration. */
class QueryPipelineTest {

  @Mock private LLMProvider mockProvider;
  @Mock private JFRSession mockSession;

  private SessionRef sessionRef;
  private QueryPipeline pipeline;
  private LLMConfig config;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);

    // Setup mock session
    when(mockSession.getAvailableEventTypes())
        .thenReturn(
            Set.of(
                "jdk.ExecutionSample",
                "jdk.GarbageCollection",
                "jdk.FileRead",
                "jdk.SocketRead"));
    when(mockSession.getRecordingPath()).thenReturn(Path.of("test.jfr"));
    when(mockSession.getEventTypeCounts()).thenReturn(java.util.Collections.emptyMap());

    sessionRef = new SessionRef(1, "test", mockSession);

    // Setup config
    config =
        new LLMConfig(
            LLMConfig.ProviderType.MOCK,
            "http://localhost",
            "test-model",
            null,
            LLMConfig.PrivacySettings.defaults(),
            30,
            2048,
            0.1);

    when(mockProvider.getConfig()).thenReturn(config);

    pipeline = new QueryPipeline(mockProvider, sessionRef, config);
  }

  @Test
  void testPipeline_SimpleCount_Success() throws LLMException {
    // Mock generation response (classification is rule-based for "count GC events")
    String generationResponse =
        """
            {
              "query": "events/jdk.GarbageCollection | count()",
              "explanation": "Counts all GC events",
              "confidence": 0.95
            }
            """;

    when(mockProvider.completeStructured(any(), eq(ResponseSchemas.GENERATION)))
        .thenReturn(new LLMProvider.LLMResponse(generationResponse, "test", 80, 200));

    // Execute
    PipelineResult result =
        pipeline.generateJfrPath("count GC events", new QueryPipeline.PipelineContext());

    // Verify
    assertTrue(result.hasQuery());
    assertEquals("events/jdk.GarbageCollection | count()", result.query());
    assertEquals("Counts all GC events", result.explanation());
    assertEquals(0.95, result.confidence(), 0.001);
    assertFalse(result.needsClarification());

    // Should only call generate (classification is rule-based, no clarify or repair needed)
    verify(mockProvider, times(1)).completeStructured(any(), eq(ResponseSchemas.GENERATION));
    verify(mockProvider, never()).completeStructured(any(), eq(ResponseSchemas.CLASSIFICATION));
    verify(mockProvider, never()).completeStructured(any(), eq(ResponseSchemas.CLARIFICATION));
    verify(mockProvider, never()).completeStructured(any(), eq(ResponseSchemas.REPAIR));
  }

  @Test
  void testPipeline_LowConfidence_Clarifies() throws LLMException {
    // Mock low confidence classification
    String classificationResponse =
        """
            {
              "category": "SIMPLE_FILTER",
              "confidence": 0.4,
              "reasoning": "Query is ambiguous"
            }
            """;

    // Mock clarification response
    String clarificationResponse =
        """
            {
              "clarificationQuestion": "What do you want to filter?",
              "suggestedChoices": ["File reads", "Network events", "GC events"],
              "ambiguityScore": 0.8
            }
            """;

    when(mockProvider.completeStructured(any(), eq(ResponseSchemas.CLASSIFICATION)))
        .thenReturn(new LLMProvider.LLMResponse(classificationResponse, "test", 50, 100));

    when(mockProvider.completeStructured(any(), eq(ResponseSchemas.CLARIFICATION)))
        .thenReturn(new LLMProvider.LLMResponse(clarificationResponse, "test", 60, 150));

    // Execute
    PipelineResult result =
        pipeline.generateJfrPath("show events", new QueryPipeline.PipelineContext());

    // Verify
    assertTrue(result.needsClarification());
    assertFalse(result.hasQuery());
    assertTrue(result.clarification().isPresent());
    assertEquals("What do you want to filter?", result.clarification().get().clarificationQuestion());
    assertEquals(3, result.clarification().get().suggestedChoices().size());

    // Should call classify and clarify (not generate or repair)
    verify(mockProvider, times(1)).completeStructured(any(), eq(ResponseSchemas.CLASSIFICATION));
    verify(mockProvider, times(1)).completeStructured(any(), eq(ResponseSchemas.CLARIFICATION));
    verify(mockProvider, never()).completeStructured(any(), eq(ResponseSchemas.GENERATION));
    verify(mockProvider, never()).completeStructured(any(), eq(ResponseSchemas.REPAIR));
  }

  @Test
  void testPipeline_SyntaxError_Repairs() throws LLMException {
    // Mock generation with syntax error (classification is rule-based)
    String generationResponse =
        """
            {
              "query": "events/ExecutionSample | count()",
              "explanation": "Counts execution samples",
              "confidence": 0.85
            }
            """;

    // Mock repair response
    String repairResponse =
        """
            {
              "query": "events/jdk.ExecutionSample | count()",
              "changes": ["Added jdk. prefix to ExecutionSample"],
              "confidence": 0.9
            }
            """;

    when(mockProvider.completeStructured(any(), eq(ResponseSchemas.GENERATION)))
        .thenReturn(new LLMProvider.LLMResponse(generationResponse, "test", 80, 200));

    when(mockProvider.completeStructured(any(), eq(ResponseSchemas.REPAIR)))
        .thenReturn(new LLMProvider.LLMResponse(repairResponse, "test", 70, 150));

    // Execute
    PipelineResult result =
        pipeline.generateJfrPath("count execution samples", new QueryPipeline.PipelineContext());

    // Verify
    assertTrue(result.hasQuery());
    assertEquals("events/jdk.ExecutionSample | count()", result.query());
    assertTrue(result.explanation().contains("repaired"));

    // Should call generate and repair (classification is rule-based)
    verify(mockProvider, times(1)).completeStructured(any(), eq(ResponseSchemas.GENERATION));
    verify(mockProvider, times(1)).completeStructured(any(), eq(ResponseSchemas.REPAIR));
    verify(mockProvider, never()).completeStructured(any(), eq(ResponseSchemas.CLASSIFICATION));
    verify(mockProvider, never()).completeStructured(any(), eq(ResponseSchemas.CLARIFICATION));
  }

  @Test
  void testPipeline_LowGenerationConfidence_Repairs() throws LLMException {
    // Mock classification
    String classificationResponse =
        """
            {
              "category": "SIMPLE_FILTER",
              "confidence": 0.8,
              "reasoning": "Filter query"
            }
            """;

    // Mock generation with low confidence
    String generationResponse =
        """
            {
              "query": "events/jdk.FileRead[bytes>1048576]",
              "explanation": "File reads over 1MB",
              "confidence": 0.6
            }
            """;

    // Mock repair response
    String repairResponse =
        """
            {
              "query": "events/jdk.FileRead[bytesRead>1048576]",
              "changes": ["Changed bytes to bytesRead"],
              "confidence": 0.85
            }
            """;

    when(mockProvider.completeStructured(any(), eq(ResponseSchemas.CLASSIFICATION)))
        .thenReturn(new LLMProvider.LLMResponse(classificationResponse, "test", 50, 100));

    when(mockProvider.completeStructured(any(), eq(ResponseSchemas.GENERATION)))
        .thenReturn(new LLMProvider.LLMResponse(generationResponse, "test", 80, 200));

    when(mockProvider.completeStructured(any(), eq(ResponseSchemas.REPAIR)))
        .thenReturn(new LLMProvider.LLMResponse(repairResponse, "test", 70, 150));

    // Execute
    PipelineResult result =
        pipeline.generateJfrPath("file reads over 1MB", new QueryPipeline.PipelineContext());

    // Verify
    assertTrue(result.hasQuery());
    assertEquals("events/jdk.FileRead[bytesRead>1048576]", result.query());

    // Should trigger repair due to low confidence (< 0.7)
    verify(mockProvider, times(1)).completeStructured(any(), eq(ResponseSchemas.REPAIR));
  }

  @Test
  void testPipeline_WithWarning() throws LLMException {
    // Mock generation with warning (classification is rule-based for "top 10 hottest methods")
    String generationResponse =
        """
            {
              "query": "events/jdk.ExecutionSample | groupBy(stackTrace/frames/0/method/type/name, agg=count) | top(10, by=count)",
              "explanation": "Top 10 hottest methods",
              "confidence": 0.9,
              "warning": "Stack trace navigation may be complex"
            }
            """;

    when(mockProvider.completeStructured(any(), eq(ResponseSchemas.GENERATION)))
        .thenReturn(new LLMProvider.LLMResponse(generationResponse, "test", 120, 300));

    // Execute
    PipelineResult result =
        pipeline.generateJfrPath("top 10 hottest methods", new QueryPipeline.PipelineContext());

    // Verify
    assertTrue(result.hasQuery());
    assertTrue(result.warning().isPresent());
    assertEquals("Stack trace navigation may be complex", result.warning().get());
  }

  @Test
  void testPipeline_ClassificationFailure() throws LLMException {
    // Mock classification failure
    when(mockProvider.completeStructured(any(), eq(ResponseSchemas.CLASSIFICATION)))
        .thenThrow(
            new LLMException(LLMException.ErrorType.NETWORK_ERROR, "Connection failed"));

    // Execute & Verify
    assertThrows(
        LLMException.class,
        () -> pipeline.generateJfrPath("count GC events", new QueryPipeline.PipelineContext()));
  }
}

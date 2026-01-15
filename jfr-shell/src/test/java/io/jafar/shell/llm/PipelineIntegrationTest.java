package io.jafar.shell.llm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager.SessionRef;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the query pipeline.
 *
 * <p>Note: These tests are disabled by default as they require a real LLM provider (Ollama). To run
 * them:
 *
 * <ol>
 *   <li>Start Ollama: `ollama serve`
 *   <li>Pull a model: `ollama pull qwen2.5:7b-instruct`
 *   <li>Enable tests by removing @Disabled annotation
 *   <li>Run: `./gradlew :jfr-shell:test --tests PipelineIntegrationTest`
 * </ol>
 */
class PipelineIntegrationTest {

  private QueryPipeline pipeline;
  private SessionRef sessionRef;

  @BeforeEach
  void setUp() {
    // Setup mock session
    JFRSession mockSession = mock(JFRSession.class);
    when(mockSession.getAvailableEventTypes())
        .thenReturn(
            Set.of(
                "jdk.ExecutionSample",
                "jdk.GarbageCollection",
                "jdk.ObjectAllocationInNewTLAB",
                "jdk.FileRead",
                "jdk.FileWrite",
                "jdk.SocketRead",
                "jdk.SocketWrite",
                "jdk.JavaExceptionThrow",
                "jdk.ThreadPark"));
    when(mockSession.getRecordingPath()).thenReturn(Path.of("test-recording.jfr"));
    when(mockSession.getEventTypeCounts()).thenReturn(java.util.Collections.emptyMap());

    sessionRef = new SessionRef(1, "test", mockSession);

    // Create real config for Ollama
    LLMConfig config =
        new LLMConfig(
            LLMConfig.ProviderType.LOCAL,
            "http://localhost:11434",
            "llama3.1:8b",
            null,
            LLMConfig.PrivacySettings.defaults(),
            60,
            2000,
            0.1);

    // Create real provider
    LLMProvider provider = LLMProvider.create(config);

    pipeline = new QueryPipeline(provider, sessionRef, config);
  }

  @Test
  void testPipeline_SimpleCountQuery() throws LLMException {
    PipelineResult result =
        pipeline.generateJfrPath("count GC events", new QueryPipeline.PipelineContext());

    System.out.println("=== SIMPLE COUNT QUERY TEST ===");
    System.out.println("Has query: " + result.hasQuery());
    System.out.println("Needs clarification: " + result.needsClarification());
    if (result.hasQuery()) {
      System.out.println("Generated query: " + result.query());
      System.out.println("Explanation: " + result.explanation());
      System.out.println("Confidence: " + result.confidence());
    }
    System.out.println("===============================");

    assertTrue(result.hasQuery(), "Should generate a query");
    assertFalse(result.needsClarification(), "Should not need clarification");

    String query = result.query();
    assertNotNull(query);
    assertTrue(
        query.contains("jdk.GarbageCollection") || query.contains("GarbageCollection"),
        "Query should reference GarbageCollection, but got: " + query);
    assertTrue(query.contains("count"), "Query should have count operation");
  }

  @Test
  void testPipeline_FilterQuery() throws LLMException {
    PipelineResult result =
        pipeline.generateJfrPath(
            "GC events longer than 100ms", new QueryPipeline.PipelineContext());

    assertTrue(result.hasQuery());
    String query = result.query();
    assertNotNull(query);

    assertTrue(query.contains("GarbageCollection"));
    assertTrue(query.contains("duration"));
    // Should have converted 100ms to nanoseconds (100000000)
    assertTrue(query.contains("100000000") || query.contains("1e8"));

    System.out.println("Generated query: " + query);
    System.out.println("Explanation: " + result.explanation());
  }

  @Test
  void testPipeline_TopNQuery() throws LLMException {
    PipelineResult result =
        pipeline.generateJfrPath("top 10 hottest methods", new QueryPipeline.PipelineContext());

    assertTrue(result.hasQuery());
    String query = result.query();
    assertNotNull(query);

    assertTrue(query.contains("ExecutionSample"));
    assertTrue(query.contains("top") || query.contains("groupBy"));

    System.out.println("Generated query: " + query);
    System.out.println("Explanation: " + result.explanation());
  }

  @Test
  void testPipeline_AmbiguousQuery() throws LLMException {
    PipelineResult result =
        pipeline.generateJfrPath("show threads", new QueryPipeline.PipelineContext());

    // Depending on classification confidence, might need clarification
    if (result.needsClarification()) {
      assertTrue(result.clarification().isPresent());
      assertFalse(result.clarification().get().clarificationQuestion().isEmpty());
      assertFalse(result.clarification().get().suggestedChoices().isEmpty());

      System.out.println(
          "Clarification needed: " + result.clarification().get().clarificationQuestion());
      System.out.println("Choices: " + result.clarification().get().suggestedChoices());
    } else {
      assertTrue(result.hasQuery());
      System.out.println("Generated query: " + result.query());
    }
  }

  @Test
  void testPipeline_DebugTrace() throws LLMException {
    // Enable debug mode
    System.setProperty("jfr.shell.debug", "true");

    try {
      PipelineResult result =
          pipeline.generateJfrPath("count GC events", new QueryPipeline.PipelineContext());

      assertTrue(result.hasQuery());
      assertTrue(result.trace().isPresent(), "Should have trace in debug mode");

      PipelineTrace trace = result.trace().get();
      assertFalse(trace.getSteps().isEmpty(), "Trace should have steps");

      System.out.println("\nPipeline trace:");
      System.out.println(trace);
    } finally {
      System.clearProperty("jfr.shell.debug");
    }
  }
}

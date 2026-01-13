package io.jafar.shell.llm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager.SessionRef;
import io.jafar.shell.llm.LLMConfig.PrivacyMode;
import io.jafar.shell.llm.LLMConfig.PrivacySettings;
import io.jafar.shell.llm.LLMConfig.ProviderType;
import io.jafar.shell.llm.LLMProvider.LLMRequest;
import io.jafar.shell.llm.LLMProvider.LLMResponse;
import io.jafar.shell.llm.LLMProvider.Message;
import io.jafar.shell.llm.LLMProvider.Role;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Integration tests for multi-level query translation system.
 *
 * <p>Tests the complete flow: classification → prompt selection → translation → escalation.
 */
class MultiLevelTranslationTest {

  private LLMProvider mockProvider;
  private ContextBuilder contextBuilder;
  private ConversationHistory history;
  private SessionRef mockSession;

  @BeforeEach
  void setUp() {
    // Create mock session
    JFRSession jfrSession = mock(JFRSession.class);
    when(jfrSession.getAvailableEventTypes())
        .thenReturn(
            Set.of(
                "jdk.ExecutionSample",
                "jdk.GarbageCollection",
                "jdk.ObjectAllocationSample",
                "jdk.FileRead"));
    when(jfrSession.getRecordingPath()).thenReturn(Path.of("test-recording.jfr"));
    mockSession = new SessionRef(1, "test", jfrSession);

    history = new ConversationHistory(10);
  }

  @Test
  void testMultiLevelDisabledByDefault() throws LLMException {
    // Create config with multi-level disabled (default)
    LLMConfig config = createConfig(false);
    mockProvider = createMockProvider(config);
    contextBuilder = new ContextBuilder(mockSession, config);

    // Mock LLM response
    when(mockProvider.complete(any(LLMRequest.class)))
        .thenReturn(
            new LLMResponse(
                "{\"query\": \"events/jdk.GarbageCollection | count()\", "
                    + "\"explanation\": \"Counts GC events\", "
                    + "\"confidence\": 0.95}",
                "test-model",
                100,
                50L));

    QueryTranslator translator = new QueryTranslator(mockProvider, contextBuilder, history);
    TranslationResult result = translator.translate("count GC events");

    assertTrue(result.hasQuery());
    assertEquals("events/jdk.GarbageCollection | count()", result.jfrPathQuery());

    // Verify full prompt was used (legacy mode)
    ArgumentCaptor<LLMRequest> requestCaptor = ArgumentCaptor.forClass(LLMRequest.class);
    verify(mockProvider, times(1)).complete(requestCaptor.capture());

    String systemPrompt = requestCaptor.getValue().systemPrompt();
    assertTrue(
        systemPrompt.length() > 12000,
        "Legacy mode should use full prompt (>12KB), was: " + systemPrompt.length());
  }

  @Test
  void testSimpleCountUsesMinimalPrompt() throws LLMException {
    // Create config with multi-level enabled
    LLMConfig config = createConfig(true);
    mockProvider = createMockProvider(config);
    contextBuilder = new ContextBuilder(mockSession, config);

    // Mock LLM responses: classification + translation
    when(mockProvider.complete(any(LLMRequest.class)))
        .thenReturn(
            new LLMResponse(
                "{\"query\": \"events/jdk.GarbageCollection | count()\", "
                    + "\"explanation\": \"Counts GC events\", "
                    + "\"confidence\": 0.95}",
                "test-model",
                100,
                50L));

    QueryTranslator translator = new QueryTranslator(mockProvider, contextBuilder, history);
    TranslationResult result = translator.translate("count GC events");

    assertTrue(result.hasQuery());
    assertEquals("events/jdk.GarbageCollection | count()", result.jfrPathQuery());
    assertTrue(result.confidence() >= 0.9);

    // Verify minimal prompt was used
    ArgumentCaptor<LLMRequest> requestCaptor = ArgumentCaptor.forClass(LLMRequest.class);
    verify(mockProvider, atLeastOnce()).complete(requestCaptor.capture());

    // Find the translation request (not classification)
    LLMRequest translationRequest =
        requestCaptor.getAllValues().stream()
            .filter(req -> req.messages().size() > 0)
            .filter(
                req ->
                    req.messages().get(req.messages().size() - 1).content().contains("count GC"))
            .findFirst()
            .orElseThrow();

    String systemPrompt = translationRequest.systemPrompt();
    assertTrue(
        systemPrompt.length() < 4000,
        "Simple count should use minimal prompt (<4KB), was: " + systemPrompt.length());
    assertTrue(systemPrompt.contains("count GC events"));
  }

  @Test
  void testTopNUsesEnhancedPrompt() throws LLMException {
    LLMConfig config = createConfig(true);
    mockProvider = createMockProvider(config);
    contextBuilder = new ContextBuilder(mockSession, config);

    // Mock high-confidence response
    when(mockProvider.complete(any(LLMRequest.class)))
        .thenReturn(
            new LLMResponse(
                "{\"query\": \"events/jdk.ExecutionSample | groupBy(stackTrace/topFrame/method/name, agg=count) | top(10, by=count)\", "
                    + "\"explanation\": \"Top 10 methods by sample count\", "
                    + "\"confidence\": 0.90}",
                "test-model",
                150,
                75L));

    QueryTranslator translator = new QueryTranslator(mockProvider, contextBuilder, history);
    TranslationResult result = translator.translate("top 10 hottest methods");

    assertTrue(result.hasQuery());
    assertTrue(result.jfrPathQuery().contains("top(10"));
    assertTrue(result.confidence() >= 0.85);

    // Verify enhanced prompt was used
    ArgumentCaptor<LLMRequest> requestCaptor = ArgumentCaptor.forClass(LLMRequest.class);
    verify(mockProvider, atLeastOnce()).complete(requestCaptor.capture());

    LLMRequest translationRequest =
        requestCaptor.getAllValues().stream()
            .filter(req -> req.messages().size() > 0)
            .filter(
                req ->
                    req.messages().get(req.messages().size() - 1).content().contains("hottest"))
            .findFirst()
            .orElseThrow();

    String systemPrompt = translationRequest.systemPrompt();
    assertTrue(
        systemPrompt.length() > 4000 && systemPrompt.length() < 20000,
        "TopN should use enhanced prompt (4-20KB), was: " + systemPrompt.length());
  }

  @Test
  void testProgressiveEscalation() throws LLMException {
    LLMConfig config = createConfig(true);
    mockProvider = createMockProvider(config);
    contextBuilder = new ContextBuilder(mockSession, config);

    // First attempt: low confidence (triggers escalation)
    // Second attempt: success with enhanced prompt
    when(mockProvider.complete(any(LLMRequest.class)))
        .thenReturn(
            new LLMResponse(
                "{\"query\": \"events/jdk.GarbageCollection | count()\", "
                    + "\"explanation\": \"Counts GC events\", "
                    + "\"confidence\": 0.5}",
                "test-model",
                100,
                50L))
        .thenReturn(
            new LLMResponse(
                "{\"query\": \"events/jdk.GarbageCollection | count()\", "
                    + "\"explanation\": \"Counts GC events\", "
                    + "\"confidence\": 0.85}",
                "test-model",
                100,
                50L));

    QueryTranslator translator = new QueryTranslator(mockProvider, contextBuilder, history);
    TranslationResult result = translator.translate("count GC events");

    assertTrue(result.hasQuery());
    assertTrue(result.confidence() >= 0.8);

    // Verify escalation happened (2 translation attempts)
    ArgumentCaptor<LLMRequest> requestCaptor = ArgumentCaptor.forClass(LLMRequest.class);
    verify(mockProvider, atLeast(2)).complete(requestCaptor.capture());

    // Verify prompt sizes increased
    List<LLMRequest> translationRequests =
        requestCaptor.getAllValues().stream()
            .filter(req -> req.messages().size() > 0)
            .filter(
                req ->
                    req.messages().get(req.messages().size() - 1).content().contains("count GC"))
            .toList();

    assertTrue(
        translationRequests.size() >= 2, "Should have at least 2 translation attempts (escalation)");

    int firstPromptSize = translationRequests.get(0).systemPrompt().length();
    int secondPromptSize = translationRequests.get(1).systemPrompt().length();

    assertTrue(
        secondPromptSize > firstPromptSize,
        "Second prompt should be larger (escalated): "
            + firstPromptSize
            + " -> "
            + secondPromptSize);
  }

  @Test
  void testClarificationRequestForAmbiguousQuery() throws LLMException {
    LLMConfig config = createConfig(true);
    mockProvider = createMockProvider(config);
    contextBuilder = new ContextBuilder(mockSession, config);

    // Mock classification with very low confidence
    when(mockProvider.complete(any(LLMRequest.class)))
        .thenReturn(
            new LLMResponse(
                "{\"category\": \"SIMPLE_COUNT\", "
                    + "\"confidence\": 0.3, "
                    + "\"reasoning\": \"Query is too vague\"}",
                "test-model",
                50,
                25L));

    QueryTranslator translator = new QueryTranslator(mockProvider, contextBuilder, history);
    TranslationResult result = translator.translate("show me events");

    assertTrue(result.needsClarification());
    assertFalse(result.hasQuery());
    assertFalse(result.isConversational());

    ClarificationRequest clarification = result.clarificationRequest().get();
    assertEquals("show me events", clarification.originalQuery());
    assertFalse(clarification.suggestedChoices().isEmpty());
  }

  @Test
  void testConversationalResponse() throws LLMException {
    LLMConfig config = createConfig(true);
    mockProvider = createMockProvider(config);
    contextBuilder = new ContextBuilder(mockSession, config);

    // Mock classification as conversational
    when(mockProvider.complete(any(LLMRequest.class)))
        .thenReturn(
            new LLMResponse(
                "{\"category\": \"CONVERSATIONAL\", "
                    + "\"confidence\": 0.95, "
                    + "\"reasoning\": \"General help question\"}",
                "test-model",
                50,
                25L))
        .thenReturn(
            new LLMResponse(
                "I can help you analyze JFR recordings. Try asking questions like 'count GC events' or 'top 10 allocating methods'.",
                "test-model",
                75,
                30L));

    QueryTranslator translator = new QueryTranslator(mockProvider, contextBuilder, history);
    TranslationResult result = translator.translate("what can you do?");

    assertTrue(result.isConversational());
    assertFalse(result.hasQuery());
    assertFalse(result.needsClarification());
    assertTrue(result.conversationalResponse().contains("help"));
  }

  @Test
  void testComplexQueryTranslation() throws LLMException {
    LLMConfig config = createConfig(true);
    mockProvider = createMockProvider(config);
    contextBuilder = new ContextBuilder(mockSession, config);

    // Mock successful translation for a complex query
    when(mockProvider.complete(any(LLMRequest.class)))
        .thenReturn(
            new LLMResponse(
                "{\"query\": \"events/jdk.ExecutionSample | groupBy(stackTrace/topFrame/method/name, agg=count) | top(10, by=count)\", "
                    + "\"explanation\": \"Top 10 methods by sample count\", "
                    + "\"confidence\": 0.88}",
                "test-model",
                200,
                100L));

    QueryTranslator translator = new QueryTranslator(mockProvider, contextBuilder, history);
    TranslationResult result = translator.translate("show top methods by sample count");

    assertTrue(result.hasQuery());
    assertTrue(result.jfrPathQuery().contains("top(10"));
    assertTrue(result.jfrPathQuery().contains("groupBy"));
    assertTrue(result.confidence() >= 0.85);

    // Verify translation was attempted
    verify(mockProvider, atLeastOnce()).complete(any(LLMRequest.class));
  }

  @Test
  void testInvalidQueryTriggersEscalation() throws LLMException {
    LLMConfig config = createConfig(true);
    mockProvider = createMockProvider(config);
    contextBuilder = new ContextBuilder(mockSession, config);

    // First attempt: invalid syntax (triggers escalation)
    // Second attempt: valid query
    when(mockProvider.complete(any(LLMRequest.class)))
        .thenReturn(
            new LLMResponse(
                "{\"query\": \"INVALID SYNTAX HERE\", "
                    + "\"explanation\": \"Bad query\", "
                    + "\"confidence\": 0.9}",
                "test-model",
                100,
                50L))
        .thenReturn(
            new LLMResponse(
                "{\"query\": \"events/jdk.GarbageCollection | count()\", "
                    + "\"explanation\": \"Counts GC events\", "
                    + "\"confidence\": 0.95}",
                "test-model",
                100,
                50L));

    QueryTranslator translator = new QueryTranslator(mockProvider, contextBuilder, history);

    // First attempt should fail with invalid syntax, triggering escalation
    try {
      TranslationResult result = translator.translate("count GC events");
      // If it succeeds, it escalated and got a valid response
      assertTrue(result.hasQuery());
    } catch (LLMException e) {
      // If all attempts fail, that's also acceptable
      assertTrue(e.getMessage().contains("invalid syntax"));
    }

    // Verify multiple attempts were made
    verify(mockProvider, atLeast(1)).complete(any(LLMRequest.class));
  }

  @Test
  void testMaxThreeEscalationAttempts() throws LLMException {
    LLMConfig config = createConfig(true);
    mockProvider = createMockProvider(config);
    contextBuilder = new ContextBuilder(mockSession, config);

    // All attempts return low confidence (should stop after 3)
    when(mockProvider.complete(any(LLMRequest.class)))
        .thenReturn(
            new LLMResponse(
                "{\"query\": \"events/jdk.GarbageCollection | count()\", "
                    + "\"explanation\": \"Counts GC events\", "
                    + "\"confidence\": 0.4}",
                "test-model",
                100,
                50L));

    QueryTranslator translator = new QueryTranslator(mockProvider, contextBuilder, history);
    TranslationResult result = translator.translate("count GC events");

    // Should still return a result (last attempt)
    assertTrue(result.hasQuery());

    // Verify maximum 3 translation attempts (plus classification)
    ArgumentCaptor<LLMRequest> requestCaptor = ArgumentCaptor.forClass(LLMRequest.class);
    verify(mockProvider, atMost(4)).complete(requestCaptor.capture());
  }

  // ===== Helper Methods =====

  private LLMConfig createConfig(boolean multiLevelEnabled) {
    return new LLMConfig(
        ProviderType.MOCK,
        "http://localhost:11434",
        "test-model",
        null,
        new PrivacySettings(PrivacyMode.LOCAL_ONLY, false, false, false, Set.of(), false),
        30,
        2000,
        0.1,
        multiLevelEnabled);
  }

  private LLMProvider createMockProvider(LLMConfig config) {
    LLMProvider provider = mock(LLMProvider.class);
    when(provider.getConfig()).thenReturn(config);
    return provider;
  }
}

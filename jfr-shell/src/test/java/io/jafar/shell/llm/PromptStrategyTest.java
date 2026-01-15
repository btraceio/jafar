package io.jafar.shell.llm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.jafar.shell.llm.PromptStrategy.PromptLevel;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PromptStrategyTest {
  private ContextBuilder mockContextBuilder;
  private PromptStrategy strategy;

  @BeforeEach
  void setUp() {
    mockContextBuilder = mock(ContextBuilder.class);
    strategy = new PromptStrategy(mockContextBuilder);
  }

  // ===== PromptLevel Enum Tests =====

  @Test
  void testPromptLevelProgression() {
    assertEquals(PromptLevel.ENHANCED, PromptLevel.MINIMAL.next());
    assertEquals(PromptLevel.FULL, PromptLevel.ENHANCED.next());
    assertNull(PromptLevel.FULL.next());
  }

  @Test
  void testPromptLevelIsFinal() {
    assertFalse(PromptLevel.MINIMAL.isFinal());
    assertFalse(PromptLevel.ENHANCED.isFinal());
    assertTrue(PromptLevel.FULL.isFinal());
  }

  // ===== Start Level Selection Tests =====

  @Test
  void testSelectStartLevel_HighConfidenceSimple_Minimal() {
    var classification =
        ClassificationResult.fromRules(QueryCategory.SIMPLE_COUNT, 0.92); // high confidence, simple

    var level = strategy.selectStartLevel(classification);

    assertEquals(PromptLevel.MINIMAL, level);
  }

  @Test
  void testSelectStartLevel_HighConfidenceComplex_Enhanced() {
    var classification =
        ClassificationResult.fromRules(
            QueryCategory.TOPN_RANKING, 0.90); // high confidence, not simple

    var level = strategy.selectStartLevel(classification);

    assertEquals(PromptLevel.ENHANCED, level);
  }

  @Test
  void testSelectStartLevel_MediumConfidence_Enhanced() {
    var classification =
        ClassificationResult.fromRules(QueryCategory.STATISTICS, 0.75); // medium confidence

    var level = strategy.selectStartLevel(classification);

    assertEquals(PromptLevel.ENHANCED, level);
  }

  @Test
  void testSelectStartLevel_LowConfidence_Full() {
    var classification =
        ClassificationResult.fromRules(QueryCategory.GROUPBY_AGGREGATED, 0.55); // low confidence

    var level = strategy.selectStartLevel(classification);

    assertEquals(PromptLevel.FULL, level);
  }

  @Test
  void testSelectStartLevel_ComplexMultiOp_Full() {
    var classification =
        ClassificationResult.fromRules(QueryCategory.COMPLEX_MULTIOP, 0.95); // always full

    var level = strategy.selectStartLevel(classification);

    assertEquals(PromptLevel.FULL, level);
  }

  @Test
  void testSelectStartLevel_DefaultToEnhanced() {
    // Create a classification with exactly 0.6 confidence (boundary case)
    var classification = ClassificationResult.fromRules(QueryCategory.SIMPLE_FILTER, 0.6);

    var level = strategy.selectStartLevel(classification);

    assertEquals(PromptLevel.ENHANCED, level);
  }

  @Test
  void testSelectStartLevel_ExistenceCheck_Minimal() {
    var classification = ClassificationResult.fromRules(QueryCategory.EXISTENCE_CHECK, 0.92);

    var level = strategy.selectStartLevel(classification);

    assertEquals(PromptLevel.MINIMAL, level);
  }

  @Test
  void testSelectStartLevel_MetadataQuery_Minimal() {
    var classification = ClassificationResult.fromRules(QueryCategory.METADATA_QUERY, 0.95);

    var level = strategy.selectStartLevel(classification);

    assertEquals(PromptLevel.MINIMAL, level);
  }

  // ===== Escalation Decision Tests =====

  @Test
  void testShouldEscalate_AlreadyFull_NoEscalation() {
    var result = TranslationResult.success("events/jdk.GC", "Gets GC events", 0.5);

    boolean shouldEscalate =
        strategy.shouldEscalate(result, 1, PromptLevel.FULL, QueryCategory.SIMPLE_COUNT);

    assertFalse(shouldEscalate);
  }

  @Test
  void testShouldEscalate_MaxAttempts_NoEscalation() {
    var result = TranslationResult.success("events/jdk.GC", "Gets GC events", 0.5);

    boolean shouldEscalate =
        strategy.shouldEscalate(result, 3, PromptLevel.ENHANCED, QueryCategory.SIMPLE_COUNT);

    assertFalse(shouldEscalate);
  }

  @Test
  void testShouldEscalate_LowConfidence_Escalates() {
    var result = TranslationResult.success("events/jdk.GC", "Gets GC events", 0.5); // < 0.6

    boolean shouldEscalate =
        strategy.shouldEscalate(result, 1, PromptLevel.MINIMAL, QueryCategory.SIMPLE_COUNT);

    assertTrue(shouldEscalate);
  }

  @Test
  void testShouldEscalate_NoQuery_Escalates() {
    var result = TranslationResult.success(null, null, 0.8); // no query

    boolean shouldEscalate =
        strategy.shouldEscalate(result, 1, PromptLevel.MINIMAL, QueryCategory.SIMPLE_COUNT);

    assertTrue(shouldEscalate);
  }

  @Test
  void testShouldEscalate_Conversational_NoEscalation() {
    var result = TranslationResult.conversational("Hello! How can I help?");

    boolean shouldEscalate =
        strategy.shouldEscalate(result, 1, PromptLevel.MINIMAL, QueryCategory.CONVERSATIONAL);

    assertFalse(shouldEscalate);
  }

  @Test
  void testShouldEscalate_Clarification_NoEscalation() {
    var clarification =
        new ClarificationRequest(
            "show threads", "What do you want?", java.util.List.of("Metadata", "Events"), 0.8);
    var result = TranslationResult.needsClarification(clarification);

    boolean shouldEscalate =
        strategy.shouldEscalate(result, 1, PromptLevel.MINIMAL, QueryCategory.SIMPLE_COUNT);

    assertFalse(shouldEscalate);
  }

  @Test
  void testShouldEscalate_AmbiguousWarning_Escalates() {
    var result =
        TranslationResult.successWithWarning(
            "events/jdk.GC", "Gets GC events", 0.8, "Query is ambiguous");

    boolean shouldEscalate =
        strategy.shouldEscalate(result, 1, PromptLevel.MINIMAL, QueryCategory.SIMPLE_COUNT);

    assertTrue(shouldEscalate);
  }

  @Test
  void testShouldEscalate_NonAmbiguousWarning_NoEscalation() {
    var result =
        TranslationResult.successWithWarning(
            "events/jdk.GC", "Gets GC events", 0.8, "Performance might be slow");

    boolean shouldEscalate =
        strategy.shouldEscalate(result, 1, PromptLevel.MINIMAL, QueryCategory.SIMPLE_COUNT);

    assertFalse(shouldEscalate);
  }

  @Test
  void testShouldEscalate_HighConfidence_NoEscalation() {
    var result = TranslationResult.success("events/jdk.GC | count()", "Counts GC events", 0.95);

    boolean shouldEscalate =
        strategy.shouldEscalate(result, 1, PromptLevel.MINIMAL, QueryCategory.SIMPLE_COUNT);

    assertFalse(shouldEscalate);
  }

  @Test
  void testShouldEscalate_Attempt2_StillEscalates() {
    var result = TranslationResult.success("events/jdk.GC", "Gets GC events", 0.5);

    boolean shouldEscalate =
        strategy.shouldEscalate(result, 2, PromptLevel.ENHANCED, QueryCategory.SIMPLE_COUNT);

    assertTrue(shouldEscalate);
  }

  // ===== Build Prompt Tests =====

  @Test
  void testBuildPrompt_Minimal() throws IOException {
    when(mockContextBuilder.buildMinimalPrompt(QueryCategory.SIMPLE_COUNT))
        .thenReturn("MINIMAL_PROMPT");

    String prompt = strategy.buildPrompt(QueryCategory.SIMPLE_COUNT, PromptLevel.MINIMAL);

    assertEquals("MINIMAL_PROMPT", prompt);
    verify(mockContextBuilder).buildMinimalPrompt(QueryCategory.SIMPLE_COUNT);
    verify(mockContextBuilder, never()).buildEnhancedPrompt(any(), any());
    verify(mockContextBuilder, never()).buildSystemPrompt();
  }

  @Test
  void testBuildPrompt_Enhanced() throws IOException {
    Set<QueryCategory> related = QueryCategory.STATISTICS.getRelatedCategories();
    when(mockContextBuilder.buildEnhancedPrompt(QueryCategory.STATISTICS, related))
        .thenReturn("ENHANCED_PROMPT");

    String prompt = strategy.buildPrompt(QueryCategory.STATISTICS, PromptLevel.ENHANCED);

    assertEquals("ENHANCED_PROMPT", prompt);
    verify(mockContextBuilder).buildEnhancedPrompt(eq(QueryCategory.STATISTICS), eq(related));
    verify(mockContextBuilder, never()).buildMinimalPrompt(any());
    verify(mockContextBuilder, never()).buildSystemPrompt();
  }

  @Test
  void testBuildPrompt_Full() throws IOException {
    when(mockContextBuilder.buildSystemPrompt()).thenReturn("FULL_SYSTEM_PROMPT");

    String prompt = strategy.buildPrompt(QueryCategory.COMPLEX_MULTIOP, PromptLevel.FULL);

    assertEquals("FULL_SYSTEM_PROMPT", prompt);
    verify(mockContextBuilder).buildSystemPrompt();
    verify(mockContextBuilder, never()).buildMinimalPrompt(any());
    verify(mockContextBuilder, never()).buildEnhancedPrompt(any(), any());
  }

  @Test
  void testBuildPrompt_IOExceptionWrapped() throws IOException {
    when(mockContextBuilder.buildMinimalPrompt(any()))
        .thenThrow(new IOException("Resource not found"));

    RuntimeException exception =
        assertThrows(
            RuntimeException.class,
            () -> strategy.buildPrompt(QueryCategory.SIMPLE_COUNT, PromptLevel.MINIMAL));

    assertTrue(exception.getMessage().contains("Failed to build"));
    assertTrue(exception.getMessage().contains("MINIMAL"));
    assertTrue(exception.getMessage().contains("SIMPLE_COUNT"));
    assertEquals(IOException.class, exception.getCause().getClass());
  }

  @ParameterizedTest
  @EnumSource(QueryCategory.class)
  void testBuildPrompt_AllCategories_Minimal(QueryCategory category) throws IOException {
    when(mockContextBuilder.buildMinimalPrompt(category)).thenReturn("PROMPT_FOR_" + category);

    String prompt = strategy.buildPrompt(category, PromptLevel.MINIMAL);

    assertEquals("PROMPT_FOR_" + category, prompt);
    verify(mockContextBuilder).buildMinimalPrompt(category);
  }

  @ParameterizedTest
  @EnumSource(QueryCategory.class)
  void testBuildPrompt_AllCategories_Enhanced(QueryCategory category) throws IOException {
    Set<QueryCategory> related = category.getRelatedCategories();
    when(mockContextBuilder.buildEnhancedPrompt(category, related))
        .thenReturn("ENHANCED_FOR_" + category);

    String prompt = strategy.buildPrompt(category, PromptLevel.ENHANCED);

    assertEquals("ENHANCED_FOR_" + category, prompt);
    verify(mockContextBuilder).buildEnhancedPrompt(category, related);
  }

  @ParameterizedTest
  @EnumSource(QueryCategory.class)
  void testBuildPrompt_AllCategories_Full(QueryCategory category) throws IOException {
    when(mockContextBuilder.buildSystemPrompt()).thenReturn("FULL_PROMPT");

    String prompt = strategy.buildPrompt(category, PromptLevel.FULL);

    assertEquals("FULL_PROMPT", prompt);
    verify(mockContextBuilder).buildSystemPrompt();
  }

  // ===== Token Estimation Tests =====

  @Test
  void testEstimateTokenCount_Minimal() {
    int tokens = strategy.estimateTokenCount(PromptLevel.MINIMAL);

    assertEquals(750, tokens);
  }

  @Test
  void testEstimateTokenCount_Enhanced() {
    int tokens = strategy.estimateTokenCount(PromptLevel.ENHANCED);

    assertEquals(2000, tokens);
  }

  @Test
  void testEstimateTokenCount_Full() {
    int tokens = strategy.estimateTokenCount(PromptLevel.FULL);

    assertEquals(4000, tokens);
  }

  // ===== Reduction Calculation Tests =====

  @Test
  void testCalculateReduction_Minimal() {
    double reduction = strategy.calculateReduction(PromptLevel.MINIMAL);

    // 750 vs 4000 = (4000-750)/4000 = 0.8125 = 81.25%
    assertEquals(81.25, reduction, 0.01);
  }

  @Test
  void testCalculateReduction_Enhanced() {
    double reduction = strategy.calculateReduction(PromptLevel.ENHANCED);

    // 2000 vs 4000 = (4000-2000)/4000 = 0.5 = 50%
    assertEquals(50.0, reduction, 0.01);
  }

  @Test
  void testCalculateReduction_Full() {
    double reduction = strategy.calculateReduction(PromptLevel.FULL);

    // 4000 vs 4000 = 0%
    assertEquals(0.0, reduction, 0.01);
  }

  // ===== Integration Scenario Tests =====

  @Test
  void testScenario_SimpleCountSuccessWithMinimal() throws IOException {
    // 1. High confidence simple query → MINIMAL
    var classification = ClassificationResult.fromRules(QueryCategory.SIMPLE_COUNT, 0.91);
    var level = strategy.selectStartLevel(classification);
    assertEquals(PromptLevel.MINIMAL, level);

    // 2. Build minimal prompt
    when(mockContextBuilder.buildMinimalPrompt(QueryCategory.SIMPLE_COUNT)).thenReturn("MINIMAL");
    String prompt = strategy.buildPrompt(QueryCategory.SIMPLE_COUNT, level);
    assertEquals("MINIMAL", prompt);

    // 3. Success on first try, no escalation
    var result = TranslationResult.success("events/jdk.GC | count()", "Counts GC events", 0.95);
    assertFalse(strategy.shouldEscalate(result, 1, level, QueryCategory.SIMPLE_COUNT));
  }

  @Test
  void testScenario_TopNEscalationToFull() throws IOException {
    // 1. Medium confidence complex query → ENHANCED
    var classification = ClassificationResult.fromRules(QueryCategory.TOPN_RANKING, 0.75);
    var level = strategy.selectStartLevel(classification);
    assertEquals(PromptLevel.ENHANCED, level);

    // 2. Build enhanced prompt
    when(mockContextBuilder.buildEnhancedPrompt(eq(QueryCategory.TOPN_RANKING), any(Set.class)))
        .thenReturn("ENHANCED");
    String prompt1 = strategy.buildPrompt(QueryCategory.TOPN_RANKING, level);
    assertEquals("ENHANCED", prompt1);

    // 3. First attempt returns low confidence → escalate
    var result1 =
        TranslationResult.success("groupBy(...) | top(10)", "Top 10 ranking", 0.55); // Low conf
    assertTrue(strategy.shouldEscalate(result1, 1, level, QueryCategory.TOPN_RANKING));

    // 4. Escalate to FULL
    level = level.next();
    assertEquals(PromptLevel.FULL, level);

    // 5. Build full prompt
    when(mockContextBuilder.buildSystemPrompt()).thenReturn("FULL");
    String prompt2 = strategy.buildPrompt(QueryCategory.TOPN_RANKING, level);
    assertEquals("FULL", prompt2);

    // 6. Second attempt succeeds → no more escalation
    var result2 =
        TranslationResult.success("groupBy(...) | top(10, by=sum)", "Top 10 ranking", 0.92);
    assertFalse(strategy.shouldEscalate(result2, 2, level, QueryCategory.TOPN_RANKING));
  }

  @Test
  void testScenario_ComplexMultiOpStartsFull() {
    // 1. Complex query always starts with FULL
    var classification = ClassificationResult.fromRules(QueryCategory.COMPLEX_MULTIOP, 0.95);
    var level = strategy.selectStartLevel(classification);

    assertEquals(PromptLevel.FULL, level);
    assertTrue(level.isFinal());
    assertNull(level.next());
  }

  @Test
  void testScenario_MaxAttemptsReached() {
    // After 3 attempts, no more escalation even if low confidence
    var result = TranslationResult.success("query", "explanation", 0.4);

    assertFalse(
        strategy.shouldEscalate(result, 3, PromptLevel.ENHANCED, QueryCategory.SIMPLE_COUNT));
  }

  @Test
  void testScenario_ClarificationStopsEscalation() {
    // If result needs clarification, don't escalate
    var clarification =
        new ClarificationRequest(
            "show threads", "What do you want?", java.util.List.of("A", "B"), 0.7);
    var result = TranslationResult.needsClarification(clarification);

    assertFalse(
        strategy.shouldEscalate(result, 1, PromptLevel.MINIMAL, QueryCategory.SIMPLE_COUNT));
  }

  // ===== Edge Cases =====

  @Test
  void testEdgeCase_ConfidenceExactly06_NoEscalation() {
    var result = TranslationResult.success("query", "explanation", 0.6);

    assertFalse(
        strategy.shouldEscalate(result, 1, PromptLevel.MINIMAL, QueryCategory.SIMPLE_COUNT));
  }

  @Test
  void testEdgeCase_ConfidenceJustBelow06_Escalates() {
    var result = TranslationResult.success("query", "explanation", 0.59);

    assertTrue(strategy.shouldEscalate(result, 1, PromptLevel.MINIMAL, QueryCategory.SIMPLE_COUNT));
  }

  @Test
  void testEdgeCase_EmptyQueryWithHighConfidence() {
    var result = TranslationResult.success("", "explanation", 0.95);

    // Empty query should trigger escalation despite high confidence
    assertTrue(strategy.shouldEscalate(result, 1, PromptLevel.MINIMAL, QueryCategory.SIMPLE_COUNT));
  }

  @Test
  void testEdgeCase_BlankQueryWithHighConfidence() {
    var result = TranslationResult.success("   ", "explanation", 0.95);

    // Blank query should trigger escalation despite high confidence
    assertTrue(strategy.shouldEscalate(result, 1, PromptLevel.MINIMAL, QueryCategory.SIMPLE_COUNT));
  }
}

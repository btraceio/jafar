package io.jafar.shell.llm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager.SessionRef;
import io.jafar.shell.llm.LLMConfig.PrivacyMode;
import io.jafar.shell.llm.LLMConfig.PrivacySettings;
import io.jafar.shell.llm.LLMConfig.ProviderType;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for ContextBuilder with real resource files.
 *
 * <p>These tests verify that the multi-level prompt building actually works with the
 * category-specific resource files.
 */
class ContextBuilderIntegrationTest {

  @Test
  void testBuildMinimalPrompt_SimpleCount() throws IOException {
    ContextBuilder builder = createTestContextBuilder();

    String prompt = builder.buildMinimalPrompt(QueryCategory.SIMPLE_COUNT);

    // Should contain core components
    assertContains(prompt, "You are an expert JFR");
    assertContains(prompt, "JfrPath Syntax");
    assertContains(prompt, "EXAMPLES FOR THIS QUERY TYPE");
    assertContains(prompt, "count GC events");

    // Should NOT contain examples from other categories
    assertNotContains(prompt, "top 10");
    assertNotContains(prompt, "decorateByTime");

    // Should be relatively small (~2-3KB)
    assertTrue(
        prompt.length() < 4000,
        "Minimal prompt should be < 4KB, was: " + prompt.length() + " bytes");
  }

  @Test
  void testBuildMinimalPrompt_TopN() throws IOException {
    ContextBuilder builder = createTestContextBuilder();

    String prompt = builder.buildMinimalPrompt(QueryCategory.TOPN_RANKING);

    // Should contain topN examples
    assertContains(prompt, "top 10 hottest methods");
    assertContains(prompt, "CATEGORY-SPECIFIC RULES");

    // Should NOT contain unrelated examples
    assertNotContains(prompt, "is ExecutionSample present");
  }

  @Test
  void testBuildMinimalPrompt_DecoratorTemporal() throws IOException {
    ContextBuilder builder = createTestContextBuilder();

    String prompt = builder.buildMinimalPrompt(QueryCategory.DECORATOR_TEMPORAL);

    // Should contain decorator examples and rules
    assertContains(prompt, "decorateByTime");
    assertContains(prompt, "CATEGORY-SPECIFIC RULES");
    assertContains(prompt, "virtual thread pinning");
  }

  @Test
  void testBuildEnhancedPrompt_WithRelatedCategories() throws IOException {
    ContextBuilder builder = createTestContextBuilder();

    Set<QueryCategory> related = QueryCategory.TOPN_RANKING.getRelatedCategories();
    String prompt = builder.buildEnhancedPrompt(QueryCategory.TOPN_RANKING, related);

    // Should contain primary category
    assertContains(prompt, "EXAMPLES FOR PRIMARY QUERY TYPE");
    assertContains(prompt, "top 10 hottest methods");

    // Should contain related categories
    assertContains(prompt, "RELATED QUERY TYPES");
    for (QueryCategory cat : related) {
      assertContains(prompt, cat.name());
    }

    // Should contain incorrect examples
    assertContains(prompt, "INCORRECT");

    // Should contain field name rules
    assertContains(prompt, "FIELD NAME");

    // Should be larger than minimal but not too large
    // Note: TOPN has 2 related categories so it can be larger than originally estimated
    assertTrue(
        prompt.length() > 4000 && prompt.length() < 20000,
        "Enhanced prompt should be 4-20KB, was: " + prompt.length() + " bytes");
  }

  @Test
  void testBuildEnhancedPrompt_DecoratorIncludesDecoratorRules() throws IOException {
    ContextBuilder builder = createTestContextBuilder();

    String prompt =
        builder.buildEnhancedPrompt(
            QueryCategory.DECORATOR_TEMPORAL,
            QueryCategory.DECORATOR_TEMPORAL.getRelatedCategories());

    // Should include decorator syntax rules
    assertContains(prompt, "decorateByTime");
    assertContains(prompt, "decorateByKey");
  }

  @Test
  void testBuildFullPrompt_StillWorks() throws IOException {
    ContextBuilder builder = createTestContextBuilder();

    String prompt = builder.buildSystemPrompt();

    // Should contain all major sections
    assertContains(prompt, "You are an expert JFR");
    assertContains(prompt, "CORRECT EXAMPLES");
    assertContains(prompt, "decorateByTime");

    // Should be the largest
    assertTrue(
        prompt.length() > 12000, "Full prompt should be > 12KB, was: " + prompt.length() + " bytes");
  }

  @Test
  void testPromptSizeProgression() throws IOException {
    ContextBuilder builder = createTestContextBuilder();

    String minimal = builder.buildMinimalPrompt(QueryCategory.STATISTICS);
    String enhanced =
        builder.buildEnhancedPrompt(
            QueryCategory.STATISTICS, QueryCategory.STATISTICS.getRelatedCategories());
    String full = builder.buildSystemPrompt();

    // Size should increase: minimal < enhanced < full
    assertTrue(
        minimal.length() < enhanced.length(),
        "Minimal ("
            + minimal.length()
            + ") should be < Enhanced ("
            + enhanced.length()
            + ")");
    assertTrue(
        enhanced.length() < full.length(),
        "Enhanced (" + enhanced.length() + ") should be < Full (" + full.length() + ")");
  }

  // ===== Helper Methods =====

  private ContextBuilder createTestContextBuilder() {
    // Create a minimal test session
    SessionRef sessionRef = createMockSession();
    LLMConfig config = createTestConfig();
    return new ContextBuilder(sessionRef, config);
  }

  private SessionRef createMockSession() {
    // Create mock JFRSession
    JFRSession mockSession = mock(JFRSession.class);
    when(mockSession.getAvailableEventTypes())
        .thenReturn(
            Set.of(
                "jdk.ExecutionSample",
                "jdk.GarbageCollection",
                "jdk.ObjectAllocationSample",
                "jdk.FileRead"));
    when(mockSession.getRecordingPath()).thenReturn(Path.of("test-recording.jfr"));

    // Create SessionRef with mock session
    return new SessionRef(1, "test", mockSession);
  }

  private LLMConfig createTestConfig() {
    return new LLMConfig(
        ProviderType.LOCAL,
        "http://localhost:11434",
        "llama3",
        null,
        new PrivacySettings(
            PrivacyMode.LOCAL_ONLY, false, false, false, Set.of(), false),
        60,
        2000,
        0.7,
        false); // multiLevelEnabled not needed for ContextBuilder tests
  }

  private void assertContains(String text, String substring) {
    assertTrue(
        text.contains(substring),
        "Expected to find '" + substring + "' in text (length: " + text.length() + ")");
  }

  private void assertNotContains(String text, String substring) {
    assertFalse(
        text.contains(substring),
        "Expected NOT to find '" + substring + "' in text (length: " + text.length() + ")");
  }
}

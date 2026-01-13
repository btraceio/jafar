package io.jafar.shell.llm;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager.SessionRef;
import io.jafar.shell.jfrpath.JfrPathParser;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Integration test for improved LLM prompts. Tests that the LLM can correctly translate natural
 * language queries into valid JfrPath queries.
 *
 * <p>Run with: ./gradlew test -Dtest.llm.enabled=true
 *
 * <p>Requires Ollama running with llama3.1:8b model.
 */
@EnabledIfSystemProperty(named = "test.llm.enabled", matches = "true")
class ImprovedPromptsIntegrationTest {

  private SessionRef testSession;
  private QueryTranslator translator;

  @BeforeEach
  void setUp() throws Exception {
    // Open test JFR recording
    ParsingContext ctx = ParsingContext.create();
    JFRSession session = new JFRSession(Paths.get("src/test/resources/sample.jfr"), ctx);
    testSession = new SessionRef(1, "test", session);

    // Initialize LLM provider
    LLMConfig config;
    try {
      config = LLMConfig.load();
    } catch (Exception e) {
      config = LLMConfig.defaults();
    }

    LLMProvider provider = LLMProviderFactory.create(config);

    // Skip test if LLM provider not available
    if (!provider.isAvailable()) {
      System.err.println(
          "WARN: LLM provider not available, skipping integration test. "
              + "Run 'ollama serve' and 'ollama pull llama3.1:8b' to enable.");
      org.junit.jupiter.api.Assumptions.assumeTrue(false);
    }

    // Create translator
    ContextBuilder contextBuilder = new ContextBuilder(testSession, config);
    translator = new QueryTranslator(provider, contextBuilder, new ConversationHistory(0));
  }

  @Test
  void testTopAllocatingClasses() throws Exception {
    // This query previously failed - should now work with improved prompts
    String naturalQuery = "top allocating classes";

    TranslationResult result = translator.translate(naturalQuery);

    // Verify query is syntactically valid
    assertNotNull(result.jfrPathQuery(), "Query should not be null");
    assertDoesNotThrow(
        () -> JfrPathParser.parse(result.jfrPathQuery()),
        "Query should be syntactically valid: " + result.jfrPathQuery());

    // Verify it uses the correct event type and field path
    assertTrue(
        result.jfrPathQuery().contains("jdk.ObjectAllocationSample"),
        "Should use ObjectAllocationSample event type");
    assertTrue(
        result.jfrPathQuery().contains("objectClass/name"),
        "Should use objectClass/name field path, not eventThread/javaClass");

    System.out.println("✓ Top allocating classes: " + result.jfrPathQuery());
    System.out.println("  Explanation: " + result.explanation());
    System.out.println("  Confidence: " + result.confidence());
  }

  @Test
  void testMonitorContention() throws Exception {
    // This query previously failed - should now work with improved prompts
    String naturalQuery = "which monitors have the most contention";

    TranslationResult result = translator.translate(naturalQuery);

    // Verify query is syntactically valid
    assertNotNull(result.jfrPathQuery(), "Query should not be null");
    assertDoesNotThrow(
        () -> JfrPathParser.parse(result.jfrPathQuery()),
        "Query should be syntactically valid: " + result.jfrPathQuery());

    // Verify it uses the correct event type
    assertTrue(
        result.jfrPathQuery().contains("jdk.JavaMonitorEnter"),
        "Should use JavaMonitorEnter event type, not LockContended");
    assertTrue(
        result.jfrPathQuery().contains("monitorClass/name"),
        "Should use monitorClass/name field path");

    System.out.println("✓ Monitor contention: " + result.jfrPathQuery());
    System.out.println("  Explanation: " + result.explanation());
    System.out.println("  Confidence: " + result.confidence());
  }

  @Test
  void testTopThreadsByMemory() throws Exception {
    // This query should continue to work
    String naturalQuery = "which threads allocated the most memory";

    TranslationResult result = translator.translate(naturalQuery);

    assertNotNull(result.jfrPathQuery());
    assertDoesNotThrow(() -> JfrPathParser.parse(result.jfrPathQuery()));

    assertTrue(result.jfrPathQuery().contains("jdk.ObjectAllocationSample"));
    assertTrue(result.jfrPathQuery().contains("eventThread"));

    System.out.println("✓ Top threads by memory: " + result.jfrPathQuery());
    System.out.println("  Explanation: " + result.explanation());
    System.out.println("  Confidence: " + result.confidence());
  }

  @Test
  void testTopHottestMethods() throws Exception {
    // This query should continue to work
    String naturalQuery = "what are top 5 hottest methods";

    TranslationResult result = translator.translate(naturalQuery);

    assertNotNull(result.jfrPathQuery());
    assertDoesNotThrow(() -> JfrPathParser.parse(result.jfrPathQuery()));

    assertTrue(result.jfrPathQuery().contains("jdk.ExecutionSample"));
    assertTrue(result.jfrPathQuery().contains("stackTrace/frames/0"));

    System.out.println("✓ Top hottest methods: " + result.jfrPathQuery());
    System.out.println("  Explanation: " + result.explanation());
    System.out.println("  Confidence: " + result.confidence());
  }

  @Test
  void testFileReadsOver1MB() throws Exception {
    // This query should continue to work
    String naturalQuery = "show file reads over 1MB";

    TranslationResult result = translator.translate(naturalQuery);

    assertNotNull(result.jfrPathQuery());
    assertDoesNotThrow(() -> JfrPathParser.parse(result.jfrPathQuery()));

    assertTrue(result.jfrPathQuery().contains("jdk.FileRead"));
    assertTrue(result.jfrPathQuery().contains("bytes>1048576"));

    System.out.println("✓ File reads over 1MB: " + result.jfrPathQuery());
    System.out.println("  Explanation: " + result.explanation());
    System.out.println("  Confidence: " + result.confidence());
  }

  @Test
  void testGCCount() throws Exception {
    // This query should continue to work
    String naturalQuery = "count GC events";

    TranslationResult result = translator.translate(naturalQuery);

    assertNotNull(result.jfrPathQuery());
    assertDoesNotThrow(() -> JfrPathParser.parse(result.jfrPathQuery()));

    assertTrue(result.jfrPathQuery().contains("jdk.GarbageCollection"));
    assertTrue(result.jfrPathQuery().contains("count()"));

    System.out.println("✓ GC count: " + result.jfrPathQuery());
    System.out.println("  Explanation: " + result.explanation());
    System.out.println("  Confidence: " + result.confidence());
  }
}

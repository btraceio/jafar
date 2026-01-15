package io.jafar.shell.llm;

import static org.mockito.Mockito.*;

import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager.SessionRef;
import io.jafar.shell.llm.LLMConfig.PrivacyMode;
import io.jafar.shell.llm.LLMConfig.PrivacySettings;
import io.jafar.shell.llm.LLMConfig.ProviderType;
import io.jafar.shell.llm.providers.OllamaProvider;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Integration test for comparing LLM models with the new enhanced context.
 *
 * <p>This test is disabled by default as it requires Ollama to be running with specific models
 * installed. To run: 1. Start Ollama: `ollama serve` 2. Pull models: `ollama pull
 * adrienbrault/nous-hermes2pro:Q8_0` `ollama pull qwen2.5-coder:7b` 3. Run test with: `./gradlew
 * :jfr-shell:test --tests ModelComparisonIntegrationTest`
 */
@Disabled("Requires Ollama with specific models - enable manually for comparison")
class ModelComparisonIntegrationTest {

  private static final String[] MODELS = {
    "adrienbrault/nous-hermes2pro:Q8_0", // Hermes 2 Pro
    "qwen2.5:7b-instruct" // Qwen 2.5 Instruct
  };

  // Test cases: (natural language, expected query, description)
  private static final TestCase[] TEST_CASES = {
    new TestCase(
        "count GC events",
        "events/jdk.GarbageCollection | count()",
        "Simple count query - baseline"),
    new TestCase(
        "average GC pause time",
        "events/jdk.GarbageCollection | stats(duration)",
        "Stats query - tests aggregation"),
    new TestCase(
        "GC events longer than 100ms",
        "events/jdk.GarbageCollection[duration>100000000]",
        "Duration filter - tests unit conversion (100ms = 100000000ns)"),
    new TestCase(
        "file reads over 1MB",
        "events/jdk.FileRead[bytesRead>1048576]",
        "Size filter - tests field name (bytesRead) and unit conversion (1MB = 1048576 bytes)"),
    new TestCase(
        "top 10 hottest methods",
        "events/jdk.ExecutionSample | groupBy(stackTrace/frames/0/method/type/name, agg=count) | top(10, by=count)",
        "TopN query - tests stacktrace navigation"),
    new TestCase(
        "network reads larger than 1KB",
        "events/jdk.SocketRead[bytesRead>1024]",
        "Network I/O - tests correct event type (SocketRead not NetworkRead) and field (bytesRead)"),
    new TestCase(
        "CPU time by thread",
        "events/jdk.ExecutionSample | groupBy(sampledThread/javaName, agg=count)",
        "Thread field - tests sampledThread (not eventThread) for ExecutionSample"),
    new TestCase(
        "allocations over 10MB by class",
        "events/jdk.ObjectAllocationInNewTLAB[allocationSize>10485760] | groupBy(objectClass/name)",
        "Memory allocation - tests allocationSize field and unit conversion"),
  };

  @Test
  void compareModels() throws Exception {
    System.out.println("\n========================================");
    System.out.println("MODEL COMPARISON WITH NEW CONTEXT");
    System.out.println("========================================\n");

    Map<String, ModelResults> allResults = new HashMap<>();

    for (String model : MODELS) {
      System.out.println("\n>>> Testing model: " + model);
      ModelResults results = testModel(model);
      allResults.put(model, results);
    }

    // Print comparison summary
    System.out.println("\n========================================");
    System.out.println("COMPARISON SUMMARY");
    System.out.println("========================================\n");

    for (String model : MODELS) {
      ModelResults results = allResults.get(model);
      System.out.printf(
          "%s:\n  Perfect: %d/%d (%.1f%%)\n  Partial: %d/%d (%.1f%%)\n  Failed: %d/%d (%.1f%%)\n  Weighted Accuracy: %.1f%%\n\n",
          model,
          results.perfect,
          TEST_CASES.length,
          (results.perfect * 100.0 / TEST_CASES.length),
          results.partial,
          TEST_CASES.length,
          (results.partial * 100.0 / TEST_CASES.length),
          results.failed,
          TEST_CASES.length,
          (results.failed * 100.0 / TEST_CASES.length),
          results.weightedAccuracy * 100);
    }

    // Print detailed failures
    System.out.println("\n========================================");
    System.out.println("DETAILED FAILURES");
    System.out.println("========================================\n");

    for (String model : MODELS) {
      ModelResults results = allResults.get(model);
      if (!results.failures.isEmpty()) {
        System.out.println(">>> " + model + ":");
        for (String failure : results.failures) {
          System.out.println(failure);
        }
        System.out.println();
      }
    }
  }

  private ModelResults testModel(String modelName) throws Exception {
    LLMConfig config = createConfig(modelName);
    LLMProvider provider = new OllamaProvider(config);
    SessionRef sessionRef = createMockSession();
    ContextBuilder contextBuilder = new ContextBuilder(sessionRef, config);
    ConversationHistory history = new ConversationHistory();
    QueryTranslator translator = new QueryTranslator(provider, contextBuilder, history, sessionRef);

    ModelResults results = new ModelResults();

    for (int i = 0; i < TEST_CASES.length; i++) {
      TestCase testCase = TEST_CASES[i];
      System.out.printf("  [%d/%d] %s... ", i + 1, TEST_CASES.length, testCase.description);

      try {
        TranslationResult result = translator.translate(testCase.naturalLanguage);

        if (result.hasQuery()) {
          String actualQuery = result.jfrPathQuery().trim();
          String expectedQuery = testCase.expectedQuery.trim();

          if (actualQuery.equals(expectedQuery)) {
            System.out.println("✓ PERFECT");
            results.perfect++;
          } else {
            // Check for partial match
            double similarity = calculateSimilarity(actualQuery, expectedQuery);
            if (similarity > 0.7) {
              System.out.printf("~ PARTIAL (%.0f%% similar)\n", similarity * 100);
              results.partial++;
              results.failures.add(
                  String.format(
                      "  PARTIAL: %s\n    Expected: %s\n    Got:      %s\n",
                      testCase.description, expectedQuery, actualQuery));
            } else {
              System.out.println("✗ FAILED");
              results.failed++;
              results.failures.add(
                  String.format(
                      "  FAILED: %s\n    Expected: %s\n    Got:      %s\n",
                      testCase.description, expectedQuery, actualQuery));
            }
          }
        } else {
          System.out.println("✗ FAILED (no query generated)");
          results.failed++;
          results.failures.add(
              String.format(
                  "  FAILED: %s\n    Expected: %s\n    Got:      (no query)\n",
                  testCase.description, testCase.expectedQuery));
        }
      } catch (Exception e) {
        System.out.println("✗ ERROR: " + e.getMessage());
        results.failed++;
        results.failures.add(
            String.format(
                "  ERROR: %s\n    Expected: %s\n    Error:    %s\n",
                testCase.description, testCase.expectedQuery, e.getMessage()));
      }
    }

    // Calculate weighted accuracy (perfect=1.0, partial=0.5, failed=0.0)
    results.weightedAccuracy =
        (results.perfect + results.partial * 0.5) / (double) TEST_CASES.length;

    return results;
  }

  private double calculateSimilarity(String s1, String s2) {
    // Simple similarity: count matching tokens
    Set<String> tokens1 = Set.of(s1.split("[\\[\\]|(),><=\\s]+"));
    Set<String> tokens2 = Set.of(s2.split("[\\[\\]|(),><=\\s]+"));

    int common = 0;
    for (String token : tokens1) {
      if (tokens2.contains(token)) {
        common++;
      }
    }

    return 2.0 * common / (tokens1.size() + tokens2.size());
  }

  private LLMConfig createConfig(String model) {
    return new LLMConfig(
        ProviderType.LOCAL,
        "http://localhost:11434",
        model,
        null,
        new PrivacySettings(PrivacyMode.LOCAL_ONLY, false, false, false, Set.of(), false),
        60,
        2000,
        0.1); // Low temperature for consistency
  }

  private SessionRef createMockSession() {
    JFRSession mockSession = mock(JFRSession.class);
    when(mockSession.getAvailableEventTypes())
        .thenReturn(
            Set.of(
                "jdk.ExecutionSample",
                "jdk.GarbageCollection",
                "jdk.ObjectAllocationSample",
                "jdk.ObjectAllocationInNewTLAB",
                "jdk.FileRead",
                "jdk.FileWrite",
                "jdk.SocketRead",
                "jdk.SocketWrite",
                "jdk.JavaExceptionThrow",
                "jdk.ThreadPark",
                "jdk.VirtualThreadPinned"));
    when(mockSession.getRecordingPath()).thenReturn(Path.of("test-recording.jfr"));
    when(mockSession.getEventTypeCounts()).thenReturn(java.util.Collections.emptyMap());

    return new SessionRef(1, "test", mockSession);
  }

  private static class TestCase {
    final String naturalLanguage;
    final String expectedQuery;
    final String description;

    TestCase(String naturalLanguage, String expectedQuery, String description) {
      this.naturalLanguage = naturalLanguage;
      this.expectedQuery = expectedQuery;
      this.description = description;
    }
  }

  private static class ModelResults {
    int perfect = 0;
    int partial = 0;
    int failed = 0;
    double weightedAccuracy = 0.0;
    List<String> failures = new ArrayList<>();
  }
}

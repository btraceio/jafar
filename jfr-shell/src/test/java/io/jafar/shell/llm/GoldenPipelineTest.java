package io.jafar.shell.llm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager.SessionRef;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Golden test cases for pipeline accuracy testing.
 *
 * <p>These tests verify that the pipeline produces correct queries for a curated set of natural
 * language inputs. Disabled by default - requires Ollama with specific models.
 */
@Disabled("Requires Ollama with specific models - enable manually for golden testing")
class GoldenPipelineTest {

  private QueryPipeline pipeline;

  @BeforeEach
  void setUp() {
    // Setup mock session
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

    SessionRef sessionRef = new SessionRef(1, "test", mockSession);

    // Create config for testing
    LLMConfig config =
        new LLMConfig(
            LLMConfig.ProviderType.LOCAL,
            "http://localhost:11434",
            "qwen2.5:7b-instruct",
            null,
            LLMConfig.PrivacySettings.defaults(),
            60,
            2000,
            0.1);

    LLMProvider provider = LLMProvider.create(config);
    pipeline = new QueryPipeline(provider, sessionRef, config);
  }

  static GoldenCase[] goldenCases() {
    return new GoldenCase[] {
      new GoldenCase(
          "count GC events",
          "events/jdk.GarbageCollection | count()",
          QueryCategory.SIMPLE_COUNT,
          "Simple count query - baseline"),
      new GoldenCase(
          "GC events longer than 100ms",
          "events/jdk.GarbageCollection[duration>100000000]",
          QueryCategory.TIME_RANGE_FILTER,
          "Duration filter - tests unit conversion (100ms = 100000000ns)"),
      new GoldenCase(
          "network reads larger than 1KB",
          "events/jdk.SocketRead[bytesRead>1024]",
          QueryCategory.SIMPLE_FILTER,
          "Network I/O - tests correct event type (SocketRead) and field (bytesRead)"),
      new GoldenCase(
          "top 10 hottest methods",
          "events/jdk.ExecutionSample | groupBy(stackTrace/frames/0/method/type/name, agg=count) | top(10, by=count)",
          QueryCategory.TOPN_RANKING,
          "TopN query - tests stacktrace navigation"),
      new GoldenCase(
          "average GC pause time",
          "events/jdk.GarbageCollection | stats(duration)",
          QueryCategory.STATISTICS,
          "Stats query - tests aggregation"),
      new GoldenCase(
          "allocations by class",
          "events/jdk.ObjectAllocationInNewTLAB | groupBy(objectClass/name, agg=sum, value=allocationSize)",
          QueryCategory.GROUPBY_AGGREGATED,
          "Grouped aggregation - tests field navigation and aggregation"),
      new GoldenCase(
          "file reads over 1MB",
          "events/jdk.FileRead[bytesRead>1048576]",
          QueryCategory.SIMPLE_FILTER,
          "Size filter - tests field name (bytesRead) and unit conversion (1MB = 1048576 bytes)"),
      new GoldenCase(
          "CPU time by thread",
          "events/jdk.ExecutionSample | groupBy(sampledThread/javaName, agg=count)",
          QueryCategory.GROUPBY_AGGREGATED,
          "Thread field - tests sampledThread (not eventThread) for ExecutionSample"),
    };
  }

  @ParameterizedTest
  @MethodSource("goldenCases")
  void testGoldenCase(GoldenCase testCase) throws LLMException {
    System.out.println("\n=== Testing: " + testCase.description + " ===");
    System.out.println("Input: " + testCase.naturalLanguage);
    System.out.println("Expected: " + testCase.expectedQuery);

    PipelineResult result =
        pipeline.generateJfrPath(testCase.naturalLanguage, new QueryPipeline.PipelineContext());

    // Should not need clarification for these clear queries
    assertFalse(
        result.needsClarification(),
        "Golden cases should not need clarification: " + testCase.description);

    assertTrue(result.hasQuery(), "Should generate a query: " + testCase.description);

    String actualQuery = result.query().trim();
    System.out.println("Actual: " + actualQuery);
    System.out.println("Confidence: " + result.confidence());

    // Check for exact match or high similarity
    if (actualQuery.equals(testCase.expectedQuery.trim())) {
      System.out.println("✓ PERFECT MATCH");
    } else {
      // Calculate similarity
      double similarity = calculateSimilarity(actualQuery, testCase.expectedQuery);
      System.out.println("Similarity: " + (similarity * 100) + "%");

      // Assert at least 70% similarity for partial credit
      assertTrue(
          similarity > 0.7,
          String.format(
              "Query similarity too low for: %s\nExpected: %s\nActual: %s\nSimilarity: %.1f%%",
              testCase.description, testCase.expectedQuery, actualQuery, similarity * 100));

      if (similarity > 0.9) {
        System.out.println("~ VERY CLOSE");
      } else {
        System.out.println("~ PARTIAL MATCH");
      }
    }
  }

  /**
   * Calculate token-based similarity between two queries.
   *
   * @param s1 first query
   * @param s2 second query
   * @return similarity score 0.0-1.0
   */
  private double calculateSimilarity(String s1, String s2) {
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

  /** Golden test case record. */
  record GoldenCase(
      String naturalLanguage, String expectedQuery, QueryCategory category, String description) {
    @Override
    public String toString() {
      return description;
    }
  }
}

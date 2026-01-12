package io.jafar.shell.llm.providers;

import io.jafar.shell.llm.LLMConfig;
import io.jafar.shell.llm.LLMException;
import io.jafar.shell.llm.LLMProvider;
import java.util.HashMap;
import java.util.Map;

/**
 * Mock LLM provider for testing. Returns deterministic responses based on pre-configured patterns.
 * No network calls are made.
 */
public class MockProvider extends LLMProvider {

  private final Map<String, String> responses;

  /**
   * Creates a mock provider with the given configuration.
   *
   * @param config provider configuration
   */
  public MockProvider(LLMConfig config) {
    super(config);
    this.responses = createDefaultResponses();
  }

  /**
   * Creates a mock provider with custom responses.
   *
   * @param config provider configuration
   * @param responses map of query patterns to responses
   */
  public MockProvider(LLMConfig config, Map<String, String> responses) {
    super(config);
    this.responses = new HashMap<>(responses);
  }

  @Override
  public LLMResponse complete(LLMRequest request) throws LLMException {
    long startTime = System.currentTimeMillis();

    // Extract the user message
    String userMessage =
        request.messages().stream()
            .filter(m -> m.role() == Role.USER)
            .map(Message::content)
            .findFirst()
            .orElse("");

    // Find matching response
    String response = findMatchingResponse(userMessage);

    long duration = System.currentTimeMillis() - startTime;
    int tokens = response.length() / 4; // Rough approximation

    return new LLMResponse(response, "mock-model", tokens, duration);
  }

  @Override
  public boolean isAvailable() {
    return true;
  }

  @Override
  public String getModelName() {
    return "mock-model";
  }

  /**
   * Finds a response that matches the user message.
   *
   * @param userMessage the user's message
   * @return matching response or default response
   */
  private String findMatchingResponse(String userMessage) {
    String lower = userMessage.toLowerCase();

    // Check for exact or partial matches
    for (Map.Entry<String, String> entry : responses.entrySet()) {
      if (lower.contains(entry.getKey().toLowerCase())) {
        return entry.getValue();
      }
    }

    // Default response
    return "{\"query\": \"events/jdk.ExecutionSample | count()\", \"explanation\": \"Mock query for testing\", \"confidence\": 0.9}";
  }

  /**
   * Creates default responses for common query patterns.
   *
   * @return map of patterns to responses
   */
  private static Map<String, String> createDefaultResponses() {
    Map<String, String> responses = new HashMap<>();

    responses.put(
        "file read",
        "{\"query\": \"events/jdk.FileRead[bytes>1048576]\", \"explanation\": \"Filters file read events to those larger than 1MB\", \"confidence\": 0.95}");

    responses.put(
        "memory",
        "{\"query\": \"events/jdk.ObjectAllocationSample | groupBy(eventThread/javaName, agg=sum, value=bytes) | top(10, by=sum)\", \"explanation\": \"Groups allocation events by thread and sums bytes\", \"confidence\": 0.95}");

    responses.put(
        "threads allocated",
        "{\"query\": \"events/jdk.ObjectAllocationSample | groupBy(eventThread/javaName, agg=sum, value=bytes) | top(10, by=sum)\", \"explanation\": \"Shows top 10 threads by memory allocation\", \"confidence\": 0.95}");

    responses.put(
        "gc",
        "{\"query\": \"events/jdk.GarbageCollection | count()\", \"explanation\": \"Counts total garbage collection events\", \"confidence\": 0.98}");

    responses.put(
        "cpu",
        "{\"query\": \"events/jdk.ExecutionSample/stackTrace/frames/0/method/type/name | groupBy(value) | top(10, by=count)\", \"explanation\": \"Shows top 10 methods by CPU samples\", \"confidence\": 0.92}");

    responses.put(
        "hottest",
        "{\"query\": \"events/jdk.ExecutionSample/stackTrace/frames/0/method/type/name | groupBy(value) | top(10, by=count)\", \"explanation\": \"Shows top methods by execution samples\", \"confidence\": 0.92}");

    return responses;
  }
}

package io.jafar.shell.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Retrieves most relevant examples for a query category using keyword scoring. Uses Jaccard
 * similarity (keyword overlap) to select the top N examples that best match the user's query.
 */
public class ExampleRetriever {

  private final Map<QueryCategory, List<Example>> examplesByCategory;
  private final int maxExamples;

  /**
   * Creates an example retriever with the specified maximum number of examples.
   *
   * @param maxExamples maximum number of examples to retrieve per query
   */
  public ExampleRetriever(int maxExamples) {
    this.maxExamples = maxExamples;
    this.examplesByCategory = loadExamples();
  }

  /**
   * Retrieve top N examples for a category based on keyword overlap with query.
   *
   * @param category query category
   * @param userQuery user's natural language query
   * @return list of most relevant examples (up to maxExamples)
   */
  public List<Example> retrieve(QueryCategory category, String userQuery) {
    List<Example> categoryExamples = examplesByCategory.get(category);
    if (categoryExamples == null || categoryExamples.isEmpty()) {
      return List.of();
    }

    // Score each example by keyword overlap
    List<ScoredExample> scored =
        categoryExamples.stream()
            .map(ex -> new ScoredExample(ex, scoreExample(ex, userQuery)))
            .sorted((a, b) -> Double.compare(b.score, a.score))
            .limit(maxExamples)
            .toList();

    return scored.stream().map(se -> se.example).toList();
  }

  /**
   * Calculate Jaccard similarity between example and query using keyword overlap.
   *
   * @param example example to score
   * @param userQuery user query
   * @return similarity score 0.0-1.0
   */
  private double scoreExample(Example example, String userQuery) {
    Set<String> queryTokens = tokenize(userQuery.toLowerCase());
    Set<String> exampleTokens = tokenize(example.question().toLowerCase());

    // Calculate Jaccard similarity: |intersection| / |union|
    Set<String> intersection = new HashSet<>(queryTokens);
    intersection.retainAll(exampleTokens);

    Set<String> union = new HashSet<>(queryTokens);
    union.addAll(exampleTokens);

    return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
  }

  /**
   * Tokenize text into set of keywords (filters short words and punctuation).
   *
   * @param text text to tokenize
   * @return set of tokens
   */
  private Set<String> tokenize(String text) {
    // Remove punctuation and split on whitespace
    return Arrays.stream(text.replaceAll("[^a-z0-9\\s]", " ").split("\\s+"))
        .filter(token -> token.length() > 2) // Remove short words
        .collect(Collectors.toSet());
  }

  /**
   * Load examples from resource files for all categories.
   *
   * @return map of category to examples
   */
  private Map<QueryCategory, List<Example>> loadExamples() {
    Map<QueryCategory, List<Example>> map = new EnumMap<>(QueryCategory.class);

    for (QueryCategory category : QueryCategory.values()) {
      if (category == QueryCategory.CONVERSATIONAL) {
        continue; // No examples for conversational queries
      }

      String path = category.getExamplesPath();
      if (path != null) {
        List<Example> examples = loadExamplesFromFile(path);
        if (!examples.isEmpty()) {
          map.put(category, examples);
        }
      }
    }

    return map;
  }

  /**
   * Load examples from a resource file in Q&A format.
   *
   * @param resourcePath path to resource file
   * @return list of examples
   */
  private List<Example> loadExamplesFromFile(String resourcePath) {
    List<Example> examples = new ArrayList<>();

    try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
      if (is == null) {
        // Resource not found - return empty list
        return examples;
      }

      try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
        String line;
        String currentQuestion = null;
        StringBuilder currentAnswer = new StringBuilder();

        while ((line = reader.readLine()) != null) {
          if (line.startsWith("Q:")) {
            // Save previous example
            if (currentQuestion != null) {
              examples.add(new Example(currentQuestion, currentAnswer.toString().trim()));
            }
            currentQuestion = line.substring(2).trim();
            currentAnswer.setLength(0);
          } else if (line.startsWith("A:")) {
            currentAnswer.append(line.substring(2).trim());
          } else if (!line.isBlank() && currentAnswer.length() > 0) {
            currentAnswer.append(" ").append(line.trim());
          }
        }

        // Save last example
        if (currentQuestion != null) {
          examples.add(new Example(currentQuestion, currentAnswer.toString().trim()));
        }
      }

    } catch (IOException e) {
      // Return empty list on error
    }

    return examples;
  }

  /** Example record containing question and answer. */
  public record Example(String question, String answer) {}

  /** Scored example for internal sorting. */
  private record ScoredExample(Example example, double score) {}
}

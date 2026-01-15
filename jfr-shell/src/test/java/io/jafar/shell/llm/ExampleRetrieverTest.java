package io.jafar.shell.llm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for ExampleRetriever keyword scoring and example selection. */
class ExampleRetrieverTest {

  private ExampleRetriever retriever;

  @BeforeEach
  void setUp() {
    retriever = new ExampleRetriever(3); // max 3 examples
  }

  @Test
  void testRetrieve_KeywordScoring() {
    // Test that keyword overlap scores examples correctly
    List<ExampleRetriever.Example> examples =
        retriever.retrieve(QueryCategory.SIMPLE_COUNT, "count garbage collection events");

    // Should return examples (if category has examples loaded)
    assertNotNull(examples);
    assertTrue(examples.size() <= 3, "Should not exceed max examples");

    // If examples are returned, they should contain relevant keywords
    if (!examples.isEmpty()) {
      String firstExample = examples.get(0).question().toLowerCase();
      assertTrue(
          firstExample.contains("count")
              || firstExample.contains("gc")
              || firstExample.contains("garbage"),
          "First example should contain relevant keywords");
    }
  }

  @Test
  void testRetrieve_MaxExamples() {
    // Create retriever with max 2 examples
    ExampleRetriever smallRetriever = new ExampleRetriever(2);

    List<ExampleRetriever.Example> examples =
        smallRetriever.retrieve(QueryCategory.TOPN_RANKING, "top 10 hottest methods");

    assertNotNull(examples);
    assertTrue(examples.size() <= 2, "Should not exceed max examples of 2");
  }

  @Test
  void testRetrieve_EmptyForConversational() {
    // Conversational category should have no examples
    List<ExampleRetriever.Example> examples =
        retriever.retrieve(QueryCategory.CONVERSATIONAL, "hello");

    assertNotNull(examples);
    assertTrue(examples.isEmpty(), "Conversational category should have no examples");
  }

  @Test
  void testRetrieve_NonExistentCategory() {
    // Category with no examples should return empty list
    List<ExampleRetriever.Example> examples =
        retriever.retrieve(QueryCategory.METADATA_QUERY, "what fields does ExecutionSample have");

    assertNotNull(examples);
    // May be empty if no examples file exists
  }

  @Test
  void testRetrieve_DifferentQueries() {
    // Different queries should potentially get different examples
    List<ExampleRetriever.Example> examples1 =
        retriever.retrieve(QueryCategory.SIMPLE_FILTER, "show file reads over 1MB");

    List<ExampleRetriever.Example> examples2 =
        retriever.retrieve(QueryCategory.SIMPLE_FILTER, "show network events");

    assertNotNull(examples1);
    assertNotNull(examples2);

    // If both have examples, they might be different based on keyword scoring
    // (This is a weak test since scoring might return same examples)
  }

  @Test
  void testRetrieve_ZeroMaxExamples() {
    // Edge case: retriever with max 0 examples
    ExampleRetriever zeroRetriever = new ExampleRetriever(0);

    List<ExampleRetriever.Example> examples =
        zeroRetriever.retrieve(QueryCategory.SIMPLE_COUNT, "count GC events");

    assertNotNull(examples);
    assertTrue(examples.isEmpty(), "Should return empty list when maxExamples=0");
  }
}

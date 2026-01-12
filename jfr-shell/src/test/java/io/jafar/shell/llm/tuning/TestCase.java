package io.jafar.shell.llm.tuning;

import java.util.List;

/**
 * Represents a single test case for LLM prompt tuning. Each test case consists of a natural
 * language query and its expected JfrPath translation.
 */
public record TestCase(
    String id,
    String naturalLanguage,
    String expectedQuery,
    String category,
    Difficulty difficulty,
    List<String> keywords) {

  public enum Difficulty {
    SIMPLE,
    MEDIUM,
    COMPLEX
  }
}

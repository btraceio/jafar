package io.jafar.shell.cli.completion.property.models;

import io.jafar.shell.cli.completion.CompletionContextType;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents the expected completion behavior for a given query position.
 *
 * <p>Used by validators to check whether the actual completion context and candidates match what
 * should be produced for a particular expression and cursor position.
 */
public final class ExpectedCompletion {
  private final CompletionContextType contextType;
  private final String eventType;
  private final List<String> fieldPath;
  private final String functionName;
  private final Integer minCandidateCount;
  private final Integer maxCandidateCount;

  private ExpectedCompletion(Builder builder) {
    this.contextType = builder.contextType;
    this.eventType = builder.eventType;
    this.fieldPath =
        builder.fieldPath != null
            ? Collections.unmodifiableList(builder.fieldPath)
            : Collections.emptyList();
    this.functionName = builder.functionName;
    this.minCandidateCount = builder.minCandidateCount;
    this.maxCandidateCount = builder.maxCandidateCount;
  }

  public CompletionContextType contextType() {
    return contextType;
  }

  public String eventType() {
    return eventType;
  }

  public List<String> fieldPath() {
    return fieldPath;
  }

  public String functionName() {
    return functionName;
  }

  public Integer minCandidateCount() {
    return minCandidateCount;
  }

  public Integer maxCandidateCount() {
    return maxCandidateCount;
  }

  /**
   * Creates a new builder for ExpectedCompletion.
   *
   * @param contextType the expected context type
   * @return a new builder
   */
  public static Builder builder(CompletionContextType contextType) {
    return new Builder(contextType);
  }

  public static class Builder {
    private final CompletionContextType contextType;
    private String eventType;
    private List<String> fieldPath;
    private String functionName;
    private Integer minCandidateCount;
    private Integer maxCandidateCount;

    private Builder(CompletionContextType contextType) {
      this.contextType = Objects.requireNonNull(contextType);
    }

    public Builder eventType(String eventType) {
      this.eventType = eventType;
      return this;
    }

    public Builder fieldPath(List<String> fieldPath) {
      this.fieldPath = fieldPath;
      return this;
    }

    public Builder functionName(String functionName) {
      this.functionName = functionName;
      return this;
    }

    public Builder minCandidateCount(int minCandidateCount) {
      this.minCandidateCount = minCandidateCount;
      return this;
    }

    public Builder maxCandidateCount(int maxCandidateCount) {
      this.maxCandidateCount = maxCandidateCount;
      return this;
    }

    public Builder candidateCount(int count) {
      this.minCandidateCount = count;
      this.maxCandidateCount = count;
      return this;
    }

    public ExpectedCompletion build() {
      return new ExpectedCompletion(this);
    }
  }
}

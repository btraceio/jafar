package io.jafar.shell.cli.completion.property.mutation;

import io.jafar.shell.cli.completion.MetadataService;
import io.jafar.shell.cli.completion.property.generators.JfrPathExpressionGenerator;
import io.jafar.shell.cli.completion.property.mutation.MutationOperators.MutationResult;
import io.jafar.shell.cli.completion.property.mutation.MutationOperators.MutationType;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;

/**
 * jqwik Arbitrary provider for generating mutated JfrPath expressions.
 *
 * <p>This generator creates test cases by: 1. Generating valid JfrPath expressions 2. Applying one
 * or more mutations 3. Positioning the cursor at strategic locations relative to the mutation
 */
public class MutationGenerator {

  private final MetadataService metadataService;
  private final JfrPathExpressionGenerator expressionGenerator;
  private final MutationOperators operators;

  public MutationGenerator(MetadataService metadataService) {
    this.metadataService = metadataService;
    this.expressionGenerator = new JfrPathExpressionGenerator(metadataService);
    this.operators = new MutationOperators();
  }

  /** A mutated query with cursor position and mutation history. */
  public record MutatedQuery(
      String originalExpression,
      String mutatedExpression,
      int cursorPosition,
      List<MutationResult> mutations,
      CursorStrategy cursorStrategy) {

    public String getFullLine() {
      return "show " + mutatedExpression;
    }

    public int getCursorInFullLine() {
      return 5 + cursorPosition; // "show " prefix
    }

    public String describe() {
      StringBuilder sb = new StringBuilder();
      sb.append("Original: ").append(originalExpression).append("\n");
      sb.append("Mutated:  ").append(mutatedExpression).append("\n");
      sb.append("Cursor:   ")
          .append(cursorPosition)
          .append(" (")
          .append(cursorStrategy)
          .append(")\n");
      sb.append("Mutations:\n");
      for (MutationResult mutation : mutations) {
        sb.append("  - ")
            .append(mutation.type())
            .append(": ")
            .append(mutation.description())
            .append("\n");
      }
      return sb.toString();
    }
  }

  /** Strategies for positioning the cursor in mutated expressions. */
  public enum CursorStrategy {
    AT_MUTATION_SITE, // Cursor at the mutation point
    AFTER_MUTATION, // Cursor after the mutated text
    AT_END, // Cursor at end of expression
    RANDOM, // Random position
    AT_STRUCTURAL_BOUNDARY // At nearest structural boundary
  }

  // ==================== Main Generators ====================

  /** Generates mutated queries with single mutations. */
  public Arbitrary<MutatedQuery> singleMutationQueries() {
    return expressionGenerator.validJfrPathExpression().flatMap(expr -> applySingleMutation(expr));
  }

  /** Generates mutated queries with multiple mutations (1-3). */
  public Arbitrary<MutatedQuery> multipleMutationQueries() {
    return expressionGenerator
        .validJfrPathExpression()
        .flatMap(
            expr ->
                Arbitraries.integers()
                    .between(1, 3)
                    .flatMap(count -> applyMultipleMutations(expr, count)));
  }

  /** Generates queries targeting filter completion issues. */
  public Arbitrary<MutatedQuery> filterMutationQueries() {
    return expressionGenerator.filteredExpression().flatMap(this::applyFilterMutation);
  }

  /** Generates queries targeting pipeline completion issues. */
  public Arbitrary<MutatedQuery> pipelineMutationQueries() {
    return expressionGenerator
        .validJfrPathExpression()
        .filter(expr -> expr.contains("|") || !expr.contains("["))
        .flatMap(this::applyPipelineMutation);
  }

  /** Generates queries targeting nested path completion issues. */
  public Arbitrary<MutatedQuery> nestedPathMutationQueries() {
    return expressionGenerator
        .simpleExpression()
        .filter(expr -> expr.startsWith("events/") && expr.indexOf('/', 7) > 0)
        .flatMap(this::applyNestedPathMutation);
  }

  /** Generates queries with all types of targeted mutations. */
  public Arbitrary<MutatedQuery> allTargetedMutationQueries() {
    return Arbitraries.oneOf(
        filterMutationQueries(),
        filterMutationQueries(), // 2x weight - most problematic
        pipelineMutationQueries(),
        pipelineMutationQueries(), // 2x weight
        nestedPathMutationQueries(),
        nestedPathMutationQueries(), // 2x weight
        singleMutationQueries(),
        multipleMutationQueries());
  }

  // ==================== Mutation Application ====================

  private Arbitrary<MutatedQuery> applySingleMutation(String originalExpr) {
    return Arbitraries.integers()
        .between(0, 11)
        .flatMap(
            mutationType -> {
              MutationResult result =
                  switch (mutationType) {
                    case 0 -> operators.insertRandomCharacter(originalExpr);
                    case 1 -> operators.deleteRandomCharacter(originalExpr);
                    case 2 -> operators.swapRandomCharacters(originalExpr);
                    case 3 -> operators.replaceRandomOperator(originalExpr);
                    case 4 -> operators.insertRandomWhitespace(originalExpr);
                    case 5 -> operators.removeRandomWhitespace(originalExpr);
                    case 6 -> operators.duplicateDelimiter(originalExpr);
                    case 7 -> operators.truncateAtBoundary(originalExpr);
                    case 8 -> operators.createIncompleteBracket(originalExpr);
                    case 9 -> operators.createIncompleteParen(originalExpr);
                    default -> MutationResult.unchanged(originalExpr);
                  };

              return cursorStrategyArbitrary()
                  .map(
                      strategy -> {
                        int cursor = determineCursorPosition(result, strategy);
                        return new MutatedQuery(
                            originalExpr,
                            result.mutatedExpression(),
                            cursor,
                            List.of(result),
                            strategy);
                      });
            });
  }

  private Arbitrary<MutatedQuery> applyMultipleMutations(String originalExpr, int count) {
    List<MutationResult> mutations = new ArrayList<>();
    String current = originalExpr;

    for (int i = 0; i < count; i++) {
      MutationResult result = operators.applyRandomMutation(current);
      if (result.type() != MutationType.NONE) {
        mutations.add(result);
        current = result.mutatedExpression();
      }
    }

    String finalExpr = current;
    List<MutationResult> finalMutations = List.copyOf(mutations);

    return cursorStrategyArbitrary()
        .map(
            strategy -> {
              int cursor =
                  finalMutations.isEmpty()
                      ? finalExpr.length()
                      : determineCursorPosition(
                          finalMutations.get(finalMutations.size() - 1), strategy);
              cursor = Math.min(cursor, finalExpr.length());
              return new MutatedQuery(originalExpr, finalExpr, cursor, finalMutations, strategy);
            });
  }

  private Arbitrary<MutatedQuery> applyFilterMutation(String originalExpr) {
    List<MutationResult> filterMutations = operators.createFilterMutations(originalExpr);

    if (filterMutations.isEmpty()) {
      return Arbitraries.just(
          new MutatedQuery(
              originalExpr, originalExpr, originalExpr.length(), List.of(), CursorStrategy.AT_END));
    }

    return Arbitraries.of(filterMutations)
        .flatMap(
            result ->
                filterCursorStrategyArbitrary()
                    .map(
                        strategy -> {
                          int cursor = determineCursorPosition(result, strategy);
                          return new MutatedQuery(
                              originalExpr,
                              result.mutatedExpression(),
                              cursor,
                              List.of(result),
                              strategy);
                        }));
  }

  private Arbitrary<MutatedQuery> applyPipelineMutation(String originalExpr) {
    List<MutationResult> pipelineMutations = operators.createPipelineMutations(originalExpr);

    if (pipelineMutations.isEmpty()) {
      return Arbitraries.just(
          new MutatedQuery(
              originalExpr, originalExpr, originalExpr.length(), List.of(), CursorStrategy.AT_END));
    }

    return Arbitraries.of(pipelineMutations)
        .flatMap(
            result ->
                pipelineCursorStrategyArbitrary()
                    .map(
                        strategy -> {
                          int cursor = determineCursorPosition(result, strategy);
                          return new MutatedQuery(
                              originalExpr,
                              result.mutatedExpression(),
                              cursor,
                              List.of(result),
                              strategy);
                        }));
  }

  private Arbitrary<MutatedQuery> applyNestedPathMutation(String originalExpr) {
    List<MutationResult> pathMutations = operators.createNestedPathMutations(originalExpr);

    if (pathMutations.isEmpty()) {
      return Arbitraries.just(
          new MutatedQuery(
              originalExpr, originalExpr, originalExpr.length(), List.of(), CursorStrategy.AT_END));
    }

    return Arbitraries.of(pathMutations)
        .flatMap(
            result ->
                pathCursorStrategyArbitrary()
                    .map(
                        strategy -> {
                          int cursor = determineCursorPosition(result, strategy);
                          return new MutatedQuery(
                              originalExpr,
                              result.mutatedExpression(),
                              cursor,
                              List.of(result),
                              strategy);
                        }));
  }

  // ==================== Cursor Position Strategies ====================

  private Arbitrary<CursorStrategy> cursorStrategyArbitrary() {
    return Arbitraries.of(CursorStrategy.values());
  }

  private Arbitrary<CursorStrategy> filterCursorStrategyArbitrary() {
    // Bias towards positions inside filter brackets
    return Arbitraries.of(
        CursorStrategy.AT_MUTATION_SITE,
        CursorStrategy.AT_MUTATION_SITE,
        CursorStrategy.AFTER_MUTATION,
        CursorStrategy.AT_END,
        CursorStrategy.AT_STRUCTURAL_BOUNDARY);
  }

  private Arbitrary<CursorStrategy> pipelineCursorStrategyArbitrary() {
    // Bias towards positions after pipe
    return Arbitraries.of(
        CursorStrategy.AT_MUTATION_SITE,
        CursorStrategy.AFTER_MUTATION,
        CursorStrategy.AFTER_MUTATION,
        CursorStrategy.AT_END,
        CursorStrategy.AT_STRUCTURAL_BOUNDARY);
  }

  private Arbitrary<CursorStrategy> pathCursorStrategyArbitrary() {
    // Bias towards positions at path boundaries
    return Arbitraries.of(
        CursorStrategy.AT_MUTATION_SITE,
        CursorStrategy.AT_STRUCTURAL_BOUNDARY,
        CursorStrategy.AT_STRUCTURAL_BOUNDARY,
        CursorStrategy.AFTER_MUTATION,
        CursorStrategy.AT_END);
  }

  private int determineCursorPosition(MutationResult result, CursorStrategy strategy) {
    String expr = result.mutatedExpression();

    return switch (strategy) {
      case AT_MUTATION_SITE -> Math.max(0, result.suggestedCursorPosition() - 1);
      case AFTER_MUTATION -> result.suggestedCursorPosition();
      case AT_END -> expr.length();
      case RANDOM -> expr.isEmpty() ? 0 : new Random().nextInt(expr.length() + 1);
      case AT_STRUCTURAL_BOUNDARY -> findNearestBoundary(expr, result.suggestedCursorPosition());
    };
  }

  private int findNearestBoundary(String expr, int hint) {
    if (expr.isEmpty()) return 0;

    char[] boundaries = {'/', '|', '[', ']', '(', ')', ',', ' '};
    int nearestBefore = -1;
    int nearestAfter = expr.length();

    for (int i = 0; i < expr.length(); i++) {
      char c = expr.charAt(i);
      for (char b : boundaries) {
        if (c == b) {
          if (i <= hint && i > nearestBefore) {
            nearestBefore = i;
          }
          if (i >= hint && i < nearestAfter) {
            nearestAfter = i;
          }
          break;
        }
      }
    }

    // Return the closest boundary
    if (nearestBefore == -1) return Math.min(nearestAfter + 1, expr.length());
    if (nearestAfter == expr.length()) return nearestBefore + 1;

    return (hint - nearestBefore <= nearestAfter - hint) ? nearestBefore + 1 : nearestAfter + 1;
  }
}

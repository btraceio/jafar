# Property-Based Completion Testing

## Overview

This package contains a comprehensive property-based testing framework for the jfr-shell code completion system. Using [jqwik](https://jqwik.net/), the framework automatically generates thousands of JfrPath expressions with strategic cursor positions to discover missing completion scenarios and validate completion behavior across all syntax variations.

## Quick Start

### Running All Property Tests

```bash
# Run all property-based tests (default: 1000 tries per property)
./gradlew :jfr-shell:test --tests "*Property*"

# Run with verbose output
./gradlew :jfr-shell:test --tests "*Property*" --info

# Run specific test class
./gradlew :jfr-shell:test --tests "PropertyBasedCompletionTests"
```

### Running Specific Test Groups

```bash
# Test filter completion only
./gradlew :jfr-shell:test --tests "*filter*"

# Test pipeline completion only
./gradlew :jfr-shell:test --tests "*pipeline*"

# Test edge cases only
./gradlew :jfr-shell:test --tests "*EdgeCase*"
```

### Configuring Test Parameters

```bash
# Run with more tries for deeper testing
./gradlew :jfr-shell:test --tests "*Property*" -Djqwik.tries.default=10000

# Run with specific seed for reproducibility
./gradlew :jfr-shell:test --tests "*Property*" -Djqwik.seed=42

# Disable shrinking for faster execution
./gradlew :jfr-shell:test --tests "*Property*" -Djqwik.shrinking.mode=OFF
```

## Architecture

### Component Structure

```
property/
├── PropertyBasedCompletionTests.java    # Main test class with 30+ property tests
├── generators/
│   ├── JfrPathComponentGenerators.java  # Atomic generators (roots, operators, functions)
│   ├── JfrPathExpressionGenerator.java  # Composite expression builder
│   ├── CursorPositionStrategy.java      # Strategic cursor placement
│   └── ArbitraryProviders.java          # jqwik @Provide convenience methods
├── validators/
│   ├── CompletionContextValidator.java  # Context type validation
│   ├── CandidateValidator.java          # Candidate appropriateness validation
│   └── CompletionInvariants.java        # Universal completion invariants
└── models/
    ├── GeneratedQuery.java              # Query + cursor + metadata
    ├── ExpectedCompletion.java          # Expected context + candidates
    ├── CompletionScenario.java          # Input + expected + actual
    ├── CursorPosition.java              # Position + type metadata
    └── ValidationResult.java            # Errors + warnings accumulator
```

### Generation Strategy

The framework uses **bottom-up composition** to generate valid JfrPath expressions:

1. **Atomic Generators** → Individual components
   - `roots()` → "events", "metadata", "cp", "chunks"
   - `eventTypes()` → Real types from test-ap.jfr metadata
   - `fieldName()` → Real fields for event types
   - `comparisonOperator()` → "==", "!=", ">", etc.
   - `aggregationFunction()` → "count", "sum", "groupBy", etc.

2. **Structural Generators** → Valid syntax structures
   - `simpleFilter()` → [field op value]
   - `complexFilter()` → [field op value && field op value]
   - `nestedFieldPath()` → /field/nested/path (max depth)

3. **Expression Generators** → Complete JfrPath queries
   - `eventsExpression()` → events/Type[filter]/field
   - `metadataExpression()` → metadata/Type/fields
   - `pipelineExpression()` → expr | function(params)

4. **Cursor Strategy** → Meaningful positions
   - Token boundaries (start/end)
   - Structural positions (after /, [, |, ()
   - Middle of identifiers (optional)
   - Between tokens

5. **Validation** → Three-layer checking
   - **Structural**: Never crash, always return valid objects
   - **Semantic**: Context type matches query structure
   - **Candidate**: Suggestions are appropriate for context

## Test Coverage

### Universal Invariant Tests (5 tests)
- **completionNeverThrows**: Robustness with arbitrary input
- **completionReturnsNonNullList**: Non-null guarantee
- **noDuplicateCandidates**: No duplicate suggestions
- **candidatesHaveNonEmptyValues**: All candidates non-empty
- **contextTypeIsDeterministic**: Deterministic context analysis

### Context-Specific Tests (6 tests)
- **rootCompletionSuggestsAllRoots**: All roots suggested at start
- **simpleExpressionsProduceValidCompletions**: Simple paths validated
- **candidatesMatchPartialInput**: Partial matching verified
- **invalidExpressionsDontCrash**: Error recovery tested
- **reasonableCandidateCounts**: Candidate count sanity checks
- **allInvariantsHold**: All invariants checked together

### Filter Completion Tests (4 tests)
- **filterFieldCompletionSuggestsValidFields**: Fields in [
- **filterOperatorCompletionAfterFieldName**: Operators after field
- **filterLogicalCompletionAfterCondition**: && || ] after condition
- **nestedFieldPathsInFiltersHandled**: Nested paths in filters

### Pipeline Completion Tests (3 tests)
- **pipelineOperatorCompletionSuggestsAllFunctions**: All functions after |
- **functionParameterCompletionSuggestsFields**: Fields in function(
- **transformOperatorsAvailableAfterPipe**: Transform functions available

### Decorator Completion Tests (3 tests)
- **decorateByTimeHasCorrectSignature**: decorateByTime signature
- **decorateByKeyParametersValid**: decorateByKey parameters
- **decoratedFieldsAccessible**: $decorator.* field access

### Edge Case Tests (9 tests)
- **veryLongPathsHandledGracefully**: Deep paths (5-10 levels)
- **cursorInMiddleOfTokenHandled**: Mid-token cursor
- **emptyAndWhitespaceHandled**: Whitespace-only input
- **metadataCompletionSuggestsTypes**: metadata/ suggestions
- **cpCompletionSuggestsTypes**: cp/ suggestions
- **chunkIdCompletionSuggestsValidIds**: chunks/ suggestions
- **fieldPathWithSlashesParsedCorrectly**: Trailing slash handling
- **specialCharactersInFieldNamesDontCrash**: Special chars ($, _, ., -)
- **candidatesMatchContext**: Metadata-based validation

**Total: 30 comprehensive property tests**

## Understanding Test Failures

When a property test fails, jqwik automatically **shrinks** the failing input to the minimal example that reproduces the failure:

```
Timestamp = 2025-01-02T..., Seed = 1234567890
PropertyBasedCompletionTests:allInvariantsHold = FAILED
  Original Sample: ["events/jdk.ExecutionSample/stackTrace/frames[lineNumber>0]/method | groupBy(name)"]
  Shrunk Sample:   ["events/jdk.ExecutionSample[lineNumber>0]"]
```

The shrunk sample shows the simplest expression that triggers the failure, making debugging much faster.

### Common Failure Patterns

1. **Context Type Mismatch**: Expected ROOT but got FIELD_PATH
   - **Cause**: Token-based parsing not matching query structure
   - **Fix**: Update CompletionContextAnalyzer tokenization logic

2. **Invalid Candidates**: Field 'foo' not found for jdk.ExecutionSample
   - **Cause**: Completer suggesting fields from wrong event type
   - **Fix**: Update field path extraction in completer

3. **Duplicate Candidates**: Duplicate candidate 'count()'
   - **Cause**: Multiple completers adding same suggestion
   - **Fix**: Add deduplication or fix completer registration

## Adding New Property Tests

### Step 1: Add Generator (if needed)

```java
// In JfrPathComponentGenerators.java
public static Arbitrary<String> myNewComponent() {
    return Arbitraries.of("value1", "value2", "value3");
}
```

### Step 2: Add Expression Generator (if needed)

```java
// In JfrPathExpressionGenerator.java
private Arbitrary<String> myNewExpression() {
    return Combinators.combine(
        components.eventTypes(metadata),
        components.myNewComponent()
    ).as((type, comp) -> "events/" + type + "/" + comp);
}
```

### Step 3: Add @Provide Method (if needed)

```java
// In PropertyBasedCompletionTests.java
@Provide
Arbitrary<GeneratedQuery> myNewQueries() {
    return expressionGenerator
        .myNewExpression()
        .flatMap(expr -> {
            List<CursorPosition> positions = cursorStrategy.generatePositions(expr);
            return Arbitraries.of(positions)
                .map(pos -> new GeneratedQuery(
                    expr, pos.position(), pos.type(), metadataService));
        });
}
```

### Step 4: Add Property Test

```java
// In PropertyBasedCompletionTests.java
@Property(tries = 500)
void myNewPropertyTest(@ForAll("myNewQueries") GeneratedQuery query) {
    List<Candidate> candidates = invokeCompletion(query);
    CompletionContext context = analyzeContext(query);

    // Add assertions
    assertTrue(candidates.size() > 0, "Should suggest candidates");

    // Use validators
    ValidationResult result = candidateValidator.validateForContext(context, candidates);
    assertTrue(result.isValid(), result.getReport());
}
```

## Design Principles

### 1. Use Real Metadata
- Generators use actual JFR file (test-ap.jfr) for event types and fields
- Ensures generated expressions are realistic
- Validators can check against actual metadata

### 2. Compositional Generation
- Build complex expressions from simpler parts
- Natural shrinking to minimal failing cases
- Reusable generators for consistency

### 3. Strategic Cursor Placement
- Focus on meaningful positions (token boundaries, structural chars)
- Skip arbitrary mid-whitespace positions
- Prioritize completion-triggering locations

### 4. Three-Layer Validation
- **Structural**: Never crash, always return valid objects
- **Semantic**: Context type matches query structure
- **Candidate**: Suggestions are appropriate for context

### 5. Deterministic Shrinking
- jqwik automatically reduces failing examples
- Remove optional filters/pipelines first
- Reduce nested depth
- Simplify expressions while preserving failure

## Performance

### Default Configuration
- 1000 tries per property test
- Full shrinking enabled
- Total execution time: ~30-60 seconds for all tests

### Fast Execution (CI)
```bash
# Reduce tries for faster feedback
./gradlew :jfr-shell:test --tests "*Property*" -Djqwik.tries.default=100

# Disable shrinking for speed
./gradlew :jfr-shell:test --tests "*Property*" -Djqwik.shrinking.mode=OFF
```

### Deep Testing (Local)
```bash
# Maximum coverage
./gradlew :jfr-shell:test --tests "*Property*" -Djqwik.tries.default=10000
```

## Troubleshooting

### Test Hangs or Takes Very Long
- **Cause**: Complex expression generation or shrinking stuck
- **Solution**: Reduce tries or disable shrinking temporarily

### "No such element" in Generator
- **Cause**: Empty event type or field list from metadata
- **Solution**: Check test-ap.jfr file exists and has data

### "Context type mismatch" Failures
- **Cause**: CompletionContextAnalyzer and CompletionContextValidator disagreeing
- **Solution**: Review token-based parsing logic in both classes

### Flaky Tests (Pass/Fail Randomly)
- **Cause**: Non-deterministic completion behavior
- **Solution**: Check for race conditions or timing dependencies in completers

## References

- **jqwik Documentation**: https://jqwik.net/docs/current/user-guide.html
- **Property-Based Testing**: https://en.wikipedia.org/wiki/Property_testing
- **JfrPath Grammar**: `/doc/jfrpath.md` in project root
- **Completion System**: `/jfr-shell/src/main/java/io/jafar/shell/cli/completion/`

## Contributing

When adding new completion features:

1. **Update Generators**: Add new syntax to JfrPathExpressionGenerator
2. **Update Validators**: Add validation rules if needed
3. **Add Property Tests**: Create targeted tests for new scenarios
4. **Verify Coverage**: Ensure new CompletionContextType values are tested

The property-based testing framework will automatically discover edge cases and validate your completion implementation across thousands of scenarios.

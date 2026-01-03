/**
 * Model classes for representing generated queries, expected results, and validation outcomes.
 *
 * <h2>Overview</h2>
 *
 * <p>This package provides data structures used throughout the property-based testing framework to
 * represent test inputs, expected results, and validation outcomes. All model classes are immutable
 * and designed for clarity and ease of debugging.
 *
 * <h2>Key Classes</h2>
 *
 * <ul>
 *   <li>{@link io.jafar.shell.cli.completion.property.models.GeneratedQuery}: Represents a
 *       generated JfrPath expression with cursor position and metadata
 *   <li>{@link io.jafar.shell.cli.completion.property.models.ExpectedCompletion}: Represents the
 *       expected completion context and candidates for a query
 *   <li>{@link io.jafar.shell.cli.completion.property.models.CompletionScenario}: Bundles together
 *       input query, expected results, and actual results for comparison
 *   <li>{@link io.jafar.shell.cli.completion.property.models.CursorPosition}: Represents a cursor
 *       position within an expression with type metadata
 *   <li>{@link io.jafar.shell.cli.completion.property.models.ValidationResult}: Accumulates
 *       validation errors and warnings with detailed messages
 * </ul>
 *
 * <h2>GeneratedQuery</h2>
 *
 * <p>The {@link io.jafar.shell.cli.completion.property.models.GeneratedQuery} is the primary input
 * to property tests. It bundles:
 *
 * <ul>
 *   <li>JfrPath expression (without "show " prefix)
 *   <li>Cursor position (character offset in expression)
 *   <li>Position type (TOKEN_START, TOKEN_END, STRUCTURAL, etc.)
 *   <li>Metadata service for validation
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * // Create query at end of expression
 * GeneratedQuery query = GeneratedQuery.atEnd("events/jdk.ExecutionSample", metadata);
 *
 * // Create query at start
 * GeneratedQuery query = GeneratedQuery.atStart("", metadata);
 *
 * // Create query at specific position
 * GeneratedQuery query = GeneratedQuery.atPosition("events/jdk.ExecutionSample", 6, metadata);
 *
 * // Get full line for completion
 * String fullLine = query.getFullLine(); // "show events/jdk.ExecutionSample"
 * int cursor = query.getCursorInFullLine(); // Adjusted for "show " prefix
 * }</pre>
 *
 * <h2>ExpectedCompletion</h2>
 *
 * <p>The {@link io.jafar.shell.cli.completion.property.models.ExpectedCompletion} represents what
 * completion context and candidates should be returned for a query. Built using builder pattern:
 *
 * <pre>{@code
 * ExpectedCompletion expected = ExpectedCompletion.builder(CompletionContextType.FIELD_PATH)
 *     .eventType("jdk.ExecutionSample")
 *     .fieldPath(List.of("sampledThread"))
 *     .build();
 * }</pre>
 *
 * <h2>CompletionScenario</h2>
 *
 * <p>The {@link io.jafar.shell.cli.completion.property.models.CompletionScenario} bundles input,
 * expected, and actual results for comprehensive validation:
 *
 * <pre>{@code
 * CompletionScenario scenario = new CompletionScenario(
 *     generatedQuery,
 *     expectedCompletion,
 *     actualContext,
 *     actualCandidates
 * );
 *
 * // Compare and validate
 * if (!scenario.contextMatches()) {
 *     System.err.println("Context mismatch: " + scenario.describeContextMismatch());
 * }
 * }</pre>
 *
 * <h2>CursorPosition</h2>
 *
 * <p>The {@link io.jafar.shell.cli.completion.property.models.CursorPosition} represents a
 * meaningful cursor position for completion testing:
 *
 * <ul>
 *   <li><b>TOKEN_START</b>: At the start of a token (e.g., before "events")
 *   <li><b>TOKEN_END</b>: At the end of a token (e.g., after "events")
 *   <li><b>TOKEN_MIDDLE</b>: In the middle of a token (e.g., "eve▐nts")
 *   <li><b>BETWEEN_TOKENS</b>: Between tokens (e.g., "events ▐ jdk")
 *   <li><b>STRUCTURAL</b>: After structural character (e.g., "events/▐")
 * </ul>
 *
 * <h2>ValidationResult</h2>
 *
 * <p>The {@link io.jafar.shell.cli.completion.property.models.ValidationResult} accumulates
 * validation messages:
 *
 * <pre>{@code
 * ValidationResult result = new ValidationResult();
 *
 * // Add errors (serious violations)
 * result.addError("Context type mismatch: expected ROOT, got FIELD_PATH");
 *
 * // Add warnings (potential issues)
 * result.addWarning("Candidate 'foo' doesn't match partial input 'bar'");
 *
 * // Check validity (no errors)
 * if (!result.isValid()) {
 *     System.err.println(result.getReport());
 * }
 *
 * // Get statistics
 * System.out.println("Errors: " + result.getErrorCount());
 * System.out.println("Warnings: " + result.getWarningCount());
 * }</pre>
 *
 * <h2>Immutability</h2>
 *
 * <p>Most model classes are immutable (using records or final fields) to ensure thread safety and
 * prevent accidental modification during testing. The only mutable class is ValidationResult, which
 * accumulates messages during validation.
 *
 * <h2>Debugging Support</h2>
 *
 * <p>All model classes provide helpful debugging methods:
 *
 * <ul>
 *   <li>{@code GeneratedQuery.describe()}: Shows expression with cursor marker
 *   <li>{@code ValidationResult.getReport()}: Formats all errors and warnings
 *   <li>{@code ExpectedCompletion.toString()}: Shows expected context details
 * </ul>
 *
 * @see io.jafar.shell.cli.completion.property.PropertyBasedCompletionTests
 * @see io.jafar.shell.cli.completion.property.validators
 * @since 0.4.0
 */
package io.jafar.shell.cli.completion.property.models;

/**
 * Property-based testing framework for jfr-shell code completion using jqwik.
 *
 * <h2>Overview</h2>
 *
 * <p>This package provides comprehensive property-based testing for the jfr-shell completion system.
 * It automatically generates thousands of JfrPath expressions with strategic cursor positions to
 * discover missing completion scenarios and validate completion behavior across all syntax
 * variations.
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li><b>Generators</b> ({@link io.jafar.shell.cli.completion.property.generators}): Create valid
 *       and invalid JfrPath expressions using bottom-up composition
 *   <li><b>Validators</b> ({@link io.jafar.shell.cli.completion.property.validators}): Verify
 *       completion contexts and candidates are correct
 *   <li><b>Models</b> ({@link io.jafar.shell.cli.completion.property.models}): Data structures for
 *       representing generated queries and validation results
 *   <li><b>Tests</b> ({@link
 *       io.jafar.shell.cli.completion.property.PropertyBasedCompletionTests}): 30+ property tests
 *       covering all completion scenarios
 * </ul>
 *
 * <h2>Design Principles</h2>
 *
 * <ol>
 *   <li><b>Use Real Metadata</b>: Generators use actual JFR file for realistic event types and
 *       fields
 *   <li><b>Compositional Generation</b>: Build complex expressions from simpler atomic parts
 *   <li><b>Strategic Cursor Placement</b>: Focus on meaningful positions (token boundaries,
 *       structural chars)
 *   <li><b>Three-Layer Validation</b>: Structural (no crashes), Semantic (correct context),
 *       Candidate (appropriate suggestions)
 *   <li><b>Deterministic Shrinking</b>: jqwik automatically reduces failing examples to minimal
 *       cases
 * </ol>
 *
 * <h2>Test Coverage</h2>
 *
 * <p>The framework provides comprehensive coverage through 30+ property tests:
 *
 * <ul>
 *   <li><b>Universal Invariants</b> (5 tests): Rules that must hold for all completions
 *   <li><b>Context-Specific</b> (6 tests): Validation for different completion contexts
 *   <li><b>Filter Completion</b> (4 tests): Field, operator, and logical completions in filters
 *   <li><b>Pipeline Completion</b> (3 tests): Function suggestions and parameters
 *   <li><b>Decorator Completion</b> (3 tests): decorateByTime and decorateByKey validation
 *   <li><b>Edge Cases</b> (9 tests): Deep paths, special characters, whitespace handling
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * // Run all property tests
 * ./gradlew :jfr-shell:test --tests "*Property*"
 *
 * // Run with more tries for deeper testing
 * ./gradlew :jfr-shell:test --tests "*Property*" -Djqwik.tries.default=10000
 *
 * // Run specific test group
 * ./gradlew :jfr-shell:test --tests "*filter*"
 * }</pre>
 *
 * <h2>Adding New Tests</h2>
 *
 * <p>To add a new property test:
 *
 * <ol>
 *   <li>Add generator in {@link
 *       io.jafar.shell.cli.completion.property.generators.JfrPathExpressionGenerator}
 *   <li>Add validation in {@link
 *       io.jafar.shell.cli.completion.property.validators.CompletionContextValidator} if needed
 *   <li>Add property test in {@link
 *       io.jafar.shell.cli.completion.property.PropertyBasedCompletionTests}
 * </ol>
 *
 * <h2>Failure Analysis</h2>
 *
 * <p>When a property fails, jqwik automatically shrinks to the minimal failing example:
 *
 * <pre>{@code
 * Original: "events/jdk.ExecutionSample/stackTrace/frames[lineNumber>0]/method | groupBy(name)"
 * Shrunk:   "events/jdk.ExecutionSample[lineNumber>0]"
 * }</pre>
 *
 * <p>This shrinking makes debugging much faster by isolating the root cause.
 *
 * @see <a href="https://jqwik.net/">jqwik Documentation</a>
 * @see <a href="README.md">Property Testing README</a>
 * @since 0.4.0
 */
package io.jafar.shell.cli.completion.property;

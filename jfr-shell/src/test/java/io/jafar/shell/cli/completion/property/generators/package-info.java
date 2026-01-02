/**
 * Generators for creating JfrPath expressions and cursor positions for property-based testing.
 *
 * <h2>Overview</h2>
 *
 * <p>This package provides generators that create valid and invalid JfrPath expressions using
 * bottom-up composition. Generators use real JFR metadata to ensure realistic test scenarios.
 *
 * <h2>Key Classes</h2>
 *
 * <ul>
 *   <li>{@link io.jafar.shell.cli.completion.property.generators.JfrPathComponentGenerators}:
 *       Atomic generators for individual JfrPath components (roots, operators, functions, values)
 *   <li>{@link io.jafar.shell.cli.completion.property.generators.JfrPathExpressionGenerator}:
 *       Composite generator that builds complete JfrPath expressions by combining atomic generators
 *   <li>{@link io.jafar.shell.cli.completion.property.generators.CursorPositionStrategy}: Strategy
 *       for generating meaningful cursor positions within expressions (token boundaries, structural
 *       positions)
 *   <li>{@link io.jafar.shell.cli.completion.property.generators.ArbitraryProviders}: Convenience
 *       {@code @Provide} methods for common generator patterns
 * </ul>
 *
 * <h2>Generation Strategy</h2>
 *
 * <p>The generation process follows a bottom-up composition approach:
 *
 * <ol>
 *   <li><b>Atomic Components</b>: Individual syntax elements (roots, event types, field names)
 *   <li><b>Structural Elements</b>: Valid syntax structures (filters, nested paths)
 *   <li><b>Complete Expressions</b>: Full JfrPath queries combining all components
 *   <li><b>Cursor Positions</b>: Strategic positions for completion invocation
 * </ol>
 *
 * <h2>Example Usage</h2>
 *
 * <pre>{@code
 * // Create expression generator
 * JfrPathExpressionGenerator generator = new JfrPathExpressionGenerator(metadataService);
 *
 * // Generate valid expression
 * Arbitrary<String> validExpr = generator.validJfrPathExpression();
 *
 * // Generate expression with cursor position
 * CursorPositionStrategy cursorStrategy = new CursorPositionStrategy(true);
 * Arbitrary<GeneratedQuery> query = validExpr.flatMap(expr -> {
 *     List<CursorPosition> positions = cursorStrategy.generatePositions(expr);
 *     return Arbitraries.of(positions)
 *         .map(pos -> new GeneratedQuery(expr, pos.position(), pos.type(), metadata));
 * });
 * }</pre>
 *
 * <h2>Design Principles</h2>
 *
 * <ul>
 *   <li><b>Real Metadata</b>: Uses actual JFR event types and field names from test files
 *   <li><b>Weighted Distribution</b>: Common patterns generated more frequently than edge cases
 *   <li><b>Compositional</b>: Complex generators built from simpler ones for better shrinking
 *   <li><b>Strategic Cursors</b>: Positions chosen to trigger completion, not arbitrary locations
 * </ul>
 *
 * @see io.jafar.shell.cli.completion.property.PropertyBasedCompletionTests
 * @since 0.4.0
 */
package io.jafar.shell.cli.completion.property.generators;

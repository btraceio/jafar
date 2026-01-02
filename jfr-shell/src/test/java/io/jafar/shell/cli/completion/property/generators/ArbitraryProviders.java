package io.jafar.shell.cli.completion.property.generators;

import io.jafar.shell.cli.completion.MetadataService;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.providers.ArbitraryProvider;
import net.jqwik.api.providers.TypeUsage;
import java.util.List;
import java.util.Set;

/**
 * Convenience providers for common patterns in property tests.
 *
 * <p>These providers make it easy to use common generators without explicitly calling the generator
 * classes. They work with jqwik's @ForAll annotation.
 *
 * <p>Note: This class provides convenience methods but is not registered as an ArbitraryProvider
 * with jqwik. The actual @Provide methods should be defined in the test class itself, where they
 * have access to the static metadataService field.
 */
public class ArbitraryProviders {

  /**
   * Creates an arbitrary for JfrPath root types.
   *
   * @return arbitrary generating root types
   */
  public static Arbitrary<String> roots() {
    return JfrPathComponentGenerators.roots();
  }

  /**
   * Creates an arbitrary for event type names.
   *
   * @param metadata the metadata service
   * @return arbitrary generating event types
   */
  public static Arbitrary<String> eventTypes(MetadataService metadata) {
    return JfrPathComponentGenerators.eventTypes(metadata);
  }

  /**
   * Creates an arbitrary for metadata type names.
   *
   * @param metadata the metadata service
   * @return arbitrary generating metadata types
   */
  public static Arbitrary<String> metadataTypes(MetadataService metadata) {
    return JfrPathComponentGenerators.metadataTypes(metadata);
  }

  /**
   * Creates an arbitrary for comparison operators.
   *
   * @return arbitrary generating comparison operators
   */
  public static Arbitrary<String> comparisonOperators() {
    return JfrPathComponentGenerators.comparisonOperator();
  }

  /**
   * Creates an arbitrary for logical operators.
   *
   * @return arbitrary generating logical operators
   */
  public static Arbitrary<String> logicalOperators() {
    return JfrPathComponentGenerators.logicalOperator();
  }

  /**
   * Creates an arbitrary for aggregation function names.
   *
   * @return arbitrary generating aggregation functions
   */
  public static Arbitrary<String> aggregationFunctions() {
    return JfrPathComponentGenerators.aggregationFunction();
  }

  /**
   * Creates an arbitrary for transform function names.
   *
   * @return arbitrary generating transform functions
   */
  public static Arbitrary<String> transformFunctions() {
    return JfrPathComponentGenerators.transformFunction();
  }

  /**
   * Creates an arbitrary for decorator function names.
   *
   * @return arbitrary generating decorator functions
   */
  public static Arbitrary<String> decoratorFunctions() {
    return JfrPathComponentGenerators.decoratorFunction();
  }

  /**
   * Creates an arbitrary for any pipeline function name.
   *
   * @return arbitrary generating pipeline functions
   */
  public static Arbitrary<String> pipelineFunctions() {
    return JfrPathComponentGenerators.pipelineFunction();
  }

  /**
   * Creates an arbitrary for string literals.
   *
   * @return arbitrary generating string literals
   */
  public static Arbitrary<String> stringLiterals() {
    return JfrPathComponentGenerators.stringLiteral();
  }

  /**
   * Creates an arbitrary for numeric literals.
   *
   * @return arbitrary generating numeric literals
   */
  public static Arbitrary<String> numericLiterals() {
    return JfrPathComponentGenerators.numericLiteral();
  }

  /**
   * Creates an arbitrary for any literal value.
   *
   * @return arbitrary generating any literal
   */
  public static Arbitrary<String> anyLiteral() {
    return JfrPathComponentGenerators.anyLiteral();
  }
}

package io.jafar.parser.api;

import java.util.Map;

/**
 * Represents a complex value in JFR data.
 *
 * <p>This interface provides access to complex structured data parsed from JFR recordings. Complex
 * values are typically represented as maps of field names to their values.
 */
public interface ComplexType {
  /**
   * Gets the value of this complex type as a map.
   *
   * <p>The map contains field names as keys and their corresponding values as values. Values can be
   * primitives, strings, or other complex types.
   *
   * <p>A constant-pool reference is resolved when this method is called, not when the event is
   * delivered. The result is {@code null} when the reference points at no entry (a null reference
   * in the recording, or a constant pool the chunk does not contain), and resolution of a corrupted
   * entry can throw.
   *
   * @return a map representing the complex value structure, or {@code null} if the reference
   *     resolves to nothing
   */
  Map<String, Object> getValue();

  /**
   * Whether this value is a reference to a JFR <em>simple type</em>: a type that exists only to
   * wrap one value (for example {@code jdk.types.ThreadState}, whose single field is {@code name}).
   * {@link #getValue()} still returns the one-entry wrapper map; this method is what lets a caller
   * tell such a wrapper from a genuine single-field struct, which {@code map.size() == 1} cannot.
   *
   * <p>See {@link Values#resolvedDeepCollapsed(Map)}.
   *
   * @return {@code true} if the referenced type is declared {@code simpleType="true"} and wraps
   *     exactly one field; {@code false} otherwise (the default)
   */
  default boolean isSimpleType() {
    return false;
  }
}

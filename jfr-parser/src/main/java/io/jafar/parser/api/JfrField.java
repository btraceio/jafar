package io.jafar.parser.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark methods that represent JFR fields.
 *
 * <p>This annotation is used to map Java methods to JFR field names and control how the field
 * values are processed during parsing.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface JfrField {
  /**
   * Original JFR field name when it differs from the method name.
   *
   * @return the JFR field name
   */
  String value();

  /**
   * If true, returns the raw underlying representation (e.g., id) instead of normalized value.
   *
   * @return true if raw values should be returned, false otherwise
   */
  boolean raw() default false;
}

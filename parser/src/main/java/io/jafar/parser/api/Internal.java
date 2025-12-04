package io.jafar.parser.api;

import static java.lang.annotation.ElementType.*;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks APIs that are internal implementation details and should not be used by external code.
 *
 * <p>Classes, methods, and fields marked with this annotation are subject to change without notice,
 * even in minor or patch releases. They may be removed, renamed, or have their behavior changed at
 * any time.
 *
 * <p><b>Do not depend on {@code @Internal} APIs in your code.</b> If you find yourself needing
 * functionality from an internal API, please file an issue requesting a public API for your use
 * case.
 *
 * <p>This annotation is used to mark:
 *
 * <ul>
 *   <li>Implementation details that leaked into the public API surface
 *   <li>Experimental features that may change significantly
 *   <li>APIs used only by internal modules
 * </ul>
 *
 * <p>Example:
 *
 * <pre>{@code
 * @Internal
 * public class InternalHelper {
 *   // Do not use this class - it may change or be removed
 * }
 * }</pre>
 *
 * @since 0.1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({TYPE, METHOD, FIELD, PACKAGE})
public @interface Internal {
  /**
   * Optional explanation of why this is internal and what public API should be used instead, if
   * any.
   *
   * @return description of the internal API and alternatives
   */
  String value() default "";
}

package io.jafar.parser.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark methods that should be ignored during JFR parsing.
 * <p>
 * Methods annotated with this annotation will not be processed as JFR fields
 * and will be excluded from the generated deserialization code.
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface JfrIgnore {
}

package io.jafar.parser.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface JfrField {
    /** Original JFR field name when it differs from the method name. */
    String value();
    /**
     * If true, returns the raw underlying representation (e.g., id) instead of normalized value.
     */
    boolean raw() default false;
}

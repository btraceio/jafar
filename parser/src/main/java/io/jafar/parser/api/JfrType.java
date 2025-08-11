package io.jafar.parser.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark interfaces that represent JFR types or events.
 * <p>
 * This annotation is used to specify the JFR type name that a Java interface
 * represents, enabling proper mapping between JFR data and Java objects.
 * </p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface JfrType {
    /**
     * Fully qualified JFR type or event name this interface represents.
     * 
     * @return the JFR type name
     */
    String value();
}

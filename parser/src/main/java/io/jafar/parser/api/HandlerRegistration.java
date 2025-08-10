package io.jafar.parser.api;

/**
 * Represents a registration for a handler, providing a method to destroy the registration.
 * @param <T> the type of the handler
 */
public interface HandlerRegistration<T> {
    void destroy(JafarParser cookie);
}

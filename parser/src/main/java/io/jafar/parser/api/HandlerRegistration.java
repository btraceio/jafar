package io.jafar.parser.api;

/**
 * Registration handle for a previously registered handler.
 * <p>
 * Implementations are idempotent; calling {@code destroy(...)} multiple times is safe.
 * </p>
 * <p>
 * The {@code cookie} must be the parser instance that created this registration.
 * This prevents accidental deregistration from a different parser.
 * </p>
 *
 * @param <T> the handled event type
 */
public interface HandlerRegistration<T> {
    /**
     * Deregisters the handler associated with this registration.
     *
     * @param cookie the parser that created this registration
     */
    void destroy(JafarParser cookie);
}

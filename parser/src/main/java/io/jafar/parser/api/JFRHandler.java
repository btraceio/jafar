package io.jafar.parser.api;

/**
 * Functional handler receiving deserialized typed JFR events.
 * <p>
 * Invoked synchronously on the parser thread; keep work minimal or offload.
 * </p>
 * 
 * @param <T> the type of JFR events this handler processes
 */
@FunctionalInterface
public interface JFRHandler<T> {
    /**
     * Implementation wrapper for JFRHandler that provides type safety.
     * <p>
     * This class wraps a JFRHandler with its corresponding Class object to enable
     * safe casting of events to the expected type.
     * </p>
     * 
     * @param <T> the type of JFR events this implementation handles
     */
    class Impl<T> {
        private final Class<T> clazz;
        private final JFRHandler<T> handler;

        /**
         * Constructs a new Impl with the specified class and handler.
         * 
         * @param clazz the Class object for the event type
         * @param handler the JFRHandler to delegate to
         */
        public Impl(Class<T> clazz, JFRHandler<T> handler) {
            this.clazz = clazz;
            this.handler = handler;
        }

        /**
         * Handles an event by casting it to the expected type and delegating to the handler.
         * 
         * @param event the event object to handle
         * @param ctl parser control utilities
         */
        public void handle(Object event, Control ctl) {
            handler.handle(clazz.cast(event), ctl);
        }
    }

    /**
     * Handles a single event.
     *
     * @param event deserialized event instance
     * @param ctl parser control utilities; {@link Control#stream()} exposes the current
     *            byte position while the handler is executing
     */
    void handle(T event, Control ctl);
}
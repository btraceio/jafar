package io.jafar.parser.api;

/**
 * Functional handler receiving deserialized typed JFR events.
 * <p>
 * Invoked synchronously on the parser thread; keep work minimal or offload.
 * </p>
 */
@FunctionalInterface
public interface JFRHandler<T> {
    class Impl<T> {
        private final Class<T> clazz;
        private final JFRHandler<T> handler;

        public Impl(Class<T> clazz, JFRHandler<T> handler) {
            this.clazz = clazz;
            this.handler = handler;
        }

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
package io.jafar.parser.api.lazy;

public interface HandlerRegistration<T> {
    void destroy(JafarParser cookie);
}

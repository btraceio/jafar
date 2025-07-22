package io.jafar.parser.api.lazy;

public interface Control {
    interface Stream {
        long position();
    }

    Stream stream();
}

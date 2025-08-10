package io.jafar.parser.api;

/**
 * Control interface represents the ability to control a stream of data.
 * It provides a method to retrieve a stream object that allows querying the position in the stream.
 */
public interface Control {
    /**
     * An interface representing a stream of data. It provides a method to query the current position in the stream.
     */
    interface Stream {
        /**
         * Returns the current position in the stream.
         *
         * @return the current position in the stream
         */
        long position();
    }

    /**
     * Retrieves a stream object that allows querying the position in the stream.
     *
     * @return the stream object
     */
    Stream stream();
}

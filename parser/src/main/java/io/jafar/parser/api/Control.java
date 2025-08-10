package io.jafar.parser.api;

/**
 * Control utilities available to handlers during parsing.
 * Provides access to the current stream position.
 */
public interface Control {
    /**
     * Represents the current recording stream while the handler executes.
     */
    interface Stream {
        /**
         * Returns the current byte position in the recording stream.
         * Meaningful only during handler invocation.
         *
         * @return the current position in the stream
         */
        long position();
    }

    /**
     * Retrieves the stream proxy that allows querying the current byte position.
     * The returned object may become invalid outside handler invocation.
     *
     * @return the stream object
     */
    Stream stream();
}

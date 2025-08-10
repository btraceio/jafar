package io.jafar.parser.api;

import java.io.IOException;
import java.nio.file.Path;

public interface JafarParser {
    static TypedJafarParser newTypedParser(Path path) {
        return TypedJafarParser.open(path.toString());
    }

    static TypedJafarParser newTypedParser(Path path, ParsingContext context) {
        return TypedJafarParser.open(path, context);
    }

    static UntypedJafarParser newUntypedParser(Path path) {
        return UntypedJafarParser.open(path.toString());
    }

    static UntypedJafarParser newUntypedParser(Path path, ParsingContext context) {
        return UntypedJafarParser.open(path, context);
    }

    void run() throws IOException;
}

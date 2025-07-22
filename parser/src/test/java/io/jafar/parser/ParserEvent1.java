package io.jafar.parser;

import io.jafar.parser.api.lazy.JfrType;

@JfrType("datadog.ParserEvent")
public interface ParserEvent1 {
    int value();
}

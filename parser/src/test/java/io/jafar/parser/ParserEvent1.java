package io.jafar.parser;

import io.jafar.parser.api.JfrType;

@JfrType("datadog.ParserEvent")
public interface ParserEvent1 {
    int value();
}

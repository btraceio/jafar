package io.jafar.parser.types;

import io.jafar.parser.api.JfrType;

@JfrType("jdk.types.ClassLoader")
public interface JFRClassLoader {
  JFRClass type();

  String name();
}

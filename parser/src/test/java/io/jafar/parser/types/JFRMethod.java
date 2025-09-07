package io.jafar.parser.types;

import io.jafar.parser.api.JfrType;

@JfrType("jdk.types.Method")
public interface JFRMethod {
  JFRClass type();

  String name();

  String descriptor();

  int modifiers();

  boolean hidden();
}

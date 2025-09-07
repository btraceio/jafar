package io.jafar.parser.types;

import io.jafar.parser.api.JfrType;

@JfrType("jdk.types.ThreadGroup")
public interface JFRThreadGroup {
  JFRThreadGroup parent();

  String name();
}

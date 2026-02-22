package io.jafar.parser.types;

import io.jafar.parser.api.JfrType;

@JfrType("jdk.types.Module")
public interface JFRModule {
  String name();

  String version();

  String location();

  JFRClassLoader classLoader();
}

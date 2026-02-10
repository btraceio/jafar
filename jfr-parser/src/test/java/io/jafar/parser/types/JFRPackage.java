package io.jafar.parser.types;

import io.jafar.parser.api.JfrType;

@JfrType("jdk.types.Package")
public interface JFRPackage {
  String name();

  JFRModule module();

  boolean exported();
}

package io.jafar.parser.benchmark.events;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;

/** Build-time generated handler for jdk.types.ClassLoader type. */
@JfrType("jdk.types.ClassLoader")
public interface JFRClassLoader {
  @JfrField("type")
  JFRClass type();

  @JfrField("name")
  String name();
}

package io.jafar.parser.benchmark.events;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;

/** Build-time generated handler for jdk.types.Method type. */
@JfrType("jdk.types.Method")
public interface JFRMethod {
  @JfrField("type")
  JFRClass type();

  @JfrField("name")
  String name();

  @JfrField("descriptor")
  String descriptor();

  @JfrField("modifiers")
  int modifiers();

  @JfrField("hidden")
  boolean hidden();
}

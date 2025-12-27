package io.jafar.parser.benchmark.events;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;

/** Build-time generated handler for java.lang.ThreadGroup type. */
@JfrType("java.lang.ThreadGroup")
public interface JFRThreadGroup {
  @JfrField("parent")
  JFRThreadGroup parent();

  @JfrField("name")
  String name();
}

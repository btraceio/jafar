package io.jafar.parser.benchmark.events;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;

/** Build-time generated handler for jdk.types.ThreadGroup, the type of java.lang.Thread.group. */
@JfrType("jdk.types.ThreadGroup")
public interface JFRThreadGroup {
  @JfrField("parent")
  JFRThreadGroup parent();

  @JfrField("name")
  String name();
}

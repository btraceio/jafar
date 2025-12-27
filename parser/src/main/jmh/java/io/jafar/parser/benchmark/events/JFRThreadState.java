package io.jafar.parser.benchmark.events;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;

/** Build-time generated handler for jdk.types.ThreadState type. */
@JfrType("jdk.types.ThreadState")
public interface JFRThreadState {
  @JfrField("name")
  String name();
}

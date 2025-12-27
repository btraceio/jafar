package io.jafar.parser.benchmark.events;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;

/** Build-time generated handler for jdk.types.StackTrace type. */
@JfrType("jdk.types.StackTrace")
public interface JFRStackTrace {
  @JfrField("truncated")
  boolean truncated();

  @JfrField("frames")
  JFRStackFrame[] frames();
}

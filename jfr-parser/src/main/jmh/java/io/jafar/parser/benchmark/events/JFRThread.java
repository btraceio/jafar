package io.jafar.parser.benchmark.events;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;

/** Build-time generated handler for java.lang.Thread type. */
@JfrType("java.lang.Thread")
public interface JFRThread {
  @JfrField("osName")
  String osName();

  @JfrField("osThreadId")
  long osThreadId();

  @JfrField("javaName")
  String javaName();

  @JfrField("javaThreadId")
  long javaThreadId();

  @JfrField("group")
  JFRThreadGroup group();
}

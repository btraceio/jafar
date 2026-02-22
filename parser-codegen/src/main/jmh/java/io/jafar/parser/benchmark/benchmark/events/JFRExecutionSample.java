package io.jafar.parser.benchmark.events;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;

/** Build-time generated handler for jdk.ExecutionSample events. */
@JfrType("jdk.ExecutionSample")
public interface JFRExecutionSample {
  @JfrField("startTime")
  long startTime();

  @JfrField("sampledThread")
  JFRThread sampledThread();

  @JfrField("stackTrace")
  JFRStackTrace stackTrace();

  @JfrField("state")
  JFRThreadState state();
}

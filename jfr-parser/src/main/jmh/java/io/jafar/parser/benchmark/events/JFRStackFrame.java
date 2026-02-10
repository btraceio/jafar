package io.jafar.parser.benchmark.events;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;

/** Build-time generated handler for jdk.types.StackFrame type. */
@JfrType("jdk.types.StackFrame")
public interface JFRStackFrame {
  @JfrField("method")
  JFRMethod method();

  @JfrField("lineNumber")
  int lineNumber();

  @JfrField("bytecodeIndex")
  int bytecodeIndex();

  @JfrField("type")
  String type();
}

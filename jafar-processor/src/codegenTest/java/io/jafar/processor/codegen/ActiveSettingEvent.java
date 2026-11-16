package io.jafar.processor.codegen;

import io.jafar.parser.api.JfrType;

/** {@code jdk.ActiveSetting}: present in both chunks of {@code tck-test.jfr}. */
@JfrType("jdk.ActiveSetting")
public interface ActiveSettingEvent {
  String name();

  ThreadRef eventThread();
}

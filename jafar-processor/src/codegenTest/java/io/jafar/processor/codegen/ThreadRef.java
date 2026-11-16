package io.jafar.processor.codegen;

import io.jafar.parser.api.JfrType;

/** {@code java.lang.Thread}, the type {@link ActiveSettingEvent#eventThread()} refers to. */
@JfrType("java.lang.Thread")
public interface ThreadRef {
  String javaName();
}

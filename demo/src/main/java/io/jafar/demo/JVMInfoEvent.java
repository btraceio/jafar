package io.jafar.demo;

import io.jafar.parser.api.JfrType;

@JfrType("jdk.JVMInformation")
public interface JVMInfoEvent {
  String jvmName();

  String jvmVersion();
}

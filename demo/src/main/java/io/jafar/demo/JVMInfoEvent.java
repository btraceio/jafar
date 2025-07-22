package io.jafar.demo;

import io.jafar.parser.api.lazy.JfrType;

@JfrType("jdk.JVMInformation")
public interface JVMInfoEvent {
    String jvmName();
    String jvmVersion();
}

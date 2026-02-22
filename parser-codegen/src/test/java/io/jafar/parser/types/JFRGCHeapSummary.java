package io.jafar.parser.types;

import io.jafar.parser.api.JfrType;

@JfrType("jdk.GCHeapSummary")
public interface JFRGCHeapSummary {
  long startTime();

  int gcId();

  String when();

  long heapUsed();
}

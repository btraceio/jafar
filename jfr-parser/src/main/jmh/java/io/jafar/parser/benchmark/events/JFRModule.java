package io.jafar.parser.benchmark.events;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;

/** Build-time generated handler for jdk.types.Module type. */
@JfrType("jdk.types.Module")
public interface JFRModule {
  @JfrField("name")
  String name();

  @JfrField("version")
  String version();

  @JfrField("location")
  String location();

  @JfrField("classLoader")
  JFRClassLoader classLoader();
}

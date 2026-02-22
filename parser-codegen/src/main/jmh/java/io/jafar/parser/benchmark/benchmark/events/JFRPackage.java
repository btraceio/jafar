package io.jafar.parser.benchmark.events;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;

/** Build-time generated handler for jdk.types.Package type. */
@JfrType("jdk.types.Package")
public interface JFRPackage {
  @JfrField("name")
  String name();

  @JfrField("module")
  JFRModule module();

  @JfrField("exported")
  boolean exported();
}

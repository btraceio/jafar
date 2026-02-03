package io.jafar.parser.benchmark.events;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;

/** Build-time generated handler for java.lang.Class type. */
@JfrType("java.lang.Class")
public interface JFRClass {
  @JfrField("classLoader")
  JFRClassLoader classLoader();

  @JfrField("name")
  String name();

  @JfrField("package")
  JFRPackage pkg();

  @JfrField("modifiers")
  int modifiers();
}

package io.jafar.parser;

import io.jafar.parser.api.JfrType;

/**
 * On the test class path, so CodeGenerator's loader has this copy; {@code TypedChildFirst8Test}
 * defines a second copy in a child-first loader. Top level: a nested copy would disagree with its
 * outer class's InnerClasses attribute.
 */
@JfrType("jdk.ExecutionSample")
public interface ChildFirstShared8 {
  long startTime();
}

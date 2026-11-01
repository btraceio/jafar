package io.jafar.parser.impl;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * {@link ArrayHolder#backingClass} restates, without allocating, which array class the {@code
 * (type, len)} constructor allocates; generated deserializers rely on it via {@code ofFilled}. Two
 * copies of one mapping must not drift apart.
 */
class ArrayHolderBackingClassTest {
  private static final String[] TYPES = {
    "short",
    "char",
    "int",
    "long",
    "byte",
    "boolean",
    "double",
    "float",
    "java.lang.String",
    "jdk.types.StackFrame"
  };

  @Test
  void backingClassMatchesWhatTheConstructorAllocates() {
    for (String type : TYPES) {
      assertSame(
          new ArrayHolder(type, 0).getArray().getClass(), ArrayHolder.backingClass(type), type);
    }
  }

  @Test
  void ofFilledRejectsTheWrongArrayClass() {
    // int elements live in a long[]; an int[] would break MapValueBuilder parity.
    assertThrows(IllegalArgumentException.class, () -> ArrayHolder.ofFilled("int", new int[1]));
    assertSame(long[].class, ArrayHolder.ofFilled("int", new long[1]).getArray().getClass());
  }
}

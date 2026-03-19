package io.jafar.hdump.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ClassNameUtilTest {

  @Test
  void primitiveDescriptors() {
    assertEquals("boolean", ClassNameUtil.toHumanReadable("Z"));
    assertEquals("byte", ClassNameUtil.toHumanReadable("B"));
    assertEquals("char", ClassNameUtil.toHumanReadable("C"));
    assertEquals("short", ClassNameUtil.toHumanReadable("S"));
    assertEquals("int", ClassNameUtil.toHumanReadable("I"));
    assertEquals("long", ClassNameUtil.toHumanReadable("J"));
    assertEquals("float", ClassNameUtil.toHumanReadable("F"));
    assertEquals("double", ClassNameUtil.toHumanReadable("D"));
  }

  @Test
  void primitiveArrays() {
    assertEquals("byte[]", ClassNameUtil.toHumanReadable("[B"));
    assertEquals("int[]", ClassNameUtil.toHumanReadable("[I"));
    assertEquals("long[]", ClassNameUtil.toHumanReadable("[J"));
    assertEquals("double[]", ClassNameUtil.toHumanReadable("[D"));
    assertEquals("boolean[]", ClassNameUtil.toHumanReadable("[Z"));
    assertEquals("char[]", ClassNameUtil.toHumanReadable("[C"));
  }

  @Test
  void multiDimensionalPrimitiveArrays() {
    assertEquals("byte[][]", ClassNameUtil.toHumanReadable("[[B"));
    assertEquals("int[][][]", ClassNameUtil.toHumanReadable("[[[I"));
  }

  @Test
  void objectArrayDescriptors() {
    assertEquals("java.lang.String[]", ClassNameUtil.toHumanReadable("[Ljava/lang/String;"));
    assertEquals("java.lang.Object[][]", ClassNameUtil.toHumanReadable("[[Ljava/lang/Object;"));
  }

  @Test
  void internalClassNames() {
    assertEquals("java.lang.String", ClassNameUtil.toHumanReadable("java/lang/String"));
    assertEquals("java.util.HashMap", ClassNameUtil.toHumanReadable("java/util/HashMap"));
  }

  @Test
  void alreadyQualifiedNames() {
    assertEquals("java.lang.String", ClassNameUtil.toHumanReadable("java.lang.String"));
    assertEquals("MyClass", ClassNameUtil.toHumanReadable("MyClass"));
  }

  @Test
  void nullAndEmpty() {
    assertNull(ClassNameUtil.toHumanReadable(null));
    assertEquals("", ClassNameUtil.toHumanReadable(""));
  }
}

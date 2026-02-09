package io.jafar.hdump.impl;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.hdump.api.HeapClass;
import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapDumpParser;
import io.jafar.hdump.util.ClassNameUtil;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests to ensure class name format consistency throughout the heap dump API.
 *
 * <p>Validates that:
 *
 * <ul>
 *   <li>API methods expect internal format (java/lang/String)
 *   <li>HeapClass.getName() returns internal format
 *   <li>Qualified format (java.lang.String) does NOT work for lookups
 * </ul>
 */
class ClassNameFormatTest {

  private static HeapDump dump;

  @BeforeAll
  static void setup() throws IOException {
    // Use a test heap dump - update path as needed for your test resources
    Path testFile = Paths.get("src/test/resources/test-dumps/small.hprof");
    if (!testFile.toFile().exists()) {
      // Skip if test file not available
      System.err.println("Test heap dump not found: " + testFile);
      System.err.println("Skipping ClassNameFormatTest");
      return;
    }
    dump = HeapDumpParser.parse(testFile);
  }

  @Test
  void testGetClassByName_requiresInternalFormat() {
    if (dump == null) {
      System.err.println("Skipping test - no test heap dump available");
      return;
    }

    // Verify that getClassByName expects internal format (slashes)
    var withSlashes = dump.getClassByName("java/lang/String");
    var withDots = dump.getClassByName("java.lang.String");

    assertTrue(withSlashes.isPresent(), "Should find class with internal format (java/lang/String)");
    assertFalse(
        withDots.isPresent(),
        "Should NOT find class with qualified format (java.lang.String)");
  }

  @Test
  void testHeapClass_getName_returnsInternalFormat() {
    if (dump == null) {
      System.err.println("Skipping test - no test heap dump available");
      return;
    }

    HeapClass stringClass =
        dump.getClassByName("java/lang/String")
            .orElseThrow(() -> new AssertionError("java/lang/String not found in heap dump"));

    String name = stringClass.getName();

    assertEquals("java/lang/String", name, "HeapClass.getName() should return internal format");
    assertTrue(name.contains("/"), "Class name should contain slashes (internal format)");
    assertFalse(name.contains("."), "Class name should NOT contain dots (not qualified format)");
  }

  @Test
  void testGetObjectsOfClass_requiresInternalFormat() {
    if (dump == null) {
      System.err.println("Skipping test - no test heap dump available");
      return;
    }

    // Count objects using internal format
    long countWithSlashes = dump.getObjectsOfClass("java/lang/String").count();

    // Count objects using qualified format (should be 0)
    long countWithDots = dump.getObjectsOfClass("java.lang.String").count();

    assertTrue(
        countWithSlashes > 0,
        "Should find String objects with internal format (java/lang/String)");
    assertEquals(
        0, countWithDots, "Should find 0 objects with qualified format (java.lang.String)");
  }

  @Test
  void testClassNameUtil_toInternal() {
    assertEquals("java/lang/String", ClassNameUtil.toInternal("java.lang.String"));
    assertEquals("java/util/HashMap", ClassNameUtil.toInternal("java.util.HashMap"));
    assertEquals("java/util/concurrent/ConcurrentHashMap", ClassNameUtil.toInternal("java.util.concurrent.ConcurrentHashMap"));
    assertNull(ClassNameUtil.toInternal(null));
  }

  @Test
  void testClassNameUtil_toQualified() {
    assertEquals("java.lang.String", ClassNameUtil.toQualified("java/lang/String"));
    assertEquals("java.util.HashMap", ClassNameUtil.toQualified("java/util/HashMap"));
    assertEquals("java.util.concurrent.ConcurrentHashMap", ClassNameUtil.toQualified("java/util/concurrent/ConcurrentHashMap"));
    assertNull(ClassNameUtil.toQualified(null));
  }

  @Test
  void testClassNameUtil_isInternal() {
    assertTrue(ClassNameUtil.isInternal("java/lang/String"));
    assertFalse(ClassNameUtil.isInternal("java.lang.String"));
    assertFalse(ClassNameUtil.isInternal(null));
  }

  @Test
  void testClassNameUtil_isQualified() {
    assertTrue(ClassNameUtil.isQualified("java.lang.String"));
    assertFalse(ClassNameUtil.isQualified("java/lang/String"));
    assertFalse(ClassNameUtil.isQualified(null));
  }
}

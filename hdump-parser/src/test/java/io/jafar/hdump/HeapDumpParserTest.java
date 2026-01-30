package io.jafar.hdump;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.hdump.api.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/** Integration test for HeapDumpParser using a real heap dump file. */
class HeapDumpParserTest {

  private static final Path TEST_HPROF = Path.of(System.getProperty("user.home"), "Downloads", "test.hprof");

  static boolean testFileExists() {
    return Files.exists(TEST_HPROF);
  }

  @Test
  @EnabledIf("testFileExists")
  void testParseRealHeapDump() throws Exception {
    System.out.println("Parsing heap dump: " + TEST_HPROF);
    long startTime = System.currentTimeMillis();

    try (HeapDump dump = HeapDumpParser.parse(TEST_HPROF)) {
      long parseTime = System.currentTimeMillis() - startTime;

      // Print basic statistics
      System.out.println("\n=== Heap Dump Statistics ===");
      System.out.println("Format: HPROF " + dump.getFormatVersion());
      System.out.println("ID size: " + dump.getIdSize() + " bytes");
      System.out.println("Parse time: " + parseTime + " ms");
      System.out.println("Classes: " + dump.getClassCount());
      System.out.println("Objects: " + dump.getObjectCount());
      System.out.println("GC Roots: " + dump.getGcRootCount());
      System.out.println("Total heap size: " + formatSize(dump.getTotalHeapSize()));

      // Verify we got reasonable data
      assertTrue(dump.getClassCount() > 0, "Should have at least one class");
      assertTrue(dump.getObjectCount() > 0, "Should have at least one object");

      // Print top classes by instance count
      System.out.println("\n=== Top 10 Classes by Instance Count ===");
      List<HeapClass> topClasses = dump.getClasses().stream()
          .filter(c -> c.getInstanceCount() > 0)
          .sorted(Comparator.comparingInt(HeapClass::getInstanceCount).reversed())
          .limit(10)
          .collect(Collectors.toList());

      for (HeapClass cls : topClasses) {
        System.out.printf("  %,8d  %s%n", cls.getInstanceCount(), cls.getName());
      }

      // Print GC root type distribution
      System.out.println("\n=== GC Root Distribution ===");
      dump.getGcRoots().stream()
          .collect(Collectors.groupingBy(GcRoot::getType, Collectors.counting()))
          .entrySet().stream()
          .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
          .forEach(e -> System.out.printf("  %,8d  %s%n", e.getValue(), e.getKey()));

      // Try to find and read some String objects
      System.out.println("\n=== Sample String Objects ===");
      dump.getClassByName("java.lang.String").ifPresent(stringClass -> {
        dump.getObjectsOfClass(stringClass)
            .limit(5)
            .forEach(obj -> {
              String value = obj.getStringValue();
              if (value != null && !value.isEmpty()) {
                String display = value.length() > 60 ? value.substring(0, 57) + "..." : value;
                display = display.replace("\n", "\\n").replace("\r", "\\r");
                System.out.printf("  [%d bytes] \"%s\"%n", obj.getShallowSize(), display);
              }
            });
      });

      // Verify we can access object fields
      System.out.println("\n=== Sample Object Field Access ===");
      dump.getClassByName("java.util.HashMap").ifPresent(hashMapClass -> {
        dump.getObjectsOfClass(hashMapClass)
            .limit(3)
            .forEach(obj -> {
              System.out.println("  HashMap@" + Long.toHexString(obj.getId()));
              obj.getFieldValues().forEach((name, value) -> {
                String valueStr = value == null ? "null" :
                    (value instanceof HeapObject ho) ? ho.getHeapClass().getSimpleName() + "@" + Long.toHexString(ho.getId()) :
                    value.toString();
                System.out.printf("    %s = %s%n", name, valueStr);
              });
            });
      });

      System.out.println("\n=== Test Passed ===");
    }
  }

  private static String formatSize(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
    if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
    return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
  }
}

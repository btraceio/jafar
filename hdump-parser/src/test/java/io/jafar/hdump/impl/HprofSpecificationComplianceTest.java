package io.jafar.hdump.impl;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.hdump.api.HeapClass;
import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapDumpParser;
import io.jafar.hdump.api.HeapDumpParser.ParserOptions;
import io.jafar.hdump.api.HeapDumpParser.ParsingMode;
import io.jafar.hdump.api.HeapObject;
import io.jafar.hdump.test.SyntheticHeapDumpGenerator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Systematic verification of HPROF parser compliance with OpenJDK specification.
 *
 * <p>Tests each parsing method against the official HPROF format defined in heapDumper.cpp. Uses
 * synthetic heap dumps to verify format correctness, edge case handling, and mode consistency.
 *
 * @see <a
 *     href="https://github.com/openjdk/jdk/blob/master/src/hotspot/share/services/heapDumper.cpp">heapDumper.cpp</a>
 */
class HprofSpecificationComplianceTest {

  @TempDir static Path tempDir;

  // Test heap dump paths
  private static Path a1Minimal;
  private static Path a2AllPrimitiveTypes;
  private static Path a3AllGcRoots;
  private static Path a4ComplexReferences;
  private static Path a5InstanceFields;
  private static Path a6StaticFields;
  private static Path a7ClassHierarchy;
  private static Path b1ZeroLengthArrays;
  private static Path b2NullReferences;
  private static Path b3CircularReferences;
  private static Path b4LargeArrays;

  @BeforeAll
  static void generateTestHeaps() throws IOException {
    // Generate Test Suite A: Format Coverage
    a1Minimal = tempDir.resolve("a1_minimal.hprof");
    SyntheticHeapDumpGenerator.generateA1Minimal(a1Minimal);

    a2AllPrimitiveTypes = tempDir.resolve("a2_all_primitive_types.hprof");
    SyntheticHeapDumpGenerator.generateA2AllPrimitiveTypes(a2AllPrimitiveTypes);

    a3AllGcRoots = tempDir.resolve("a3_all_gc_roots.hprof");
    SyntheticHeapDumpGenerator.generateA3AllGcRoots(a3AllGcRoots);

    a4ComplexReferences = tempDir.resolve("a4_complex_references.hprof");
    SyntheticHeapDumpGenerator.generateA4ComplexReferences(a4ComplexReferences);

    a5InstanceFields = tempDir.resolve("a5_instance_fields.hprof");
    SyntheticHeapDumpGenerator.generateA5InstanceFields(a5InstanceFields);

    a6StaticFields = tempDir.resolve("a6_static_fields.hprof");
    SyntheticHeapDumpGenerator.generateA6StaticFields(a6StaticFields);

    a7ClassHierarchy = tempDir.resolve("a7_class_hierarchy.hprof");
    SyntheticHeapDumpGenerator.generateA7ClassHierarchy(a7ClassHierarchy);

    // Generate Test Suite B: Edge Cases
    b1ZeroLengthArrays = tempDir.resolve("b1_zero_length_arrays.hprof");
    SyntheticHeapDumpGenerator.generateB1ZeroLengthArrays(b1ZeroLengthArrays);

    b2NullReferences = tempDir.resolve("b2_null_references.hprof");
    SyntheticHeapDumpGenerator.generateB2NullReferences(b2NullReferences);

    b3CircularReferences = tempDir.resolve("b3_circular_references.hprof");
    SyntheticHeapDumpGenerator.generateB3CircularReferences(b3CircularReferences);

    b4LargeArrays = tempDir.resolve("b4_large_arrays.hprof");
    SyntheticHeapDumpGenerator.generateB4LargeArrays(b4LargeArrays);
  }

  // ===== Test Suite A: Format Coverage =====

  @Test
  void testA1_Minimal_InMemory() throws Exception {
    HeapDump heap = parse(a1Minimal, ParsingMode.IN_MEMORY);

    assertEquals(1, heap.getClasses().size(), "Should have 1 class (Object)");
    assertEquals(1, heap.getObjects().count(), "Should have 1 object");
    assertTrue(heap.getGcRoots().size() >= 1, "Should have at least 1 GC root");

    HeapObject obj = heap.getObjects().findFirst().orElseThrow();
    assertNotNull(obj.getHeapClass(), "Object should have non-null class");
    assertEquals("java/lang/Object", obj.getHeapClass().getName());
  }

  @Test
  void testA2_AllPrimitiveTypes_InMemory() throws Exception {
    HeapDump heap = parse(a2AllPrimitiveTypes, ParsingMode.IN_MEMORY);

    assertEquals(8, heap.getObjects().count(), "Should have 8 primitive arrays");

    // Verify all are arrays
    heap.getObjects()
        .forEach(
            o -> {
              assertTrue(o.isArray(), "All objects should be arrays");
              assertTrue(
                  o.getHeapClass().isPrimitiveArray(),
                  "All arrays should be primitive arrays: " + o.getHeapClass().getName());
            });

    // Verify GC roots
    assertTrue(heap.getGcRoots().size() >= 8, "Should have GC roots for all arrays");
  }

  @Test
  void testA3_AllGcRoots_InMemory() throws Exception {
    HeapDump heap = parse(a3AllGcRoots, ParsingMode.IN_MEMORY);

    assertEquals(9, heap.getObjects().count(), "Should have 9 objects (one per root type)");
    assertEquals(
        9, heap.getGcRoots().size(), "Should have 9 GC roots (all standard HPROF 1.0.2 types)");

    // Verify objects exist for all GC roots
    for (var root : heap.getGcRoots()) {
      Optional<HeapObject> obj = heap.getObjectById(root.getObjectId());
      assertTrue(obj.isPresent(), "GC root should reference existing object: " + root.getObjectId());
    }
  }

  @Test
  void testA4_ComplexReferences_InMemory() throws Exception {
    HeapDump heap = parse(a4ComplexReferences, ParsingMode.IN_MEMORY);

    assertEquals(4, heap.getObjects().count(), "Should have 4 objects in chain");

    // Verify reference chain: 1000 -> 1001 -> 1002 -> 1003 -> null
    HeapObject head = heap.getObjectById(1000).orElseThrow();
    assertNotNull(head, "Head object should exist");

    long refCount = head.getOutboundReferences().count();
    assertEquals(1, refCount, "Head should have 1 reference");

    HeapObject tail = heap.getObjectById(1003).orElseThrow();
    assertNotNull(tail, "Tail object should exist");
    long tailRefCount = tail.getOutboundReferences().count();
    assertEquals(0, tailRefCount, "Tail should have no references (null)");
  }

  @Test
  void testA5_InstanceFields_InMemory() throws Exception {
    HeapDump heap = parse(a5InstanceFields, ParsingMode.IN_MEMORY);

    // Critical test for InboundIndexBuilder fix
    assertEquals(3, heap.getObjects().count(), "Should have 3 objects (1 holder + 2 targets)");

    HeapObject holder = heap.getObjectById(1000).orElseThrow();
    assertNotNull(holder, "Holder object should exist");
    assertEquals("Holder", holder.getHeapClass().getName());

    // Verify instance field references extracted
    long refCount = holder.getOutboundReferences().count();
    assertEquals(2, refCount, "Holder should have 2 references (ref1 and ref2 instance fields)");

    long[] refIds =
        holder.getOutboundReferences().mapToLong(HeapObject::getId).sorted().toArray();
    assertArrayEquals(
        new long[] {2000, 2001}, refIds, "Holder should reference both target objects");
  }

  @Test
  void testA6_StaticFields_InMemory() throws Exception {
    HeapDump heap = parse(a6StaticFields, ParsingMode.IN_MEMORY);

    assertEquals(1, heap.getObjects().count(), "Should have 1 object (target)");

    HeapClass cls = heap.getClassByName("ClassWithStatics").orElseThrow();
    assertNotNull(cls, "Class with statics should exist");

    // Verify static field exists
    assertEquals(2, cls.getStaticFields().size(), "Should have 2 static fields");
  }

  @Test
  void testA7_ClassHierarchy_InMemory() throws Exception {
    HeapDump heap = parse(a7ClassHierarchy, ParsingMode.IN_MEMORY);

    assertEquals(1, heap.getObjects().count(), "Should have 1 object (Derived instance)");

    HeapObject obj = heap.getObjectById(1000).orElseThrow();
    assertNotNull(obj, "Derived instance should exist");
    assertEquals("Derived", obj.getHeapClass().getName());

    HeapClass derived = obj.getHeapClass();
    HeapClass base = heap.getClassByName("Base").orElseThrow();
    assertNotNull(base, "Base class should exist");

    // Verify class hierarchy
    assertEquals(base, derived.getSuperClass(), "Derived should extend Base");

    // Verify instance size includes both base and derived fields
    assertEquals(8, derived.getInstanceSize(), "Instance size should be 8 (4 + 4)");
  }

  // ===== Test Suite B: Edge Cases =====

  @Test
  void testB1_ZeroLengthArrays_InMemory() throws Exception {
    HeapDump heap = parse(b1ZeroLengthArrays, ParsingMode.IN_MEMORY);

    assertEquals(3, heap.getObjects().count(), "Should have 3 zero-length arrays");

    // Verify all arrays have length 0
    heap.getObjects()
        .forEach(
            obj -> {
              assertTrue(obj.isArray(), "Object should be array");
              assertEquals(0, obj.getArrayLength(), "Array should have length 0");
            });
  }

  @Test
  void testB2_NullReferences_InMemory() throws Exception {
    HeapDump heap = parse(b2NullReferences, ParsingMode.IN_MEMORY);

    assertEquals(2, heap.getObjects().count(), "Should have 2 objects (holder + array)");

    HeapObject holder = heap.getObjectById(1000).orElseThrow();
    assertNotNull(holder, "Holder should exist");

    long refCount = holder.getOutboundReferences().count();
    assertEquals(0, refCount, "Holder should have no non-null references");

    HeapObject array = heap.getObjectById(2000).orElseThrow();
    assertNotNull(array, "Array should exist");
    assertEquals(3, array.getArrayLength(), "Array should have 3 elements");

    long arrayRefCount = array.getOutboundReferences().count();
    assertEquals(0, arrayRefCount, "Array should have no non-null references");
  }

  @Test
  void testB3_CircularReferences_InMemory() throws Exception {
    HeapDump heap = parse(b3CircularReferences, ParsingMode.IN_MEMORY);

    assertEquals(3, heap.getObjects().count(), "Should have 3 objects");

    // Self-reference: 1000 -> 1000
    HeapObject selfRef = heap.getObjectById(1000).orElseThrow();
    assertNotNull(selfRef, "Self-referencing object should exist");
    long[] refs1 =
        selfRef.getOutboundReferences().mapToLong(HeapObject::getId).toArray();
    assertEquals(1, refs1.length, "Should have 1 reference");
    assertEquals(1000, refs1[0], "Should reference itself");

    // Cycle: 2000 -> 2001 -> 2000
    HeapObject obj1 = heap.getObjectById(2000).orElseThrow();
    HeapObject obj2 = heap.getObjectById(2001).orElseThrow();
    assertNotNull(obj1, "First cycle object should exist");
    assertNotNull(obj2, "Second cycle object should exist");

    long[] refs2 = obj1.getOutboundReferences().mapToLong(HeapObject::getId).toArray();
    long[] refs3 = obj2.getOutboundReferences().mapToLong(HeapObject::getId).toArray();
    assertEquals(1, refs2.length, "Should have 1 reference");
    assertEquals(1, refs3.length, "Should have 1 reference");
    assertEquals(2001, refs2[0], "Should reference 2001");
    assertEquals(2000, refs3[0], "Should reference 2000");
  }

  @Test
  void testB4_LargeArrays_InMemory() throws Exception {
    HeapDump heap = parse(b4LargeArrays, ParsingMode.IN_MEMORY);

    assertTrue(heap.getObjects().count() >= 3, "Should have at least 3 arrays");

    // Small array (1 element)
    HeapObject small = heap.getObjectById(1000).orElseThrow();
    assertNotNull(small, "Small array should exist");
    assertEquals(1, small.getArrayLength(), "Small array should have 1 element");

    // Medium array (1000 elements)
    HeapObject medium = heap.getObjectById(3000).orElseThrow();
    assertNotNull(medium, "Medium array should exist");
    assertEquals(1000, medium.getArrayLength(), "Medium array should have 1000 elements");

    // Large primitive array (100000 elements)
    HeapObject large = heap.getObjectById(4000).orElseThrow();
    assertNotNull(large, "Large array should exist");
    assertEquals(100000, large.getArrayLength(), "Large array should have 100000 elements");
  }

  // ===== Differential Testing: In-Memory vs Indexed =====

  static Stream<Arguments> provideAllTestHeaps() {
    return Stream.of(
        Arguments.of("A1_Minimal", a1Minimal),
        Arguments.of("A2_AllPrimitiveTypes", a2AllPrimitiveTypes),
        Arguments.of("A3_AllGcRoots", a3AllGcRoots),
        Arguments.of("A4_ComplexReferences", a4ComplexReferences),
        Arguments.of("A5_InstanceFields", a5InstanceFields),
        Arguments.of("A6_StaticFields", a6StaticFields),
        Arguments.of("A7_ClassHierarchy", a7ClassHierarchy),
        Arguments.of("B1_ZeroLengthArrays", b1ZeroLengthArrays),
        Arguments.of("B2_NullReferences", b2NullReferences),
        Arguments.of("B3_CircularReferences", b3CircularReferences),
        Arguments.of("B4_LargeArrays", b4LargeArrays));
  }

  @ParameterizedTest(name = "{0}: Indexed mode matches in-memory")
  @MethodSource("provideAllTestHeaps")
  void testIndexedMatchesInMemory(String testName, Path heapPath) throws Exception {
    HeapDump inMemory = parse(heapPath, ParsingMode.IN_MEMORY);
    HeapDump indexed = parse(heapPath, ParsingMode.INDEXED);

    // Verify statistics match
    assertEquals(
        inMemory.getObjects().count(),
        indexed.getObjects().count(),
        testName + ": Object counts should match");
    assertEquals(
        inMemory.getClasses().size(),
        indexed.getClasses().size(),
        testName + ": Class counts should match");
    assertEquals(
        inMemory.getGcRoots().size(),
        indexed.getGcRoots().size(),
        testName + ": GC root counts should match");

    // Verify each object matches
    inMemory
        .getObjects()
        .forEach(
            inMemObj -> {
              Optional<HeapObject> indexedObjOpt = indexed.getObjectById(inMemObj.getId());
              assertTrue(
                  indexedObjOpt.isPresent(),
                  testName
                      + ": Object "
                      + inMemObj.getId()
                      + " should exist in indexed mode");
              HeapObject indexedObj = indexedObjOpt.get();

              // Critical: Class should not be null in indexed mode
              assertNotNull(
                  indexedObj.getHeapClass(),
                  testName
                      + ": Object "
                      + inMemObj.getId()
                      + " should have non-null class in indexed mode");

              assertEquals(
                  inMemObj.getHeapClass().getName(),
                  indexedObj.getHeapClass().getName(),
                  testName + ": Object " + inMemObj.getId() + " class names should match");

              // Verify array properties
              assertEquals(
                  inMemObj.isArray(),
                  indexedObj.isArray(),
                  testName + ": Object " + inMemObj.getId() + " isArray should match");
              if (inMemObj.isArray()) {
                assertEquals(
                    inMemObj.getArrayLength(),
                    indexedObj.getArrayLength(),
                    testName + ": Object " + inMemObj.getId() + " array length should match");

                // Verify primitive array class property
                assertEquals(
                    inMemObj.getHeapClass().isPrimitiveArray(),
                    indexedObj.getHeapClass().isPrimitiveArray(),
                    testName + ": Object " + inMemObj.getId() + " isPrimitiveArray should match");
              }

              // Verify references match
              long[] inMemRefs =
                  inMemObj.getOutboundReferences().mapToLong(HeapObject::getId).sorted().toArray();
              long[] indexedRefs =
                  indexedObj
                      .getOutboundReferences()
                      .mapToLong(HeapObject::getId)
                      .sorted()
                      .toArray();
              assertArrayEquals(
                  inMemRefs,
                  indexedRefs,
                  testName + ": Object " + inMemObj.getId() + " references should match");
            });
  }

  @ParameterizedTest(name = "{0}: Can parse without errors")
  @MethodSource("provideAllTestHeaps")
  void testAllHeapsParse(String testName, Path heapPath) throws Exception {
    assertDoesNotThrow(
        () -> {
          HeapDump heap = parse(heapPath, ParsingMode.IN_MEMORY);
          assertTrue(heap.getObjects().count() > 0, testName + ": Should have at least one object");
        },
        testName + ": Should parse without exceptions");
  }

  // ===== Helper Methods =====

  private static HeapDump parse(Path heapPath, ParsingMode mode) throws Exception {
    ParserOptions options = ParserOptions.builder().parsingMode(mode).build();
    return HeapDumpParser.parse(heapPath, options, null);
  }
}

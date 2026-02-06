package io.jafar.hdump.impl;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.hdump.internal.HprofReader;
import io.jafar.hdump.test.SyntheticHeapDumpGenerator;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for two-pass parsing approach (M2). */
class TwoPassParsingTest {

  private Path tempDir;
  private Path testHeapDump;

  @BeforeEach
  void setUp() throws IOException {
    tempDir = Files.createTempDirectory("jafar-twopass-test");
    testHeapDump = tempDir.resolve("test.hprof");

    // Generate a small synthetic heap dump with 10 objects
    SyntheticHeapDumpGenerator.generateMinimalHeapDump(testHeapDump, 10);
  }

  @AfterEach
  void tearDown() throws IOException {
    // Clean up temp directory
    if (tempDir != null && Files.exists(tempDir)) {
      Files.walk(tempDir)
          .sorted((a, b) -> -a.compareTo(b))
          .forEach(
              path -> {
                try {
                  Files.delete(path);
                } catch (IOException e) {
                  // Ignore
                }
              });
    }
  }

  @Test
  void testPass1AddressCollection() throws IOException {
    // Pass 1: Collect object addresses
    AddressCollector collector = new AddressCollector();
    collector.collectAddresses(testHeapDump);

    // Verify addresses were collected
    LongArrayList addresses = collector.getAddresses();
    assertNotNull(addresses, "Address list should not be null");
    assertEquals(10, addresses.size(), "Should collect exactly 10 objects");

    // Verify addresses are unique (no duplicates)
    assertEquals(
        addresses.size(),
        addresses.stream().distinct().count(),
        "All addresses should be unique");

    // Verify address-to-ID mapping is created
    Long2IntOpenHashMap addressToId = collector.getAddressToIdMap();
    assertNotNull(addressToId, "Address-to-ID map should not be null");
    assertEquals(10, addressToId.size(), "Map size should match address count");

    // Verify IDs are sequential starting from 0
    for (int i = 0; i < addresses.size(); i++) {
      long address = addresses.getLong(i);
      int id32 = addressToId.get(address);
      assertEquals(i, id32, "Object IDs should be sequential starting from 0");
    }
  }

  @Test
  void testPass1WithLargerHeap() throws IOException {
    Path largerHeap = tempDir.resolve("larger.hprof");
    SyntheticHeapDumpGenerator.generateMinimalHeapDump(largerHeap, 100);

    AddressCollector collector = new AddressCollector();
    collector.collectAddresses(largerHeap);

    LongArrayList addresses = collector.getAddresses();
    assertEquals(100, addresses.size(), "Should collect exactly 100 objects");

    // Verify memory efficiency: addresses are sorted for binary search
    for (int i = 1; i < addresses.size(); i++) {
      assertTrue(
          addresses.getLong(i) > addresses.getLong(i - 1),
          "Sorted addresses should be strictly increasing");
    }
  }

  /**
   * Helper class for Pass 1: Address collection.
   *
   * <p>This will be integrated into HeapDumpImpl later, but kept separate for testing.
   */
  static class AddressCollector {
    private final LongArrayList objectAddresses = new LongArrayList();
    private Long2IntOpenHashMap addressToId;

    void collectAddresses(Path heapDumpPath) throws IOException {
      objectAddresses.clear();

      try (HprofReader reader = new HprofReader(heapDumpPath)) {
        reader.reset();

        while (reader.hasMoreRecords()) {
          var header = reader.readRecordHeader();
          if (header == null) break;

          // Only process heap dump records
          if (header.tag() == 0x0C || header.tag() == 0x1C) { // HEAP_DUMP or HEAP_DUMP_SEGMENT
            collectFromHeapDump(reader, header);
          } else {
            reader.skipRecordBody(header);
          }
        }
      }

      // Sort addresses for binary search
      objectAddresses.sort(null);

      // Create address-to-ID mapping (64-bit address -> 32-bit sequential ID)
      addressToId = new Long2IntOpenHashMap(objectAddresses.size());
      addressToId.defaultReturnValue(-1);
      for (int i = 0; i < objectAddresses.size(); i++) {
        addressToId.put(objectAddresses.getLong(i), i);
      }
    }

    private void collectFromHeapDump(HprofReader reader, HprofReader.RecordHeader header) {
      long endPos = header.bodyPosition() + header.length();

      while (reader.position() < endPos) {
        int subTag = reader.readU1();

        switch (subTag) {
          case 0x21: // INSTANCE_DUMP
            collectInstanceAddress(reader);
            break;
          case 0x22: // OBJ_ARRAY_DUMP
            collectObjArrayAddress(reader);
            break;
          case 0x23: // PRIM_ARRAY_DUMP
            collectPrimArrayAddress(reader);
            break;
          case 0x20: // CLASS_DUMP
            skipClassDump(reader);
            break;
          default:
            // GC roots and other tags - skip
            skipHeapSubRecord(reader, subTag);
            break;
        }
      }
    }

    private void collectInstanceAddress(HprofReader reader) {
      long objId = reader.readId();
      objectAddresses.add(objId);

      // Skip rest of record
      reader.readI4(); // stack trace serial
      reader.readId(); // class ID
      int dataSize = reader.readI4();
      reader.skip(dataSize);
    }

    private void collectObjArrayAddress(HprofReader reader) {
      long objId = reader.readId();
      objectAddresses.add(objId);

      // Skip rest of record
      reader.readI4(); // stack trace serial
      int length = reader.readI4();
      reader.readId(); // array class ID
      reader.skip(length * reader.getIdSize());
    }

    private void collectPrimArrayAddress(HprofReader reader) {
      long objId = reader.readId();
      objectAddresses.add(objId);

      // Skip rest of record
      reader.readI4(); // stack trace serial
      int length = reader.readI4();
      int elemType = reader.readU1();
      int elemSize = getBasicTypeSize(elemType, reader.getIdSize());
      reader.skip(length * elemSize);
    }

    private void skipClassDump(HprofReader reader) {
      // Skip class dump record structure
      reader.readId(); // class ID
      reader.readI4(); // stack trace serial
      reader.readId(); // super class ID
      reader.readId(); // class loader ID
      reader.readId(); // signers ID
      reader.readId(); // protection domain ID
      reader.readId(); // reserved
      reader.readId(); // reserved
      reader.readI4(); // instance size

      // Skip constant pool
      int cpSize = reader.readU2();
      for (int i = 0; i < cpSize; i++) {
        reader.readU2(); // constant pool index
        int type = reader.readU1();
        reader.readValue(type); // skip value
      }

      // Skip static fields
      int staticCount = reader.readU2();
      for (int i = 0; i < staticCount; i++) {
        reader.readId(); // name ID
        int type = reader.readU1();
        reader.readValue(type); // skip value
      }

      // Skip instance fields
      int fieldCount = reader.readU2();
      for (int i = 0; i < fieldCount; i++) {
        reader.readId(); // name ID
        reader.readU1(); // type
      }
    }

    private void skipHeapSubRecord(HprofReader reader, int subTag) {
      // Handle GC root records
      switch (subTag) {
        case 0xFF: // ROOT_UNKNOWN
        case 0x01: // ROOT_JNI_GLOBAL (has extra ID)
        case 0x05: // ROOT_STICKY_CLASS
        case 0x07: // ROOT_MONITOR_USED
          reader.readId(); // object ID
          if (subTag == 0x01) reader.readId(); // JNI global ref ID
          break;
        case 0x02: // ROOT_JNI_LOCAL
        case 0x03: // ROOT_JAVA_FRAME
        case 0x04: // ROOT_NATIVE_STACK
        case 0x06: // ROOT_THREAD_BLOCK
          reader.readId(); // object ID
          reader.readI4(); // thread serial
          if (subTag == 0x03 || subTag == 0x04) reader.readI4(); // frame number
          break;
        case 0x08: // ROOT_THREAD_OBJ
          reader.readId(); // object ID
          reader.readI4(); // thread serial
          reader.readI4(); // stack trace serial
          break;
        default:
          // Unknown tag - should not happen with our synthetic dumps
          throw new RuntimeException(
              "Unknown heap sub-record tag: 0x" + Integer.toHexString(subTag));
      }
    }

    private static int getBasicTypeSize(int type, int idSize) {
      return switch (type) {
        case 2 -> idSize; // OBJECT
        case 4 -> 1; // BOOLEAN
        case 5 -> 2; // CHAR
        case 6 -> 4; // FLOAT
        case 7 -> 8; // DOUBLE
        case 8 -> 1; // BYTE
        case 9 -> 2; // SHORT
        case 10 -> 4; // INT
        case 11 -> 8; // LONG
        default -> 1;
      };
    }

    LongArrayList getAddresses() {
      return objectAddresses;
    }

    Long2IntOpenHashMap getAddressToIdMap() {
      return addressToId;
    }
  }
}

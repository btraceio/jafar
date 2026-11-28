package io.jafar.hdump.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapDumpParser;
import io.jafar.hdump.api.HeapDumpParser.ParserOptions;
import io.jafar.hdump.api.HeapObject;
import io.jafar.hdump.internal.BasicType;
import io.jafar.hdump.internal.HeapTag;
import io.jafar.hdump.internal.HprofTag;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #262: an array whose data exceeds {@code Integer.MAX_VALUE} bytes made the {@code int}
 * shallow size overflow to a negative number, which was added to the heap total. The dump here
 * holds one {@code long[]} of 300 million elements (2.4 GB of data); the file is sparse, so the
 * data costs no disk.
 */
class HugeArrayShallowSizeTest {

  private static final int ID_SIZE = 8;
  private static final int LENGTH = 300_000_000;
  private static final long DATA = (long) LENGTH * 8; // long[]: 2_400_000_000 bytes

  @TempDir Path dir;

  @Test
  void inMemoryCapsTheObjectAndCountsTheExactSize() throws IOException {
    check(ParserOptions.IN_MEMORY);
  }

  @Test
  void indexedCapsTheObjectAndCountsTheExactSize() throws IOException {
    check(ParserOptions.INDEXED);
  }

  private void check(ParserOptions options) throws IOException {
    Path file = sparseDumpWithOneHugeLongArray(options.parsingMode().name());
    try (HeapDump dump = HeapDumpParser.parse(file, options)) {
      // header estimate for a primitive array: idSize + 12, plus the data
      assertEquals(ID_SIZE + 12 + DATA, dump.getTotalHeapSize(), "heap total, " + options);
      List<HeapObject> objects = dump.getObjects().collect(Collectors.toList());
      assertEquals(1, objects.size(), objects.toString());
      int shallow = objects.get(0).getShallowSize();
      assertTrue(shallow >= 0, "shallow size " + shallow + ", " + options);
      assertEquals(Integer.MAX_VALUE, shallow, options.toString());
    }
  }

  @Test
  void indexedRetainedSizeIsCappedNotNegative() throws IOException {
    checkRetained(ParserOptions.INDEXED);
  }

  @Test
  void inMemoryRetainedSizeIsCappedNotNegative() throws IOException {
    checkRetained(ParserOptions.IN_MEMORY);
  }

  /**
   * Retained sizes are summed from the per-object shallow sizes, so the array counts as
   * Integer.MAX_VALUE there. On main they were -2147483629 in both modes: from the negative
   * getShallowSize() in IN_MEMORY mode, from IndexedRefs' own wrapped computation in INDEXED mode.
   */
  private void checkRetained(ParserOptions options) throws IOException {
    Path file = sparseDump(options.parsingMode().name() + "-rooted", true);
    try (HeapDump dump = HeapDumpParser.parse(file, options)) {
      dump.computeDominators();
      dump.computeFullDominatorTree(null);
      HeapObject array = dump.getObjects().findFirst().orElseThrow();
      assertEquals(Integer.MAX_VALUE, array.getRetainedSize(), "retained size, " + options);
    }
  }

  private Path sparseDumpWithOneHugeLongArray(String name) throws IOException {
    return sparseDump(name, false);
  }

  private Path sparseDump(String name, boolean rooted) throws IOException {
    Path file = dir.resolve(name + ".hprof");
    try (RandomAccessFile out = new RandomAccessFile(file.toFile(), "rw")) {
      out.write("JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.US_ASCII));
      out.writeInt(ID_SIZE);
      out.writeLong(0L);
      long body = (rooted ? 1 + ID_SIZE : 0) + 1 + ID_SIZE + 4 + 4 + 1 + DATA;
      out.writeByte(HprofTag.HEAP_DUMP_SEGMENT);
      out.writeInt(0); // time offset
      out.writeInt((int) body); // u4, unsigned: about 2.4e9 fits in 32 bits
      if (rooted) {
        out.writeByte(HeapTag.ROOT_UNKNOWN);
        out.writeLong(0x1000L);
      }
      out.writeByte(HeapTag.PRIM_ARRAY_DUMP);
      out.writeLong(0x1000L); // array id
      out.writeInt(0); // stack trace serial
      out.writeInt(LENGTH);
      out.writeByte(BasicType.LONG);
      out.seek(out.getFilePointer() + DATA); // leave the data as a hole
      out.writeByte(HprofTag.HEAP_DUMP_END);
      out.writeInt(0);
      out.writeInt(0);
    }
    return file;
  }
}

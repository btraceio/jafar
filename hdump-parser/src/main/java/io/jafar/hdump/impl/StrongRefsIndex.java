package io.jafar.hdump.impl;

import io.jafar.hdump.index.IndexFormat;
import io.jafar.utils.CustomByteBuffer;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The strong-reference graph of an INDEXED dump on disk, by 32-bit object id ({@link
 * IndexFormat#STRONGREFS_INDEX_NAME}, {@link IndexFormat#STRONGREFS_OFFSETS_INDEX_NAME}).
 *
 * <p>Built once by streaming over all id32 values with {@link IndexedRefs} — the address-to-id32
 * lookup per reference happens once here instead of on every traversal — and written sequentially
 * (heap use: two output buffers). Read through memory mapping, so the graph costs page cache, not
 * heap. Rows hold exactly what {@link IndexedRefs#strongRefs} returns.
 *
 * <p>Not thread-safe; {@link #view()} gives each thread its own buffer state. Validated on open by
 * header (magic, version, entry count, HPROF file size) and file lengths; rebuilt when invalid.
 */
final class StrongRefsIndex implements Closeable {

  private static final int SPLICE_SIZE = 256 * 1024 * 1024;

  /**
   * Header of both files: magic (4), version (4), entry count (8), HPROF file size (8). 24 bytes,
   * so every offset long is 8-aligned and never straddles a splice (a straddling read goes through
   * the spliced buffer's shared scratch array).
   */
  static final int HEADER_SIZE = 24;

  /** Version of this index's layout, independent of the other index files. */
  static final int VERSION = 1;

  private final CustomByteBuffer offsets;
  private final CustomByteBuffer refs;
  private final int count;

  private StrongRefsIndex(CustomByteBuffer offsets, CustomByteBuffer refs, int count) {
    this.offsets = offsets;
    this.refs = refs;
    this.count = count;
  }

  /** Opens the index in {@code indexDir}, building it first when absent or not for this dump. */
  static StrongRefsIndex openOrBuild(HeapDumpImpl dump, Path indexDir) throws IOException {
    int count = dump.getObjectIndexReader().getEntryCount();
    long hprofSize = Files.size(dump.getPath());
    Path refsFile = indexDir.resolve(IndexFormat.STRONGREFS_INDEX_NAME);
    Path offsetsFile = indexDir.resolve(IndexFormat.STRONGREFS_OFFSETS_INDEX_NAME);
    if (!valid(offsetsFile, refsFile, count, hprofSize)) {
      build(dump, count, hprofSize, refsFile, offsetsFile);
    }
    CustomByteBuffer o = CustomByteBuffer.map(offsetsFile, SPLICE_SIZE);
    o.order(ByteOrder.BIG_ENDIAN);
    CustomByteBuffer r = CustomByteBuffer.map(refsFile, SPLICE_SIZE);
    r.order(ByteOrder.BIG_ENDIAN);
    return new StrongRefsIndex(o, r, count);
  }

  /**
   * Both files have this dump's header and exactly the length their contents need: the offsets file
   * {@code HEADER + 8 (count + 1)}, the refs file {@code HEADER + 4 * offset[count]}. A truncated
   * file, or a pair from different builds, fails.
   */
  private static boolean valid(Path offsetsFile, Path refsFile, int count, long hprofSize) {
    try {
      if (!Files.exists(offsetsFile) || !Files.exists(refsFile)) return false;
      if (Files.size(offsetsFile) != HEADER_SIZE + 8L * (count + 1L)) return false;
      if (!header(offsetsFile, IndexFormat.STRONGREFS_OFFSETS_INDEX_MAGIC, count, hprofSize)
          || !header(refsFile, IndexFormat.STRONGREFS_INDEX_MAGIC, count, hprofSize)) {
        return false;
      }
      long total;
      try (var raf = new java.io.RandomAccessFile(offsetsFile.toFile(), "r")) {
        raf.seek(HEADER_SIZE + 8L * count);
        total = raf.readLong();
      }
      return Files.size(refsFile) == HEADER_SIZE + 4L * total;
    } catch (IOException e) {
      return false;
    }
  }

  private static boolean header(Path file, int magic, int count, long hprofSize)
      throws IOException {
    try (var in = new java.io.DataInputStream(Files.newInputStream(file))) {
      return in.readInt() == magic
          && in.readInt() == VERSION
          && in.readLong() == count
          && in.readLong() == hprofSize;
    }
  }

  private static void build(
      HeapDumpImpl dump, int count, long hprofSize, Path refsFile, Path offsetsFile)
      throws IOException {
    Path refsTmp = refsFile.resolveSibling(refsFile.getFileName() + ".tmp");
    Path offsetsTmp = offsetsFile.resolveSibling(offsetsFile.getFileName() + ".tmp");
    IndexedRefs source = new IndexedRefs(dump);
    try (DataOutputStream r =
            new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(refsTmp), 1 << 16));
        DataOutputStream o =
            new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(offsetsTmp), 1 << 16))) {
      writeHeader(r, IndexFormat.STRONGREFS_INDEX_MAGIC, count, hprofSize);
      writeHeader(o, IndexFormat.STRONGREFS_OFFSETS_INDEX_MAGIC, count, hprofSize);
      long pos = 0;
      o.writeLong(0);
      for (int v = 0; v < count; v++) {
        int k = source.strongRefs(v);
        int[] buf = source.refs();
        for (int i = 0; i < k; i++) {
          r.writeInt(buf[i]);
        }
        pos += k;
        o.writeLong(pos);
      }
    }
    // A crash between the moves leaves a pair that valid() rejects (lengths do not match).
    Files.move(refsTmp, refsFile, StandardCopyOption.REPLACE_EXISTING);
    Files.move(offsetsTmp, offsetsFile, StandardCopyOption.REPLACE_EXISTING);
  }

  private static void writeHeader(DataOutputStream out, int magic, int count, long hprofSize)
      throws IOException {
    out.writeInt(magic);
    out.writeInt(VERSION);
    out.writeLong(count);
    out.writeLong(hprofSize);
  }

  /** A view over the same mappings with its own buffer state, for one thread. */
  StrongRefsIndex view() {
    CustomByteBuffer o = offsets.slice(0, offsets.limit());
    o.order(ByteOrder.BIG_ENDIAN);
    CustomByteBuffer r = refs.slice(0, refs.limit());
    r.order(ByteOrder.BIG_ENDIAN);
    return new StrongRefsIndex(o, r, count);
  }

  int size() {
    return count;
  }

  /** Index of the first reference of {@code id32} (and the end of the previous object's). */
  long start(int id32) {
    return offsets.getLong(HEADER_SIZE + 8L * id32);
  }

  /** The {@code i}-th reference overall (an id32). */
  int ref(long i) {
    return refs.getInt(HEADER_SIZE + 4L * i);
  }

  @Override
  public void close() throws IOException {
    offsets.close();
    refs.close();
  }
}

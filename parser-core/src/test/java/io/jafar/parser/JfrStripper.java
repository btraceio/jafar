package io.jafar.parser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Strips all regular events from a JFR recording, keeping only the chunk header, constant-pool
 * events, and the metadata event. The result is a valid, self-contained JFR file that exercises the
 * metadata/CP parsing code paths without the bulk of event data.
 *
 * <p>Useful for generating small, reproducible test fixtures from real-world JFR 2.1 recordings.
 */
public final class JfrStripper {

  private static final int HEADER_SIZE = 68;
  private static final int MAGIC_BE = 0x464C5200; // "FLR\0"

  private JfrStripper() {}

  /** CLI entry point: {@code JfrStripper <src.jfr> <dst.jfr>} */
  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      System.err.println("Usage: JfrStripper <src.jfr> <dst.jfr>");
      System.exit(1);
    }
    Path src = Path.of(args[0]);
    Path dst = Path.of(args[1]);
    strip(src, dst);
    System.out.printf("Stripped %s -> %d bytes%n", src.getFileName(), Files.size(dst));
  }

  /**
   * Strips all events from {@code src} and writes the result to {@code dst}.
   *
   * @param src path to the source JFR file
   * @param dst path to write the stripped JFR file
   * @throws IOException if reading or writing fails
   */
  public static void strip(Path src, Path dst) throws IOException {
    byte[] data = Files.readAllBytes(src);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int offset = 0;
    while (offset < data.length) {
      int remaining = data.length - offset;
      if (remaining < HEADER_SIZE) break;

      // --- Read chunk header (big-endian) ---
      ByteBuffer hdr = ByteBuffer.wrap(data, offset, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
      int magic = hdr.getInt();
      if (magic != MAGIC_BE) break;
      short major = hdr.getShort();
      short minor = hdr.getShort();
      long chunkSizeL = hdr.getLong();
      long cpOffsetL = hdr.getLong(); // relative to chunk start
      long metaOffsetL = hdr.getLong(); // relative to chunk start
      if (chunkSizeL > Integer.MAX_VALUE
          || cpOffsetL > Integer.MAX_VALUE
          || metaOffsetL > Integer.MAX_VALUE) {
        throw new IOException("Chunk fields exceed int range (chunk too large for JfrStripper)");
      }
      int chunkSize = (int) chunkSizeL;
      int cpOffset = (int) cpOffsetL;
      int metaOffset = (int) metaOffsetL;
      long startNanos = hdr.getLong();
      long duration = hdr.getLong();
      long startTicks = hdr.getLong();
      long frequency = hdr.getLong();
      int compressed = hdr.getInt();

      // --- Collect all CP events following the delta chain ---
      // Each CP's nextOffsetDelta (field 5 in the event) is the distance from the current CP's
      // absolute position to the next CP's absolute position (both relative to chunk start).
      List<byte[]> cpEvents = new ArrayList<>();
      int cpPos = cpOffset;
      while (cpPos != 0) {
        int evSize = readEventSize(data, offset + cpPos);
        byte[] evBytes = new byte[evSize];
        System.arraycopy(data, offset + cpPos, evBytes, 0, evSize);
        int delta = readNextOffsetDelta(evBytes);
        cpEvents.add(evBytes);
        if (delta == 0) break;
        cpPos += delta;
      }

      // --- Collect metadata event bytes ---
      int metaSize = readEventSize(data, offset + metaOffset);
      byte[] metaBytes = new byte[metaSize];
      System.arraycopy(data, offset + metaOffset, metaBytes, 0, metaSize);

      // --- Patch nextOffsetDelta in each CP event ---
      // After stripping, CPs are placed consecutively at offset HEADER_SIZE.
      // For CP[i], new delta = size of CP[i] (distance to CP[i+1] start).
      // For the last CP, delta = 0.
      //
      // Two-pass fixed-point approach: patching the delta may change the varint's encoded
      // length, which changes the event size, which changes the delta for the previous event.
      // We iterate until sizes stabilise (converges in at most a few rounds).
      List<byte[]> patched = new ArrayList<>(cpEvents.size());
      // Pass 0: initialise with delta=0 for all events to get baseline sizes.
      for (byte[] ev : cpEvents) {
        patched.add(patchNextOffsetDelta(ev, 0));
      }
      // Fixed-point: re-patch non-last events with delta = their own patched size.
      boolean changed = true;
      while (changed) {
        changed = false;
        for (int i = 0; i < patched.size() - 1; i++) {
          int delta = patched.get(i).length;
          byte[] updated = patchNextOffsetDelta(patched.get(i), delta);
          if (updated.length != patched.get(i).length) changed = true;
          patched.set(i, updated);
        }
      }
      int cpTotal = 0;
      for (byte[] cp : patched) cpTotal += cp.length;

      // --- Assemble stripped chunk ---
      int newCpOffset = HEADER_SIZE;
      int newMetaOffset = HEADER_SIZE + cpTotal;
      int newChunkSize = newMetaOffset + metaBytes.length;

      ByteBuffer newHdr = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
      newHdr.putInt(MAGIC_BE);
      newHdr.putShort(major);
      newHdr.putShort(minor);
      newHdr.putLong(newChunkSize);
      newHdr.putLong(newCpOffset);
      newHdr.putLong(newMetaOffset);
      newHdr.putLong(startNanos);
      newHdr.putLong(duration);
      newHdr.putLong(startTicks);
      newHdr.putLong(frequency);
      newHdr.putInt(compressed);

      out.write(newHdr.array());
      for (byte[] cp : patched) out.write(cp);
      out.write(metaBytes);

      offset += chunkSize;
    }
    Files.write(dst, out.toByteArray());
  }

  // ---------------------------------------------------------------------------
  // Varint helpers (JFR uses unsigned LEB128)
  // ---------------------------------------------------------------------------

  /** Returns the total size of the event (value of the first size varint). */
  private static int readEventSize(byte[] data, int pos) {
    long v = 0;
    int shift = 0;
    for (; ; ) {
      int b = data[pos++] & 0xFF;
      v |= (long) (b & 0x7F) << shift;
      if ((b & 0x80) == 0) return (int) v;
      shift += 7;
    }
  }

  /**
   * Reads the {@code nextOffsetDelta} field from a raw CP event byte array.
   *
   * <p>CP event layout (LEB128 varints): size | typeId(1) | startTime | duration | nextOffsetDelta
   * | isFlush(1B) | cpCount | entries…
   */
  private static int readNextOffsetDelta(byte[] ev) {
    int pos = varintSkip(ev, 0); // skip size
    pos = varintSkip(ev, pos); // skip typeId
    pos = varintSkip(ev, pos); // skip startTime
    pos = varintSkip(ev, pos); // skip duration
    return (int) readVarint(ev, pos);
  }

  /**
   * Returns a copy of {@code ev} with its {@code nextOffsetDelta} field set to {@code newDelta}. If
   * the varint encoding length of the new delta differs from the old one, the event is rebuilt and
   * the size field updated accordingly.
   */
  private static byte[] patchNextOffsetDelta(byte[] ev, int newDelta) throws IOException {
    int p0 = varintSkip(ev, 0); // after size varint
    int p1 = varintSkip(ev, p0); // after typeId
    int p2 = varintSkip(ev, p1); // after startTime
    int p3 = varintSkip(ev, p2); // after duration
    int p4 = varintSkip(ev, p3); // after delta (= start of rest)

    int oldDeltaLen = p4 - p3;
    int newDeltaLen = varintLen(newDelta);

    if (oldDeltaLen == newDeltaLen) {
      // Same length: patch in-place (no size change)
      byte[] result = ev.clone();
      writeVarint(result, p3, newDelta);
      return result;
    }

    // Different length: rebuild payload, then prefix with correct size varint
    ByteArrayOutputStream payload = new ByteArrayOutputStream();
    // Re-emit typeId, startTime, duration from original bytes
    payload.write(ev, p0, p3 - p0);
    // New delta
    writeVarintTo(payload, newDelta);
    // Rest (isFlush + cpCount + entries)
    payload.write(ev, p4, ev.length - p4);
    byte[] payloadBytes = payload.toByteArray();

    int newTotal = computeTotalSize(payloadBytes.length);
    ByteArrayOutputStream result = new ByteArrayOutputStream(newTotal);
    writeVarintTo(result, newTotal);
    result.write(payloadBytes);
    return result.toByteArray();
  }

  /**
   * Computes the total event size (including the size varint itself) given the payload length
   * (everything after the size varint).
   */
  private static int computeTotalSize(int payloadLen) {
    int size = payloadLen + 1;
    while (true) {
      int next = payloadLen + varintLen(size);
      if (next == size) break;
      size = next;
    }
    return size;
  }

  /** Skips a LEB128 varint at {@code pos}, returning the position after it. */
  private static int varintSkip(byte[] data, int pos) {
    while ((data[pos++] & 0x80) != 0) {}
    return pos;
  }

  /** Reads a LEB128 unsigned varint at {@code pos}. */
  private static long readVarint(byte[] data, int pos) {
    long v = 0;
    int shift = 0;
    for (; ; ) {
      int b = data[pos++] & 0xFF;
      v |= (long) (b & 0x7F) << shift;
      if ((b & 0x80) == 0) return v;
      shift += 7;
    }
  }

  /** Returns the number of bytes required to encode {@code v} as a LEB128 varint. */
  private static int varintLen(long v) {
    int len = 0;
    do {
      v >>>= 7;
      len++;
    } while (v != 0);
    return len;
  }

  /** Writes {@code v} as a LEB128 varint into {@code buf} starting at {@code pos}. */
  private static void writeVarint(byte[] buf, int pos, long v) {
    do {
      byte b = (byte) (v & 0x7F);
      v >>>= 7;
      if (v != 0) b |= (byte) 0x80;
      buf[pos++] = b;
    } while (v != 0);
  }

  /** Writes {@code v} as a LEB128 varint into {@code out}. */
  private static void writeVarintTo(ByteArrayOutputStream out, long v) {
    do {
      byte b = (byte) (v & 0x7F);
      v >>>= 7;
      if (v != 0) b |= (byte) 0x80;
      out.write(b & 0xFF);
    } while (v != 0);
  }
}

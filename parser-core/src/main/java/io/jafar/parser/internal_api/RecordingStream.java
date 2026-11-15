package io.jafar.parser.internal_api;

import io.jafar.parser.ParsingUtils;
import io.jafar.parser.api.ParserContext;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.BufferUnderflowException;
import java.nio.file.Path;

/**
 * A stream for reading JFR recording data with position tracking and context management.
 *
 * <p>This class provides a high-level interface for reading JFR recording data, including support
 * for slicing, marking/resetting, and context management.
 */
public final class RecordingStream implements AutoCloseable {
  /** The underlying reader for the recording data. */
  private final RecordingStreamReader reader;

  /** The parser context associated with this stream. */
  private final ParserContext context;

  /** The marked position for reset operations. */
  private long mark = -1;

  /**
   * Thread-local cache of {@link RecordingStream} slices for constant pool resolution.
   *
   * <p>Each entry wraps a {@link RecordingStreamReader} that shares the same underlying
   * memory-mapped buffer but has independent position tracking. Lazy-initialized to avoid
   * allocating a ThreadLocal on short-lived wrapper instances that never call {@link
   * #threadLocalStreamSlice()}.
   *
   * <p><b>Lifecycle:</b> each participating thread's entry is removed when {@link #close()} is
   * called from that thread. Entries for other threads are removed when those threads exit. In
   * practice, {@link RecordingStream} instances are chunk-scoped, so exposure is bounded.
   */
  private volatile ThreadLocal<RecordingStream> threadLocalStreamCache;

  /**
   * Constructs a new RecordingStream from a file path.
   *
   * @param path the path to the JFR recording file
   * @param context the parser context to use
   * @throws IOException if an I/O error occurs during construction
   */
  RecordingStream(Path path, ParserContext context) throws IOException {
    this(RecordingStreamReader.mapped(path), context);
  }

  /**
   * Creates a slice of this stream with the specified position and length.
   *
   * @param pos the starting position for the slice
   * @param len the length of the slice
   * @param context the parser context for the slice
   * @return a new RecordingStream representing the slice
   */
  public RecordingStream slice(long pos, long len, ParserContext context) {
    long length = reader.length();
    if (pos < 0 || len < 0 || pos > length || len > length - pos) {
      throw outOfBounds("slice [" + pos + ", " + pos + " + " + len + ")");
    }
    return new RecordingStream(reader.slice(pos, len), context);
  }

  /**
   * The failure for any access outside the stream's data: an {@link EOFException} carried by an
   * {@link UncheckedIOException}, because the read methods declare no checked exception.
   *
   * <p>Recording bytes are untrusted input, so running off the end of the data - a truncated file,
   * or a corrupted size, count or offset that sends the parser there - is an I/O condition of the
   * recording, not a programming error. Left alone it surfaces as whatever the backing buffer
   * happens to throw ({@code BufferUnderflowException}, {@code IllegalArgumentException}, {@code
   * IndexOutOfBoundsException}), which tells the caller nothing about the recording. The Go
   * parser's reader reports the same condition as "unexpected end of data".
   *
   * @param what the access that was attempted
   * @return the exception to throw
   */
  private UncheckedIOException outOfBounds(String what) {
    long length = -1;
    long position = -1;
    try {
      length = reader.length();
      position = reader.position();
    } catch (RuntimeException ignored) {
      // the reader's own state is what failed; report what is known
    }
    return new UncheckedIOException(
        new EOFException(
            "Unexpected end of recording data: "
                + what
                + " at position "
                + position
                + " of "
                + length));
  }

  /**
   * Whether {@code e} is how a backing buffer reports an access outside its bounds. Anything else
   * is not translated: it is not known to be a property of the recording.
   */
  private static boolean isBoundsFailure(RuntimeException e) {
    return e instanceof BufferUnderflowException
        || e instanceof IndexOutOfBoundsException
        || e instanceof IllegalArgumentException;
  }

  private RuntimeException translate(RuntimeException e, String what) {
    return isBoundsFailure(e) ? outOfBounds(what) : e;
  }

  /**
   * Constructs a new RecordingStream from a reader and context.
   *
   * @param reader the reader for the recording data
   * @param context the parser context to use
   */
  public RecordingStream(RecordingStreamReader reader, ParserContext context) {
    this(reader, context, true);
  }

  /**
   * Constructs a new RecordingStream with optional context registration.
   *
   * <p>When {@code register} is {@code false}, the stream is not registered in the context. This is
   * used for creating temporary streams for thread-safe constant pool resolution.
   *
   * @param reader the reader for the recording data
   * @param context the parser context to use
   * @param register whether to register this stream in the context
   */
  public RecordingStream(RecordingStreamReader reader, ParserContext context, boolean register) {
    this.reader = reader;
    this.context = context;
    if (register) {
      this.context.put(RecordingStream.class, this);
    }
  }

  /**
   * Returns a thread-local {@link RecordingStream} slice, creating one lazily per thread.
   *
   * <p>Each slice shares the same underlying memory-mapped buffer but has independent position
   * tracking. Position save/restore can be performed on the returned stream directly. Eliminates
   * per-resolution wrapper allocation compared to {@code new RecordingStream(slice, ...)}.
   *
   * <p>Re-entrancy (e.g. reading a CP entry that triggers a string pool lookup) is handled by the
   * caller via explicit position save/restore before and after each use.
   *
   * @return a reusable stream slice for the current thread
   */
  public RecordingStream threadLocalStreamSlice() {
    ThreadLocal<RecordingStream> tl = threadLocalStreamCache;
    if (tl == null) {
      synchronized (this) {
        tl = threadLocalStreamCache;
        if (tl == null) {
          tl = new ThreadLocal<>();
          threadLocalStreamCache = tl;
        }
      }
    }
    RecordingStream s = tl.get();
    if (s == null) {
      s = new RecordingStream(reader.slice(0, reader.length()), context, false);
      tl.set(s);
    }
    return s;
  }

  /**
   * Gets the parser context associated with this stream.
   *
   * @return the parser context
   */
  public ParserContext getContext() {
    return context;
  }

  /**
   * Sets the position in the stream.
   *
   * @param position the new position
   */
  public void position(long position) {
    if (position < 0 || position > reader.length()) {
      throw outOfBounds("seek to " + position);
    }
    reader.position(position);
  }

  /**
   * Gets the current position in the stream.
   *
   * @return the current position
   */
  public long position() {
    return reader.position();
  }

  /**
   * Reads bytes into the specified buffer.
   *
   * @param buffer the destination buffer
   * @param offset the starting offset in the buffer
   * @param length the number of bytes to read
   */
  public void read(byte[] buffer, int offset, int length) {
    if (length < 0 || available() < length) {
      throw outOfBounds("read of " + length + " byte(s)");
    }
    reader.read(buffer, offset, length);
  }

  /**
   * Reads a single byte from the stream.
   *
   * @return the byte value
   */
  public byte read() {
    try {
      return reader.read();
    } catch (RuntimeException e) {
      throw translate(e, "read of a byte");
    }
  }

  /**
   * Reads a short value from the stream.
   *
   * @return the short value
   */
  public short readShort() {
    try {
      return reader.readShort();
    } catch (RuntimeException e) {
      throw translate(e, "read of a short");
    }
  }

  /**
   * Reads an int value from the stream.
   *
   * @return the int value
   */
  public int readInt() {
    try {
      return reader.readInt();
    } catch (RuntimeException e) {
      throw translate(e, "read of an int");
    }
  }

  /**
   * Reads a long value from the stream.
   *
   * @return the long value
   */
  public long readLong() {
    try {
      return reader.readLong();
    } catch (RuntimeException e) {
      throw translate(e, "read of a long");
    }
  }

  /**
   * Reads a float value from the stream.
   *
   * @return the float value
   */
  public float readFloat() {
    try {
      return reader.readFloat();
    } catch (RuntimeException e) {
      throw translate(e, "read of a float");
    }
  }

  /**
   * Reads a double value from the stream.
   *
   * @return the double value
   */
  public double readDouble() {
    try {
      return reader.readDouble();
    } catch (RuntimeException e) {
      throw translate(e, "read of a double");
    }
  }

  /**
   * Reads a variable-length integer from the stream.
   *
   * @return the long value
   */
  public long readVarint() {
    try {
      return reader.readVarint();
    } catch (RuntimeException e) {
      throw translate(e, "read of a varint");
    }
  }

  /**
   * Reads a boolean value from the stream.
   *
   * @return the boolean value
   */
  public boolean readBoolean() {
    try {
      return reader.readBoolean();
    } catch (RuntimeException e) {
      throw translate(e, "read of a boolean");
    }
  }

  public String readUTF8() throws IOException {
    return ParsingUtils.readUTF8(this, context.getStringTypeId());
  }

  /**
   * Gets the number of bytes available for reading.
   *
   * @return the number of available bytes
   */
  public long available() {
    return reader.remaining();
  }

  /**
   * Skips the specified number of bytes.
   *
   * @param bytes the number of bytes to skip
   */
  public void skip(int bytes) {
    if (bytes < 0 || bytes > reader.remaining()) {
      throw outOfBounds("skip of " + bytes + " byte(s)");
    }
    reader.skip(bytes);
  }

  /** Marks the current position for later reset. */
  public void mark() {
    mark = reader.position();
  }

  /** Resets the position to the previously marked position. */
  public void reset() {
    if (mark > -1) {
      position(mark);
      mark = -1;
    }
  }

  @Override
  public void close() {
    // Remove the current thread's slice from the cache to avoid retaining references after close.
    // Entries for other threads are not removable cross-thread; they are cleared when those
    // threads exit or when the GC processes their ThreadLocalMap entries.
    if (threadLocalStreamCache != null) {
      threadLocalStreamCache.remove();
    }
    try {
      reader.close();
    } catch (IOException ignored) {
    }
  }
}

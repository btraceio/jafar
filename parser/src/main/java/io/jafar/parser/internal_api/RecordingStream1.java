package io.jafar.parser.internal_api;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RecordingStream1 implements AutoCloseable {
  private final Path path;
  private final RandomAccessFile file;

  private final RecordingParserContext context;
  private long mark = -1;

  private final long offset;
  private final long length;

  private long position;

  private static final int PAGE_SIZE = 4 * 1024 * 1024;
  private final byte[] page = new byte[PAGE_SIZE + 10];
  private final ByteBuffer pageBuffer = ByteBuffer.wrap(page);
  private long currentPageAnchor;

  RecordingStream1(Path path) throws IOException {
    this(path, 0, Files.size(path), new RecordingParserContext());
  }

  public RecordingStream1 slice(long pos, long len, RecordingParserContext context) throws IOException {
    return new RecordingStream1(path, pos, len, context);
  }

  public RecordingStream1(Path path, long pos, long len, RecordingParserContext context) throws IOException {
    this.path = path;
    this.file = new RandomAccessFile(path.toFile(), "r");
    this.offset = pos;
    this.length = Math.min(len, file.length() - offset);
    this.file.seek(pos);
    this.context = context;

    this.position = 0;
    this.currentPageAnchor = 0;
    file.read(page, 0, (int)Math.min(len, page.length));
  }

  public RecordingParserContext getContext() {
    return context;
  }

  public void position(long position) throws IOException {
    this.position = position;
    long diff = position - currentPageAnchor;
    if (diff > PAGE_SIZE || diff < 0) {
      file.seek(offset + position);
      if (file.length() - (offset + position + Math.min(PAGE_SIZE, available())) < 0) {
        System.out.println("xxx");
      }
      file.read(page, 0, (int)Math.min(PAGE_SIZE, available()));
      pageBuffer.position(0);
      currentPageAnchor = position;
    } else {
      pageBuffer.position((int)diff);
    }
  }

  public long position() throws IOException {
    return position;
  }

  private void readPage() throws IOException {
    int diff = (int)(position - currentPageAnchor);
    if (diff > PAGE_SIZE) {
      file.read(page, 0, page.length);
      currentPageAnchor = position;
      pageBuffer.position(0);
    }
  }

  public void read(byte[] buffer, int offset, int length) throws IOException {
    try {
      if (available() < length) {
        throw new EOFException("unexpected EOF");
      }
      int toRead = length - offset;
      int targetDiff = (int)(position + toRead - currentPageAnchor);
      if (targetDiff > PAGE_SIZE) {
        int leftPart = toRead - targetDiff + PAGE_SIZE;
        pageBuffer.get(buffer, offset, leftPart);
        currentPageAnchor = position + leftPart;
        file.read(page, 0, PAGE_SIZE);
        pageBuffer.get(buffer, offset + leftPart, length - leftPart);
      } else {
        pageBuffer.get(buffer, offset, length);
      }
      position += length;

    } catch (BufferUnderflowException | BufferOverflowException e) {
      throw new IOException(e);
    }
  }

  public byte read() throws IOException {
    try {
      byte b = pageBuffer.get();
      position++;
      readPage();
      return b;
    } catch (BufferUnderflowException | BufferOverflowException e) {
      throw new IOException(e);
    }
  }

  public short readShort() throws IOException {
    try {
      short s = pageBuffer.getShort();
      position += 2;
      readPage();
      return s;
    } catch (BufferUnderflowException | BufferOverflowException e) {
      throw new IOException(e);
    }
  }

  public int readInt() throws IOException {
    try {
      int i = pageBuffer.getInt();
      position += 4;
      readPage();
      return i;
    } catch (BufferUnderflowException | BufferOverflowException e) {
      throw new IOException(e);
    }
  }

  public long readLong() throws IOException {
    try {
      long l = pageBuffer.getLong();
      position += 8;
      readPage();
      return l;
    } catch (BufferUnderflowException | BufferOverflowException e) {
      throw new IOException(e);
    }
  }

  public float readFloat() throws IOException {
    try {
      float f = pageBuffer.getFloat();
      position += 4;
      readPage();
      return f;
    } catch (BufferUnderflowException | BufferOverflowException e) {
      throw new IOException(e);
    }
  }

  public double readDouble() throws IOException {
    try {
      double d = pageBuffer.getDouble();
      position += 8;
      readPage();
      return d;
    } catch (BufferUnderflowException | BufferOverflowException e) {
      throw new IOException(e);
    }
  }

  public long readVarint() throws IOException {
    try {
      byte b0 = pageBuffer.get();
      position++;
      long ret = (b0 & 0x7FL);
      if (b0 >= 0) {
        return ret;
      }
      int b1 = pageBuffer.get();
      position++;
      ret += (b1 & 0x7FL) << 7;
      if (b1 >= 0) {
        return ret;
      }
      int b2 = pageBuffer.get();
      position++;
      ret += (b2 & 0x7FL) << 14;
      if (b2 >= 0) {
        return ret;
      }
      int b3 = pageBuffer.get();
      position++;
      ret += (b3 & 0x7FL) << 21;
      if (b3 >= 0) {
        return ret;
      }
      int b4 = pageBuffer.get();
      position++;
      ret += (b4 & 0x7FL) << 28;
      if (b4 >= 0) {
        return ret;
      }
      int b5 = pageBuffer.get();
      position++;
      ret += (b5 & 0x7FL) << 35;
      if (b5 >= 0) {
        return ret;
      }
      int b6 = pageBuffer.get();
      position++;
      ret += (b6 & 0x7FL) << 42;
      if (b6 >= 0) {
        return ret;
      }
      int b7 = pageBuffer.get();
      position++;
      ret += (b7 & 0x7FL) << 49;
      if (b7 >= 0) {
        return ret;
      }
      int b8 = pageBuffer.get();// read last byte raw
      position++;
      return ret + (((long) (b8 & 0XFF)) << 56);
    } finally {
      readPage();
    }
  }

  // TODO:
  // Theoretically one 'long' read should be more efficient than 8 byte reads
  // However, due to endianness diffrences this can be actually more expensive
  // Leaving the code here in case I want to re-test on a platform where the data does not have to be converted
  // due to endianness and maybe, if the benefit is significant, use a smart switch to use the best implementation
  // for a platform

//  public long readVarint1() throws IOException {
//    long pos = delegate.position();
//    long num = delegate.getLong();
//    try {
//      byte b0 = (byte)((num & 0xff00000000000000L) >> 56);
//      pos++;
//      long ret = (b0 & 0x7FL);
//      if (b0 >= 0) {
//        return ret;
//      }
//      pos++;
//      int b1 = (byte)((num & 0x00ff000000000000L) >> 48);
//      ret += (b1 & 0x7FL) << 7;
//      if (b1 >= 0) {
//        return ret;
//      }
//      pos++;
//      int b2 = (byte)((num & 0x0000ff0000000000L) >> 40);
//      ret += (b2 & 0x7FL) << 14;
//      if (b2 >= 0) {
//        return ret;
//      }
//      pos++;
//      int b3 = (byte)((num & 0x000000ff00000000L) >> 32);
//      ret += (b3 & 0x7FL) << 21;
//      if (b3 >= 0) {
//        return ret;
//      }
//      pos++;
//      int b4 = (byte)((num & 0x00000000ff000000L) >> 24);;
//      ret += (b4 & 0x7FL) << 28;
//      if (b4 >= 0) {
//        return ret;
//      }
//      pos++;
//      int b5 = (byte)((num & 0x0000000000ff0000L) >> 16);
//      ret += (b5 & 0x7FL) << 35;
//      if (b5 >= 0) {
//        return ret;
//      }
//      pos++;
//      int b6 = (byte)((num & 0x000000000000ff00L) >> 8);
//      ret += (b6 & 0x7FL) << 42;
//      if (b6 >= 0) {
//        return ret;
//      }
//      pos++;
//      int b7 = (byte)((num & 0x00000000000000ffL));;
//      ret += (b7 & 0x7FL) << 49;
//      if (b7 >= 0) {
//        return ret;
//      }
//      pos = -1;
//      int b8 = (byte)file.read();// read last byte raw
//      return ret + (((long) (b8 & 0XFF)) << 56);
//    } finally {
//      if (pos > -1) {
//        delegate.position(pos);
//      }
//    }
//  }

  public boolean readBoolean() throws IOException {
    boolean b = pageBuffer.get() == 1 ? true : false;
    position++;
    readPage();
    return b;
  }

  public long available() throws IOException {
    return length - position;
  }

  public void skip(int bytes) throws IOException {
    try {
      position(position + bytes);
    } catch (BufferUnderflowException | BufferOverflowException e) {
      throw new IOException(e);
    }
  }

  public void mark() throws IOException {
    mark = position;
    if (mark > length) {
      System.out.println("xxx");
    }
  }

  public void reset() throws IOException {
    if (mark > -1) {
      position(mark);
    }
  }

  @Override
  public void close() {
    try {
      file.close();
    } catch (IOException ignored) {}
  }
}

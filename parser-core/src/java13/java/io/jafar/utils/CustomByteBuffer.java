package io.jafar.utils;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

public interface CustomByteBuffer {
  static CustomByteBuffer map(Path channel) throws IOException {
    return map(channel, Integer.MAX_VALUE);
  }

  static CustomByteBuffer map(Path path, int spliceSize) throws IOException {
    long size = Files.size(path);
    if (size > spliceSize) {
      return new SplicedMappedByteBuffer(path, spliceSize);
    } else {
      try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
          FileChannel channel = raf.getChannel()) {
        return new ByteBufferWrapper(channel.map(FileChannel.MapMode.READ_ONLY, 0, size));
      }
    }
  }

  CustomByteBuffer slice();

  CustomByteBuffer slice(long pos, long len);

  CustomByteBuffer order(ByteOrder bigEndian);

  ByteOrder order();

  boolean isNativeOrder();

  void position(long position);

  long position();

  long remaining();

  long limit();

  void get(byte[] buffer, int offset, int length);

  byte get();

  short getShort();

  int getInt();

  float getFloat();

  double getDouble();

  void mark();

  void reset();

  long getLong();

  void close() throws IOException;

  class ByteBufferWrapper implements CustomByteBuffer {
    private final ByteBuffer delegate;
    private final boolean nativeOrder;

    public ByteBufferWrapper(ByteBuffer delegate) {
      this.delegate = delegate;
      this.nativeOrder = delegate.order() == ByteOrder.nativeOrder();
      delegate.order(ByteOrder.nativeOrder());
    }

    @Override
    public boolean isNativeOrder() {
      return nativeOrder;
    }

    @Override
    public CustomByteBuffer slice(long pos, long len) {
      ByteBuffer dup = delegate.duplicate();
      dup.position((int) pos);
      dup.limit((int) (pos + len));
      return new ByteBufferWrapper(dup.slice());
    }

    @Override
    public CustomByteBuffer slice() {
      ByteBuffer dup = delegate.duplicate();
      return new ByteBufferWrapper(dup.slice());
    }

    @Override
    public CustomByteBuffer order(ByteOrder order) {
      delegate.order(order);
      return this;
    }

    @Override
    public ByteOrder order() {
      return delegate.order();
    }

    @Override
    public void position(long position) {
      delegate.position((int) position);
    }

    @Override
    public long position() {
      return delegate.position();
    }

    @Override
    public long remaining() {
      return delegate.remaining();
    }

    @Override
    public void get(byte[] buffer, int offset, int length) {
      delegate.get(buffer, offset, length);
    }

    @Override
    public byte get() {
      return delegate.get();
    }

    @Override
    public short getShort() {
      return delegate.getShort();
    }

    @Override
    public int getInt() {
      return delegate.getInt();
    }

    @Override
    public float getFloat() {
      return delegate.getFloat();
    }

    @Override
    public double getDouble() {
      return delegate.getDouble();
    }

    @Override
    public void mark() {
      delegate.mark();
    }

    @Override
    public void reset() {
      delegate.reset();
    }

    @Override
    public long getLong() {
      return delegate.getLong();
    }

    @Override
    public long limit() {
      return delegate.limit();
    }

    @Override
    public void close() throws IOException {
      // ByteBuffer doesn't need explicit closing
    }
  }
}

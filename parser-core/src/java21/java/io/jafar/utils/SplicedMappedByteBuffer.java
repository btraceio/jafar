package io.jafar.utils;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SplicedMappedByteBuffer implements CustomByteBuffer {
  private final int spliceSize;
  private int index = 0;
  private int offset = 0;
  private long position = 0;
  private long mark = 0;
  private final long limit;
  private final long sliceBase;
  private final boolean nativeOrder;
  private final MappedByteBuffer[] splices;

  SplicedMappedByteBuffer(
      MappedByteBuffer[] splices,
      int spliceSize,
      int sliceOffset,
      int sliceIndex,
      long limit,
      boolean nativeOrder) {
    this.splices = splices;
    this.index = sliceIndex;
    this.offset = sliceOffset;
    this.spliceSize = spliceSize;
    this.limit = limit;
    this.sliceBase = (long) index * spliceSize + offset;
    this.nativeOrder = nativeOrder;
  }

  SplicedMappedByteBuffer(Path file, int spliceSize) throws IOException {
    this.sliceBase = 0;
    this.spliceSize = spliceSize;
    limit = Files.size(file);
    int count = (int) (((long) spliceSize + limit - 1) / spliceSize);
    splices = new MappedByteBuffer[count];
    boolean inOrder = true;
    try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");
        FileChannel channel = raf.getChannel()) {
      long remaining = limit;
      for (int i = 0; i < count; i++) {
        splices[i] =
            channel.map(
                FileChannel.MapMode.READ_ONLY,
                (long) i * spliceSize,
                (long) Math.min(spliceSize, remaining));
        inOrder &= splices[i].order() == ByteOrder.nativeOrder();
        splices[i].order(ByteOrder.nativeOrder());
        remaining -= spliceSize;
      }
      this.nativeOrder = inOrder;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public boolean isNativeOrder() {
    return nativeOrder;
  }

  @Override
  public CustomByteBuffer slice() {
    return new SplicedMappedByteBuffer(
        splices, spliceSize, offset, index, remaining(), nativeOrder);
  }

  @Override
  public CustomByteBuffer slice(long pos, long len) {
    if (pos + len > limit) {
      throw new BufferOverflowException();
    }
    int realIndex = (int) ((sliceBase + pos) / spliceSize);
    int realOffset = (int) ((sliceBase + pos) % spliceSize);
    return new SplicedMappedByteBuffer(
        splices, spliceSize, realOffset, realIndex, len, nativeOrder);
  }

  @Override
  public CustomByteBuffer order(ByteOrder order) {
    for (int i = 0; i < splices.length; i++) {
      splices[i] = (MappedByteBuffer) splices[i].order(order);
    }
    return this;
  }

  @Override
  public ByteOrder order() {
    return splices[0].order();
  }

  @Override
  public void position(long position) {
    if (position > limit) {
      throw new BufferOverflowException();
    }
    index = (int) ((position + sliceBase) / spliceSize);
    offset = (int) ((position + sliceBase) % spliceSize);
    this.position = position;
  }

  @Override
  public long position() {
    return position;
  }

  @Override
  public long remaining() {
    return limit - position;
  }

  private void checkSpliceOffset() {
    if (offset == spliceSize) {
      if (++index == splices.length) {
        throw new BufferOverflowException();
      }
      offset = 0;
      splices[index].position(offset);
    }
  }

  @Override
  public void get(byte[] buffer, int offset, int length) {
    int loaded = 0;
    do {
      checkSpliceOffset();
      int toLoad = (int) Math.min(spliceSize - this.offset, length - loaded);
      splices[index].get(this.offset, buffer, offset + loaded, toLoad);
      loaded += toLoad;
      this.offset += toLoad;
    } while (loaded < length);
    position += length;
  }

  @Override
  public byte get() {
    checkSpliceOffset();
    position++;
    return splices[index].get(offset++);
  }

  private final byte[] numArray = new byte[8];

  @Override
  public short getShort() {
    checkSpliceOffset();
    if (spliceSize - offset >= 2) {
      position += 2;
      short ret = splices[index].getShort(offset);
      offset += 2;
      return ret;
    } else {
      numArray[0] = get();
      numArray[1] = get();
      return ByteBuffer.wrap(numArray).order(splices[0].order()).getShort();
    }
  }

  @Override
  public int getInt() {
    checkSpliceOffset();
    if (spliceSize - offset >= 4) {
      position += 4;
      int ret = splices[index].getInt(offset);
      offset += 4;
      return ret;
    } else {
      int splitPoint = spliceSize - offset;
      get(numArray, 0, splitPoint);
      get(numArray, splitPoint, 4 - splitPoint);
      return ByteBuffer.wrap(numArray).order(splices[0].order()).getInt();
    }
  }

  @Override
  public float getFloat() {
    checkSpliceOffset();
    if (spliceSize - offset >= 4) {
      position += 4;
      float ret = splices[index].getFloat(offset);
      offset += 4;
      return ret;
    } else {
      int splitPoint = spliceSize - offset;
      get(numArray, 0, splitPoint);
      get(numArray, splitPoint, 4 - splitPoint);
      return ByteBuffer.wrap(numArray).order(splices[0].order()).getFloat();
    }
  }

  @Override
  public double getDouble() {
    checkSpliceOffset();
    if (spliceSize - offset >= 8) {
      position += 8;
      double ret = splices[index].getDouble(offset);
      offset += 8;
      return ret;
    } else {
      int splitPoint = spliceSize - offset;
      get(numArray, 0, splitPoint);
      get(numArray, splitPoint, 8 - splitPoint);
      return ByteBuffer.wrap(numArray).order(splices[0].order()).getDouble();
    }
  }

  @Override
  public void mark() {
    mark = position;
  }

  @Override
  public void reset() {
    position(mark);
  }

  @Override
  public long getLong() {
    checkSpliceOffset();
    if (spliceSize - offset >= 8) {
      position += 8;
      long ret = splices[index].getLong(offset);
      offset += 8;
      return ret;
    } else {
      int splitPoint = spliceSize - offset;
      get(numArray, 0, splitPoint);
      get(numArray, splitPoint, 8 - splitPoint);
      return ByteBuffer.wrap(numArray).order(splices[0].order()).getLong();
    }
  }

  @Override
  public long limit() {
    return limit;
  }

  @Override
  public void close() throws IOException {
    // MappedByteBuffers don't need explicit closing
  }
}

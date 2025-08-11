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

/**
 * Implementation of CustomByteBuffer that uses multiple memory-mapped file segments.
 * <p>
 * This class provides a byte buffer implementation that splits large files into
 * multiple memory-mapped segments (splices) for efficient memory usage. It handles
 * reading across splice boundaries automatically and maintains position tracking
 * across all segments.
 * </p>
 */
public final class SplicedMappedByteBuffer implements CustomByteBuffer {
    /** Size of each splice segment in bytes. */
    private final int spliceSize;
    
    /** Current splice index. */
    private int index = 0;
    
    /** Current offset within the current splice. */
    private int offset = 0;
    
    /** Current absolute position in the buffer. */
    private long position = 0;
    
    /** Marked position for reset operations. */
    private long mark = 0;
    
    /** Total size limit of the buffer. */
    private final long limit;
    
    /** Base offset for slice operations. */
    private final long sliceBase;
    
    /** Whether the buffer uses native byte order. */
    private final boolean nativeOrder;

    /** Array of memory-mapped byte buffer segments. */
    private final MappedByteBuffer[] splices;

    /**
     * Constructs a new SplicedMappedByteBuffer with the specified parameters.
     * 
     * @param splices the array of memory-mapped byte buffers
     * @param spliceSize the size of each splice segment
     * @param sliceOffset the offset within the slice
     * @param sliceIndex the starting splice index
     * @param limit the total size limit
     * @param nativeOrder whether to use native byte order
     */
    SplicedMappedByteBuffer(MappedByteBuffer[] splices, int spliceSize, int sliceOffset, int sliceIndex, long limit, boolean nativeOrder) {
        this.splices = splices;
        this.index = sliceIndex;
        this.offset = sliceOffset;
        this.spliceSize = spliceSize;
        this.limit = limit;
        this.sliceBase = (long)index * spliceSize + offset;
        this.nativeOrder = nativeOrder;
    }

    /**
     * Constructs a new SplicedMappedByteBuffer for the specified file.
     * 
     * @param file the file to memory-map
     * @param spliceSize the size of each splice segment
     * @throws IOException if an I/O error occurs during file mapping
     */
    SplicedMappedByteBuffer(Path file, int spliceSize) throws IOException {
        this.sliceBase = 0;
        this.spliceSize = spliceSize;
        limit = Files.size(file);
        int count = (int)(((long)spliceSize + limit - 1) / spliceSize);
        splices = new MappedByteBuffer[count];
        boolean inOrder = true;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");
            FileChannel channel = raf.getChannel()) {
            long remaining = limit;
            for (int i = 0; i  < count; i++) {
                splices[i] = channel.map(FileChannel.MapMode.READ_ONLY, (long)i * spliceSize, (long)Math.min(spliceSize, remaining));
                inOrder &= splices[i].order() == ByteOrder.nativeOrder();
                splices[i].order(ByteOrder.nativeOrder()); // force native order
                remaining -= spliceSize;
            }
            this.nativeOrder = inOrder;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isNativeOrder() {
        return nativeOrder;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CustomByteBuffer slice() {
        return new SplicedMappedByteBuffer(splices, spliceSize, offset, index, remaining(), nativeOrder);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CustomByteBuffer slice(long pos, long len) {
        if (pos + len > limit) {
            throw new BufferOverflowException();
        }
        int realIndex = (int)((sliceBase + pos) / spliceSize);
        int realOffset = (int)((sliceBase + pos) % spliceSize);
        return new SplicedMappedByteBuffer(splices, spliceSize, realOffset, realIndex, len, nativeOrder);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CustomByteBuffer order(ByteOrder order) {
        for (int i = 0; i < splices.length; i++) {
            splices[i] = (MappedByteBuffer) splices[i].order(order);
        }
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ByteOrder order() {
        return splices[0].order();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void position(long position) {
        if (position > limit) {
            throw new BufferOverflowException();
        }
        index = (int)((position + sliceBase) / spliceSize);
        offset = (int)((position + sliceBase) % spliceSize);
        this.position = position;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long position() {
        return position;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long remaining() {
        return limit - position;
    }

    /**
     * Checks and updates splice offset, advancing to the next splice if necessary.
     * 
     * @throws BufferOverflowException if attempting to read beyond available splices
     */
    private void checkSpliceOffset() {
        if (offset == spliceSize) {
            if (++index == splices.length) {
                throw new BufferOverflowException();
            }
            offset = 0;
            splices[index].position(offset);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void get(byte[] buffer, int offset, int length) {
        int loaded = 0;
        do {
            checkSpliceOffset();
            int toLoad = (int)Math.min(spliceSize - this.offset, length - loaded);
            // For Java 8 compatibility, manually copy bytes instead of using get(int, byte[], int, int)
            for (int i = 0; i < toLoad; i++) {
                buffer[offset + loaded + i] = splices[index].get(this.offset + i);
            }
            loaded += toLoad;
            this.offset += toLoad;
        } while (loaded < length);
        position += length;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte get() {
        checkSpliceOffset();
        position++;
        return splices[index].get(offset++);
    }

    /** Temporary array for reading multi-byte values across splice boundaries. */
    private final byte[] numArray = new byte[8];

    /**
     * {@inheritDoc}
     */
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

    /**
     * {@inheritDoc}
     */
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

    /**
     * {@inheritDoc}
     */
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

    /**
     * {@inheritDoc}
     */
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

    /**
     * {@inheritDoc}
     */
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

    /**
     * {@inheritDoc}
     */
    @Override
    public void mark() {
        mark = position;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reset() {
        position = mark;
        index = (int)((position + sliceBase) / spliceSize);
        offset = (int)((position + sliceBase) % spliceSize);
    }
}

package io.jafar.parser.internal_api;

import io.jafar.parser.api.ParserContext;

import java.io.IOException;
import java.nio.file.Path;

public final class RecordingStream implements AutoCloseable {
  private final RecordingStreamReader reader;

  private final ParserContext context;
  private long mark = -1;


  RecordingStream(Path path, ParserContext context) throws IOException {
    this(RecordingStreamReader.mapped(path), context);
  }

  public RecordingStream slice(long pos, long len, ParserContext context) {
    return new RecordingStream(reader.slice(pos, len), context);
  }

  public RecordingStream(RecordingStreamReader reader, ParserContext context) {
    this.reader = reader;
    this.context = context;
    this.context.put(RecordingStream.class, this);
  }

  public ParserContext getContext() {
    return context;
  }

  public void position(long position) {
    reader.position(position);
  }

  public long position() {
    return reader.position();
  }

  public void read(byte[] buffer, int offset, int length) {
    if (available() < length) {
      throw new RuntimeException("unexpected EOF");
    }
    reader.read(buffer, offset, length);
  }

  public byte read() {
    return reader.read();
  }

  public short readShort() {
    return reader.readShort();
  }

  public int readInt() {
    return reader.readInt();
  }

  public long readLong() {
    return reader.readLong();
  }

  public float readFloat() {
    return reader.readFloat();
  }

  public double readDouble()  {
    return reader.readDouble();
  }

  public long readVarint() {
    return reader.readVarint();
  }
  
  public boolean readBoolean() {
    return reader.readBoolean();
  }

  public long available() {
    return reader.remaining();
  }

  public void skip(int bytes) {
    reader.skip(bytes);
  }

  public void mark() {
    mark = reader.position();
  }

  public void reset() {
    if (mark > -1) {
      position(mark);
      mark = -1;
    }
  }

  @Override
  public void close() {
    try {
      reader.close();
    } catch (IOException ignored) {}
  }
}

package io.jafar.parser.internal_api;

import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.metadata.MetadataEvent;

/**
 * Streaming, almost zero-allocation, JFR chunk parser implementation. <br>
 * This is an MVP of a chunk parser allowing to stream the JFR events efficiently. The parser
 * notifies its listeners as the data becomes available. Because of this it is possible for the
 * metadata events to come 'out-of-band' (although not very probable) and it is up to the caller to
 * deal with that eventuality. <br>
 */
public final class StreamingChunkParser implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(StreamingChunkParser.class);

  private final ExecutorService executor = Executors.newFixedThreadPool(
          Math.max(Runtime.getRuntime().availableProcessors() - 2, 1),
          r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
          });

  private boolean closed = false;
  private final ParserContextFactory contextFactory;

  /**
   * Constructs a new StreamingChunkParser with the specified context factory.
   * 
   * @param contextFactory the factory for creating parser contexts
   */
  public StreamingChunkParser(ParserContextFactory contextFactory) {
    this.contextFactory = contextFactory;
  }

  /**
   * Parse the given JFR recording stream.<br>
   * The parser will process the recording stream and call the provided listener in this order:
   *
   * <ol>
   *   <li>listener.onRecordingStart()</li>
   *   <li>listener.onChunkStart()</li>
   *   <li>listener.onMetadata()</li>
   *   <li>listener.onCheckpoint()</li>
   *   <li>listener.onEvent()</li>
   *   <li>listener.onChunkEnd()</li>
   *   <li>listener.onRecordingEnd()</li>
   * </ol>
   *
   * @param path the JFR recording path
   * @param listener the parser listener
   * @throws IOException if an I/O error occurs during parsing
   */
  public void parse(Path path, ChunkParserListener listener) throws IOException {
    if (closed) {
      throw new IOException("Parser is closed");
    }
    try (RecordingStream stream = new RecordingStream(path, contextFactory.newContext())) {
      parse(stream, listener);
    }
  }

  @Override
  public void close() throws Exception {
    if (!closed) {
      closed = true;
      executor.shutdown();
    }
  }

  private Future<Boolean> submitParsingTask(ChunkHeader chunkHeader, RecordingStream chunkStream, ChunkParserListener listener, long remainder) {
    return executor.submit(() -> {
      int chunkCounter = chunkHeader.order;
      ParserContext chunkContext = chunkStream.getContext();
      try {
        if (!listener.onChunkStart(chunkContext, chunkCounter, chunkHeader)) {
          log.debug(
                  "'onChunkStart' returned false. Skipping metadata and events for chunk {}",
                  chunkCounter);
          listener.onChunkEnd(chunkContext, chunkCounter, true);
          return true;
        }
        // read metadata
        if (!readMetadata(chunkStream, chunkHeader, listener, false)) {
          log.debug(
                  "'onMetadata' returned false. Skipping events for chunk {}", chunkCounter);
          listener.onChunkEnd(chunkContext, chunkCounter, true);
          return false;
        }
        if (!readConstantPool(chunkStream, chunkHeader, listener)) {
          log.debug(
                  "'onCheckpoint' returned false. Skipping the rest of the chunk {}", chunkCounter);
          listener.onChunkEnd(chunkContext, chunkCounter, true);
          return false;
        }
        chunkStream.position(remainder);
        while (chunkStream.position() < chunkHeader.size) {
          long eventStartPos = chunkStream.position();
          chunkStream.mark(); // max 2 varints ahead
          int eventSize = (int) chunkStream.readVarint();
          if (eventSize > 0) {
            long eventType = chunkStream.readVarint();
            if (eventType > 1) { // skip metadata and checkpoint events
              long currentPos = chunkStream.position();
              if (!listener.onEvent(chunkContext, eventType, eventStartPos, eventSize, eventSize - (currentPos - eventStartPos))) {
                log.debug(
                        "'onEvent({}, stream, {})' returned false. Skipping the rest of the chunk {}",
                        eventType,
                        eventSize - (currentPos - eventStartPos),
                        chunkCounter);
                listener.onChunkEnd(chunkContext, chunkCounter, true);
                return false;
              }
            }
            // always skip any unconsumed event data to get the stream into consistent state
            chunkStream.position(eventStartPos + eventSize);
          }
        }
        return listener.onChunkEnd(chunkContext, chunkCounter, false);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
  }

  private void parse(RecordingStream stream, ChunkParserListener listener) throws IOException {
    if (stream.available() == 0) {
      return;
    }
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      listener.onRecordingStart(stream.getContext());
      int chunkCounter = 1;
      while (stream.available() > 0) {
        ChunkHeader header = new ChunkHeader(stream, chunkCounter);
        long remainder = (stream.position() - header.offset);

        RecordingStream chunkStream = stream.slice(header.offset, header.size, contextFactory.newContext(stream.getContext(), chunkCounter));
        stream.position(header.offset + header.size);

        results.add(submitParsingTask(header, chunkStream, listener, remainder));
        chunkCounter++;
      }
      results.forEach(f -> {
        try {
          f.get();
        } catch (Throwable t) {
          throw new RuntimeException("Failed to process chunk", t);
        }
      });
    } catch(EOFException e) {
      throw new IOException("Invalid buffer encountered during parsing", e);
    } catch (Throwable t) {
      throw new IOException("Error occurred while parsing JFR recording", t);
    } finally {
      listener.onRecordingEnd(stream.getContext());
    }
  }

  private boolean readMetadata(RecordingStream stream, ChunkHeader header, ChunkParserListener listener, boolean forceConstantPools) throws IOException {
    stream.mark();
    stream.position(header.metaOffset);
    MetadataEvent m = new MetadataEvent(stream, forceConstantPools);
    if (!listener.onMetadata(stream.getContext(), m)) {
      return false;
    }
    stream.getContext().onMetadataReady();
    stream.reset();
    return true;
  }

  private boolean readConstantPool(RecordingStream stream, ChunkHeader header, ChunkParserListener listener) throws IOException {
    return readConstantPool(stream, header.cpOffset, listener);
  }

  private boolean readConstantPool(RecordingStream stream, int position, ChunkParserListener listener) throws IOException {
    while (true) {
      stream.position(position);
      CheckpointEvent event = new CheckpointEvent(stream);
      event.readConstantPools();
      if (!listener.onCheckpoint(stream.getContext(), event)) {
        return false;
      }
      int delta = event.nextOffsetDelta;
      if (delta != 0) {
        position += delta;
      } else {
        break;
      }
    }
    stream.getContext().onConstantPoolsReady();
    return true;
  }
}

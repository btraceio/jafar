package io.jafar.parser.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.Control;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.CheckpointEvent;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Once a parse is aborted, a chunk that has already passed {@code onChunkStart} stops at its next
 * metadata or checkpoint callback instead of binding metadata and reading the rest of its constant
 * pool chain.
 *
 * <p>The window between a chunk's {@code onChunkStart} and its {@code onMetadata} cannot be hit
 * deterministically through a real parse, so the listener callbacks are driven directly and the
 * shared abort flag is set the way {@link Control#abort()} sets it.
 */
public class EventStreamAbortCallbacksTest {

  /** Counts the callbacks that reach the delegate. */
  private static final class CountingDelegate implements ChunkParserListener {
    final AtomicInteger metadata = new AtomicInteger();
    final AtomicInteger checkpoints = new AtomicInteger();

    @Override
    public boolean onMetadata(ParserContext context, MetadataEvent m) {
      metadata.incrementAndGet();
      return true;
    }

    @Override
    public boolean onCheckpoint(ParserContext context, CheckpointEvent c) {
      checkpoints.incrementAndGet();
      return true;
    }
  }

  private static ChunkParserListener create(String kind, ChunkParserListener delegate) {
    switch (kind) {
      case "EventStream":
        return new EventStream(delegate) {
          @Override
          protected void onEventValue(MetadataClass t, Map<String, Object> v, Control c) {}
        };
      case "EventStreamGenerated":
        return new EventStreamGenerated(delegate) {
          @Override
          protected void onEventValue(MetadataClass t, Map<String, Object> v, Control c) {}
        };
      case "EventStreamLazy":
        return new EventStreamLazy(delegate) {
          @Override
          protected void onEventValue(MetadataClass t, Map<String, Object> v, Control c) {}
        };
      case "EventStreamBaseline":
        return new EventStreamBaseline(delegate) {
          @Override
          protected void onEventValue(MetadataClass t, Map<String, Object> v, Control c) {}
        };
      default:
        throw new IllegalArgumentException(kind);
    }
  }

  /** Sets the listener's shared abort flag - the field every ControlImpl of the parse shares. */
  private static void abort(ChunkParserListener listener) throws Exception {
    Class<?> c = listener.getClass().getSuperclass();
    Field f = c.getDeclaredField("aborted");
    f.setAccessible(true);
    ((AtomicBoolean) f.get(listener)).set(true);
  }

  @ParameterizedTest(name = "{displayName}[{0}]")
  @ValueSource(
      strings = {"EventStream", "EventStreamGenerated", "EventStreamLazy", "EventStreamBaseline"})
  void metadataAndCheckpointsProceedWhenNotAborted(String kind) {
    CountingDelegate delegate = new CountingDelegate();
    ChunkParserListener listener = create(kind, delegate);
    assertTrue(listener.onMetadata(null, null));
    assertTrue(listener.onCheckpoint(null, null));
    assertEquals(1, delegate.metadata.get());
    assertEquals(1, delegate.checkpoints.get());
  }

  @ParameterizedTest(name = "{displayName}[{0}]")
  @ValueSource(
      strings = {"EventStream", "EventStreamGenerated", "EventStreamLazy", "EventStreamBaseline"})
  void metadataAndCheckpointsStopAfterAbort(String kind) throws Exception {
    CountingDelegate delegate = new CountingDelegate();
    ChunkParserListener listener = create(kind, delegate);
    abort(listener);
    assertFalse(listener.onMetadata(null, null), "onMetadata must skip the chunk after abort");
    assertFalse(listener.onCheckpoint(null, null), "onCheckpoint must stop the chain after abort");
    assertEquals(0, delegate.metadata.get(), "delegate saw metadata after abort");
    assertEquals(0, delegate.checkpoints.get(), "delegate saw a checkpoint after abort");
  }
}

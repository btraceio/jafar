package io.jafar.parser.internal_api;

import io.jafar.parser.AbstractEvent;
import io.jafar.parser.TypeFilter;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.io.IOException;

/**
 * Represents a checkpoint event in JFR recordings.
 *
 * <p>Checkpoint events mark important points in the recording timeline and contain information
 * about constant pools and other metadata that may be referenced by subsequent events.
 */
public final class CheckpointEvent extends AbstractEvent {

  /** The start time of the checkpoint in nanoseconds. */
  public final long startTime;

  /** The duration of the checkpoint in nanoseconds. */
  public final long duration;

  /** The offset delta to the next checkpoint. */
  public final int nextOffsetDelta;

  /** Whether this checkpoint represents a flush operation. */
  public final boolean isFlush;

  private final RecordingStream stream;

  /** Offset of this checkpoint event in the chunk; its size field is the first thing there. */
  private final long start;

  /** Size of this checkpoint event in bytes, including the size field itself. */
  private final int size;

  /** Bounds the workaround for the JMC writer emitting zero type ids in checkpoints. */
  private static final int MAX_ZERO_TYPE_IDS = 1024;

  /**
   * Constructs a new CheckpointEvent from the recording stream.
   *
   * @param stream the recording stream to read from
   * @throws IOException if an I/O error occurs during construction
   */
  CheckpointEvent(RecordingStream stream) throws IOException {
    super(stream);
    this.stream = stream;
    this.start = stream.position();
    long declaredSize = stream.readVarint();
    // The size is where readConstantPools() expects the pools to end; one that is not positive, or
    // that reaches past the chunk, can only come from a corrupted checkpoint (or a chain offset
    // that does not point at one).
    if (declaredSize <= 0 || declaredSize > stream.available() + (stream.position() - start)) {
      throw new IOException("Checkpoint at " + start + ": unexpected event size " + declaredSize);
    }
    this.size = (int) declaredSize;
    long typeId = stream.readVarint();
    if (typeId != 1) {
      throw new IOException("Unexpected event type: " + typeId + " (should be 1)");
    }
    this.startTime = stream.readVarint();
    this.duration = stream.readVarint();
    this.nextOffsetDelta = (int) stream.readVarint();
    this.isFlush = stream.read() != 0;
  }

  /**
   * Reads and processes constant pools from the recording stream.
   *
   * <p>This method processes all constant pool entries associated with this checkpoint, applying
   * type filtering and value processing as configured in the parser context.
   *
   * @throws IOException if an I/O error occurs during reading, or if the constant pools do not end
   *     exactly where the checkpoint event's size says the event ends
   */
  void readConstantPools() throws IOException {
    ParserContext context = stream.getContext();

    //    ConstantPoolValueProcessor cpProcessor = context.get(ConstantPoolValueProcessor.class);
    //    GenericValueReader vr = cpProcessor != null ? new GenericValueReader(cpProcessor) : null;
    //    cpProcessor = cpProcessor != null ? cpProcessor : ConstantPoolValueProcessor.NOOP;

    TypeFilter typeFilter = context.get(TypeFilter.class);

    boolean skipAll = context.getConstantPools().isReady();

    long end = start + size;
    long cpCount = stream.readVarint();
    // A pool takes at least two bytes (type id and entry count) and an entry at least one (its
    // id), so neither count can exceed the bytes left in this event. Unchecked, a corrupted count
    // sizes the pool's map and drives the loops below.
    if (cpCount < 0 || cpCount > end - stream.position()) {
      throw new IOException(
          "Checkpoint at " + start + ": implausible constant pool count " + cpCount);
    }
    for (long i = 0; i < cpCount; i++) {
      long typeId = 0;
      int zeroTypeIds = 0;
      while ((typeId = stream.readVarint()) == 0) {
        // workaround for a bug in JMC JFR writer - bounded, as in the Go parser, so that a run of
        // zero bytes is not consumed as one endless "type id"
        if (++zeroTypeIds > MAX_ZERO_TYPE_IDS) {
          throw new IOException(
              "Checkpoint at " + start + ": more than " + MAX_ZERO_TYPE_IDS + " zero type ids");
        }
      }
      long declaredCount = stream.readVarint();
      if (declaredCount < 0 || declaredCount > end - stream.position()) {
        throw new IOException(
            "Constant pool of type "
                + typeId
                + " declares an implausible entry count "
                + declaredCount);
      }
      int count = (int) declaredCount;
      try {
        MetadataClass clz = context.getMetadataLookup().getClass(typeId);
        if (clz == null) {
          // Without the type its entries cannot be skipped, so - as for an entry that cannot be
          // skipped - the rest of this checkpoint cannot be located. Abandoning it with a warning
          // left non-string references into it resolving to null (Verification.md R3).
          throw new IOException(
              "Constant pool of type "
                  + typeId
                  + " ("
                  + count
                  + " entries): the type is not declared in the chunk metadata");
        }
        boolean skip = skipAll || (typeFilter != null && !typeFilter.test(clz));

        MutableConstantPool constantPool =
            skip
                ? null
                : ((MutableConstantPools) context.getConstantPools())
                    .addOrGetConstantPool(stream, typeId, count);
        for (int j = 0; j < count; j++) {
          long id = stream.readVarint();
          if (!skip && !constantPool.containsKey(id)) {
            constantPool.addOffset(id, stream.position());
          }
          try {
            clz.skipDirect(stream);
          } catch (IOException e) {
            // Entries are not length-prefixed: past an entry that cannot be skipped, the offsets of
            // the rest of this checkpoint cannot be known, so the chunk cannot be read reliably.
            throw new IOException(
                "Constant pool " + clz.getName() + ", index " + id + ": " + e.getMessage(), e);
          }
        }
      } catch (IOException e) {
        throw e;
      }
    }
    // Entries are not length-prefixed, so a wrong length inside one (a corrupted string length
    // varint, say) still skips without error and indexes every later entry at the wrong offset.
    // The checkpoint's size is the only check that catches it. The JDK's ChunkParser fails here
    // too ("Size of check point event doesn't match content").
    if (stream.position() != end) {
      throw new IOException(
          "Checkpoint at "
              + start
              + ": constant pools end at "
              + stream.position()
              + ", but the event size "
              + size
              + " says it ends at "
              + end);
    }
  }
}

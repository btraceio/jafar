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

  /**
   * Constructs a new CheckpointEvent from the recording stream.
   *
   * @param stream the recording stream to read from
   * @throws IOException if an I/O error occurs during construction
   */
  CheckpointEvent(RecordingStream stream) throws IOException {
    super(stream);
    this.stream = stream;
    int size = (int) stream.readVarint();
    if (size == 0) {
      throw new IOException("Unexpected event size. Should be > 0");
    }
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
   * @throws IOException if an I/O error occurs during reading
   */
  void readConstantPools() throws IOException {
    ParserContext context = stream.getContext();

    //    ConstantPoolValueProcessor cpProcessor = context.get(ConstantPoolValueProcessor.class);
    //    GenericValueReader vr = cpProcessor != null ? new GenericValueReader(cpProcessor) : null;
    //    cpProcessor = cpProcessor != null ? cpProcessor : ConstantPoolValueProcessor.NOOP;

    TypeFilter typeFilter = context.get(TypeFilter.class);

    boolean skipAll = context.getConstantPools().isReady();

    long cpCount = stream.readVarint();
    for (long i = 0; i < cpCount; i++) {
      long typeId = 0;
      while ((typeId = stream.readVarint()) == 0)
        ; // workaround for a bug in JMC JFR writer
      int count = (int) stream.readVarint();
      try {
        MetadataClass clz = context.getMetadataLookup().getClass(typeId);
        if (clz == null) {
          // Unknown type - throw exception early
          throw new IOException("Constant pool references unknown type ID: " + typeId);
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
          clz.skipDirect(stream);
        }
      } catch (IOException e) {
        throw e;
      }
    }
  }
}

package io.jafar.parser.internal_api.metadata;

import io.jafar.parser.AbstractEvent;
import io.jafar.parser.ParsingUtils;
import io.jafar.parser.internal_api.MutableMetadataLookup;
import io.jafar.parser.internal_api.RecordingStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JFR Chunk metadata
 *
 * <p>It contains the chunk specific type specifications
 */
public final class MetadataEvent extends AbstractEvent {
  private static final Logger log = LoggerFactory.getLogger(MetadataEvent.class);

  private boolean hasHashCode = false;
  private int hashCode;

  /** The size of this metadata event in bytes. */
  public final int size;

  /** The start time of this metadata event in nanoseconds. */
  public final long startTime;

  /** The duration of this metadata event in nanoseconds. */
  public final long duration;

  /** The unique identifier for this metadata event. */
  public final long metadataId;

  private final MetadataRoot root;
  private final List<MetadataClass> classes = new ArrayList<>(200);

  private final boolean forceConstantPools;

  /** Upper bound on metadata element nesting; see {@link #readElement(RecordingStream)}. */
  static final int MAX_ELEMENT_DEPTH = 64;

  /** Current nesting depth of {@link #readElement(RecordingStream)}. */
  private int depth;

  /**
   * Constructs a new MetadataEvent from the recording stream.
   *
   * @param stream the recording stream to read from
   * @param forceConstantPools whether to force constant pool processing
   * @throws IOException if an I/O error occurs during construction
   */
  public MetadataEvent(RecordingStream stream, boolean forceConstantPools) throws IOException {
    super(stream);
    size = (int) stream.readVarint();
    if (size == 0) {
      throw new IOException("Unexpected event size. Should be > 0");
    }
    long typeId = stream.readVarint();
    if (typeId != 0) {
      throw new IOException("Unexpected event type: " + typeId + " (should be 0)");
    }
    startTime = stream.readVarint();
    duration = stream.readVarint();
    metadataId = stream.readVarint();
    this.forceConstantPools = forceConstantPools;

    readStringTable(stream);
    AbstractMetadataElement top = readElement(stream);
    if (!(top instanceof MetadataRoot)) {
      throw new IOException(
          "Metadata event: the top element is "
              + top.getKind()
              + ", expected "
              + MetadataElementKind.ROOT);
    }
    root = (MetadataRoot) top;
  }

  /**
   * Reads the string table from the recording stream.
   *
   * @param stream the recording stream to read from
   * @throws IOException if an I/O error occurs during reading
   */
  private void readStringTable(RecordingStream stream) throws IOException {
    long declared = stream.readVarint();
    // Every string takes at least its one-byte encoding id, so a count beyond the bytes that are
    // left cannot be honest - and must not size the table.
    if (declared < 0 || declared > stream.available()) {
      throw new IOException("Metadata event: implausible string table size " + declared);
    }
    int stringCnt = (int) declared;
    String[] stringConstants = new String[stringCnt];
    for (int stringIdx = 0; stringIdx < stringCnt; stringIdx++) {
      stringConstants[stringIdx] = stream.readUTF8();
    }
    ((MutableMetadataLookup) stream.getContext().getMetadataLookup())
        .setStringtable(stringConstants);
  }

  /**
   * Reads a metadata element from the recording stream.
   *
   * @param stream the recording stream to read from
   * @return the parsed metadata element
   * @throws IOException if an I/O error occurs during reading
   */
  AbstractMetadataElement readElement(RecordingStream stream) throws IOException {
    // Elements nest by recursion (an element's constructor reads its children through here), so the
    // nesting depth of the recording is the depth of this thread's stack. Real metadata is five
    // levels deep; the Go parser uses the same bound.
    if (depth >= MAX_ELEMENT_DEPTH) {
      throw new IOException("Metadata element nesting exceeds " + MAX_ELEMENT_DEPTH + " levels");
    }
    depth++;
    try {
      // get the element name
      int stringPtr = (int) stream.readVarint();
      String typeId =
          ParsingUtils.metadataString(stream.getContext().getMetadataLookup(), stringPtr);
      if (typeId == null) {
        // The string table may hold null strings (encoding 0); one cannot name an element.
        throw new IOException(
            "Metadata element whose name is a null string (index " + stringPtr + ")");
      }
      AbstractMetadataElement element = null;
      switch (typeId) {
        case "class":
          {
            MetadataClass clz = new MetadataClass(stream, this);
            classes.add(clz);
            element = clz;
            break;
          }
        case "field":
          {
            element = new MetadataField(stream, this, forceConstantPools);
            break;
          }
        case "annotation":
          {
            element = new MetadataAnnotation(stream, this);
            break;
          }
        case "root":
          {
            element = new MetadataRoot(stream, this);
            break;
          }
        case "metadata":
          {
            element = new MetadataElement(stream, this);
            break;
          }
        case "region":
          {
            element = new MetadataRegion(stream, this);
            break;
          }
        case "setting":
          {
            element = new MetadataSetting(stream, this);
            break;
          }
        default:
          {
            log.warn("Unknown metadata element type '{}', skipping", typeId);
            element = new UnknownMetadataElement(stream, this);
            break;
          }
      }

      return element;
    } catch (Throwable t) {
      // Logged once, by the outermost element: every enclosing element rethrows the same
      // exception, and logging it at each level printed one full stack trace per nesting level.
      if (depth == 1) {
        log.error("Failed to read metadata element", t);
      }
      throw t;
    } finally {
      depth--;
    }
  }

  /**
   * Gets the metadata root element.
   *
   * @return the metadata root element
   */
  public MetadataRoot getRoot() {
    return root;
  }

  /**
   * Gets an unmodifiable collection of all metadata classes.
   *
   * @return an unmodifiable collection of metadata classes
   */
  public Collection<MetadataClass> getClasses() {
    return Collections.unmodifiableCollection(classes);
  }

  @Override
  public String toString() {
    return "Metadata{"
        + "size="
        + size
        + ", startTime="
        + startTime
        + ", duration="
        + duration
        + ", metadataId="
        + metadataId
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    MetadataEvent that = (MetadataEvent) o;
    return size == that.size
        && startTime == that.startTime
        && duration == that.duration
        && metadataId == that.metadataId
        && forceConstantPools == that.forceConstantPools
        && Objects.equals(root, that.root)
        && Objects.equals(classes, that.classes);
  }

  @Override
  public int hashCode() {
    if (!hasHashCode) {
      hashCode =
          Objects.hash(size, startTime, duration, metadataId, root, classes, forceConstantPools);
      hasHashCode = true;
    }
    return hashCode;
  }
}

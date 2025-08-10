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

/**
 * JFR Chunk metadata
 *
 * <p>It contains the chunk specific type specifications
 */
public final class MetadataEvent extends AbstractEvent {
  private boolean hasHashCode = false;
  private int hashCode;

  public final int size;
  public final long startTime;
  public final long duration;
  public final long metadataId;
  private final MetadataRoot root;
  private final List<MetadataClass> classes = new ArrayList<>(200);

  private final boolean forceConstantPools;

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
    root = (MetadataRoot) readElement(stream);
  }

  private void readStringTable(RecordingStream stream) throws IOException {
    int stringCnt = (int) stream.readVarint();
    String[] stringConstants = new String[stringCnt];
    for (int stringIdx = 0; stringIdx < stringCnt; stringIdx++) {
      stringConstants[stringIdx] = ParsingUtils.readUTF8(stream);
    }
    ((MutableMetadataLookup)stream.getContext().getMetadataLookup()).setStringtable(stringConstants);
  }

  AbstractMetadataElement readElement(RecordingStream stream) throws IOException {
    try {
      // get the element name
      int stringPtr = (int) stream.readVarint();
      String typeId = stream.getContext().getMetadataLookup().getString(stringPtr);
      AbstractMetadataElement element = null;
      switch (typeId) {
        case "class": {
          MetadataClass clz = new MetadataClass(stream, this);
          classes.add(clz);
          element =  clz;
          break;
        }
        case "field": {
          element = new MetadataField(stream, this, forceConstantPools);
          break;
        }
        case "annotation": {
          element = new MetadataAnnotation(stream, this);
          break;
        }
        case "root": {
          element = new MetadataRoot(stream, this);
          break;
        }
        case "metadata": {
          element = new MetadataElement(stream, this);
          break;
        }
        case "region": {
          element = new MetadataRegion(stream, this);
          break;
        }
        case "setting": {
          element = new MetadataSetting(stream, this);
          break;
        }
        default: {
          throw new IOException("Unsupported metadata type: " + typeId);
        }
      };

      return element;
    } catch (Throwable t) {
      t.printStackTrace();
      throw t;
    }
  }

  public MetadataRoot getRoot() {
    return root;
  }

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
    return size == that.size && startTime == that.startTime && duration == that.duration && metadataId == that.metadataId && forceConstantPools == that.forceConstantPools && Objects.equals(root, that.root) && Objects.equals(classes, that.classes);
  }

  @Override
  public int hashCode() {
    if (!hasHashCode) {
      hashCode = Objects.hash(size, startTime, duration, metadataId, root, classes, forceConstantPools);
      hasHashCode = true;
    }
    return hashCode;
  }
}

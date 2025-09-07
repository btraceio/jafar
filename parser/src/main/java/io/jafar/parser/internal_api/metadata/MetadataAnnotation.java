package io.jafar.parser.internal_api.metadata;

import io.jafar.parser.internal_api.RecordingStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a metadata annotation in JFR recordings.
 *
 * <p>This class extends AbstractMetadataElement to provide specific functionality for handling
 * annotation metadata, including nested annotations and values.
 */
public final class MetadataAnnotation extends AbstractMetadataElement {
  /** Flag indicating whether the hash code has been computed. */
  private boolean hasHashCode = false;

  /** Cached hash code value. */
  private int hashCode;

  /** List of nested annotations within this annotation. */
  private List<MetadataAnnotation> annotations = null;

  /** Cached class ID value. */
  private Long classId = null;

  /** Raw class ID string value. */
  private String classIdVal = null;

  /** The annotation value. */
  public String value;

  /**
   * Constructs a new MetadataAnnotation from the recording stream and event.
   *
   * @param stream the recording stream to read from
   * @param event the metadata event containing subelements
   * @throws IOException if an I/O error occurs during construction
   */
  MetadataAnnotation(RecordingStream stream, MetadataEvent event) throws IOException {
    super(stream, MetadataElementKind.ANNOTATION);
    readSubelements(event);
  }

  /**
   * Handles attributes encountered during parsing.
   *
   * @param key the attribute key
   * @param value the attribute value
   */
  @Override
  protected void onAttribute(String key, String value) {
    switch (key) {
      case "class":
        classIdVal = value;
        break;
      case "value":
        this.value = value;
        break;
    }
  }

  /**
   * Gets the metadata class type for this annotation.
   *
   * @return the metadata class type
   */
  public MetadataClass getType() {
    return metadataLookup.getClass(classId);
  }

  /**
   * Gets the class ID for this annotation.
   *
   * @return the class ID as a long value
   */
  public long getClassId() {
    if (classId == null) {
      classId = Long.parseLong(classIdVal);
    }
    return classId;
  }

  /**
   * Gets the annotation value.
   *
   * @return the annotation value string
   */
  public String getValue() {
    return value;
  }

  /**
   * Handles subelements encountered during parsing.
   *
   * @param count the total count of subelements
   * @param element the subelement that was read
   */
  @Override
  protected void onSubelement(int count, AbstractMetadataElement element) {
    if (annotations == null) {
      annotations = new ArrayList<>(count);
    }
    if (element.getKind() == MetadataElementKind.ANNOTATION) {
      annotations.add((MetadataAnnotation) element);
    } else {
      throw new IllegalStateException("Unexpected subelement: " + element.getKind());
    }
  }

  /**
   * Accepts a metadata visitor for processing this element.
   *
   * @param visitor the visitor to accept
   */
  @Override
  public void accept(MetadataVisitor visitor) {
    visitor.visitAnnotation(this);
    if (annotations != null) {
      annotations.forEach(a -> a.accept(visitor));
    }
    visitor.visitEnd(this);
  }

  @Override
  public String toString() {
    return "MetadataAnnotation{"
        + "type='"
        + (getType() != null ? getType().getName() : getClassId())
        + '\''
        + ", value='"
        + getValue()
        + '\''
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    MetadataAnnotation that = (MetadataAnnotation) o;
    return getClassId() == that.getClassId()
        && Objects.equals(annotations, that.annotations)
        && Objects.equals(getValue(), that.getValue());
  }

  @Override
  public int hashCode() {
    if (!hasHashCode) {
      long mixed =
          getClassId() * 0x9E3779B97F4A7C15L
              + Objects.hashCode(annotations) * 0xC6BC279692B5C323L
              + Objects.hashCode(getValue()) * 0xD8163841FDE6A8F9L;
      hashCode = Long.hashCode(mixed);
      hasHashCode = true;
    }
    return hashCode;
  }
}

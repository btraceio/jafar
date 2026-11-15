package io.jafar.parser.internal_api.metadata;

import io.jafar.parser.ParsingUtils;
import io.jafar.parser.internal_api.RecordingStream;
import io.jafar.parser.internal_api.TypeSkipper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a metadata field in JFR recordings.
 *
 * <p>This class extends AbstractMetadataElement to provide specific functionality for handling
 * field metadata, including type information, annotations, and array dimensions.
 */
public final class MetadataField extends AbstractMetadataElement {
  /** Flag indicating whether the hash code has been computed. */
  private boolean hasHashCode = false;

  /** Cached hash code value. */
  private int hashCode;

  /** List of annotations associated with this field. */
  private List<MetadataAnnotation> annotations = null;

  /** Cached class ID value. */
  private Long classId;

  /** Raw class ID string value. */
  private String classIdVal;

  /** Class type name for name-based fallback when class ID is not numeric. */
  private String classTypeName;

  /** Cached constant pool flag. */
  private Boolean hasConstantPool;

  /** Raw constant pool string value. */
  private String hasConstantPoolVal;

  /** Cached dimension value. */
  private Integer dimension;

  /** Raw dimension string value. */
  private String dimensionVal;

  /** Cached metadata class type. */
  private MetadataClass type = null;

  /** Lazily built by {@link #skipper()}; racy but idempotent, as the result is always equal. */
  private volatile TypeSkipper skipper;

  /**
   * Constructs a new MetadataField from the recording stream and event.
   *
   * @param stream the recording stream to read from
   * @param event the metadata event containing subelements
   * @param forceConstantPools whether to force constant pool processing
   * @throws IOException if an I/O error occurs during construction
   */
  MetadataField(RecordingStream stream, MetadataEvent event, boolean forceConstantPools)
      throws IOException {
    super(stream, MetadataElementKind.FIELD);
    readSubelements(event);
    // Parsed lazily and unchecked by getDimension(); a dimension that is not a number is a
    // property of the recording, so reject it here where it can be reported as one.
    if (dimensionVal != null) {
      try {
        dimension = Integer.parseInt(dimensionVal);
      } catch (NumberFormatException e) {
        throw new IOException(
            "Metadata field " + getName() + ": invalid dimension \"" + dimensionVal + "\"");
      }
    }
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
      case "constantPool":
        hasConstantPoolVal = value;
        break;
      case "dimension":
        dimensionVal = value;
        break;
    }
  }

  /**
   * Gets the metadata class type for this field.
   *
   * @return the metadata class type
   */
  public MetadataClass getType() {
    // all events from a single chunk, referencing a particular type will be procesed in a single
    // thread
    // therefore, we are not risiking data race here
    if (type == null) {
      if (classTypeName != null) {
        type = metadataLookup.getClass(classTypeName);
      } else {
        type = metadataLookup.getClass(getTypeId());
      }
    }
    return type;
  }

  /**
   * Gets the type ID for this field.
   *
   * @return the type ID as a long value, or -1 if the class attribute is not a numeric ID
   */
  public long getTypeId() {
    if (classId == null) {
      if (classIdVal == null) {
        // No "class" attribute at all: the type stays unresolved, and decoding or skipping a
        // value of it reports that.
        classId = -1L;
        return classId;
      }
      try {
        classId = ParsingUtils.parseLongSWAR(classIdVal);
      } catch (NumberFormatException e) {
        // Some JFR producers write the class name instead of a numeric type ID.
        classTypeName = classIdVal;
        classId = -1L;
      }
    }
    return classId;
  }

  /**
   * Checks if this field has an associated constant pool.
   *
   * @return true if the field has a constant pool, false otherwise
   */
  public boolean hasConstantPool() {
    if (hasConstantPool == null) {
      hasConstantPool = Boolean.parseBoolean(hasConstantPoolVal);
    }
    return hasConstantPool;
  }

  /**
   * Gets the array dimension for this field.
   *
   * @return the array dimension, or -1 if not an array
   */
  public int getDimension() {
    if (dimension == null) {
      dimension = dimensionVal != null ? Integer.parseInt(dimensionVal) : -1;
    }
    return dimension;
  }

  /**
   * Handles subelements encountered during parsing.
   *
   * @param count the total count of subelements
   * @param element the subelement that was read
   */
  @Override
  protected void onSubelement(int count, AbstractMetadataElement element) throws IOException {
    if (element.getKind() == MetadataElementKind.ANNOTATION) {
      if (annotations == null) {
        annotations = new ArrayList<>(count);
      }
      annotations.add((MetadataAnnotation) element);
    } else {
      throw unexpectedSubelement(element);
    }
  }

  /**
   * Accepts a metadata visitor for processing this element.
   *
   * @param visitor the visitor to accept
   */
  @Override
  public void accept(MetadataVisitor visitor) {
    visitor.visitField(this);
    if (annotations != null) {
      annotations.forEach(a -> a.accept(visitor));
    }
    visitor.visitEnd(this);
  }

  /**
   * Returns field-level JFR metadata annotations declared on this field.
   *
   * <p>The returned list is an unmodifiable snapshot; it may be empty when the field has no
   * annotations. Each entry provides access to the annotation type and value via {@link
   * MetadataAnnotation#getType()} and {@link MetadataAnnotation#getValue()}.
   *
   * @return an unmodifiable list of field annotations (possibly empty)
   */
  public List<MetadataAnnotation> getAnnotations() {
    return java.util.Collections.unmodifiableList(
        annotations == null ? java.util.Collections.emptyList() : annotations);
  }

  @Override
  public String toString() {
    return "MetadataField{"
        + "type='"
        + (getType() != null ? getType().getName() : getTypeId())
        + '\''
        + ", name='"
        + getName()
        + "'"
        + ", hasConstantPool="
        + hasConstantPool
        + ", dimension="
        + dimension
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    MetadataField that = (MetadataField) o;
    return getTypeId() == that.getTypeId()
        && hasConstantPool() == that.hasConstantPool()
        && getDimension() == that.getDimension()
        && Objects.equals(classTypeName, that.classTypeName);
  }

  @Override
  public int hashCode() {
    if (!hasHashCode) {
      long mixed =
          getTypeId() * 0x9E3779B97F4A7C15L
              + (hasConstantPool() ? 1 : 0) * 0xC6BC279692B5C323L
              + getDimension() * 0xD8163841FDE6A8F9L
              + Objects.hashCode(classTypeName) * 0xA3E09AD7D7352315L;
      hashCode = Long.hashCode(mixed);
      hasHashCode = true;
    }
    return hashCode;
  }

  /**
   * Returns the skipper for this field's value as it appears inside its owning type, built on first
   * use. Requires the field's type to be resolved.
   *
   * @return the skipper
   */
  public TypeSkipper skipper() {
    TypeSkipper s = skipper;
    if (s == null) {
      s = TypeSkipper.createFieldSkipper(this);
      skipper = s;
    }
    return s;
  }
}

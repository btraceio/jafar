package io.jafar.parser.internal_api.metadata;

import io.jafar.parser.impl.TypedParserContext;
import io.jafar.parser.internal_api.Deserializer;
import io.jafar.parser.internal_api.DeserializerCache;
import io.jafar.parser.internal_api.RecordingStream;
import io.jafar.parser.internal_api.TypeSkipper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Represents a metadata class in JFR recordings.
 *
 * <p>This class extends AbstractMetadataElement to provide specific functionality for handling
 * class metadata, including fields, annotations, settings, and deserialization.
 */
public final class MetadataClass extends AbstractMetadataElement {
  /** Flag indicating whether the hash code has been computed. */
  private boolean hasHashCode = false;

  /** Cached hash code value. */
  private int hashCode;

  /** Set of primitive type names. */
  private static final Set<String> primitiveTypeNames =
      new java.util.HashSet<String>(
          java.util.Arrays.asList(
              "byte",
              "char",
              "short",
              "int",
              "long",
              "float",
              "double",
              "boolean",
              "java.lang.String"));

  /** Map of settings associated with this class. */
  private Map<String, MetadataSetting> settings = null;

  /** List of annotations associated with this class. */
  private List<MetadataAnnotation> annotations = null;

  /** List of fields defined in this class. */
  private List<MetadataField> fields = null;

  /** The super type of this class. */
  private String superType;

  /** Cached primitive type flag. */
  private Boolean isPrimitive;

  /** Cached simple type flag. */
  private Boolean isSimpleType;

  /** Raw simple type string value. */
  private String simpleTypeVal;

  /** The chunk index associated with this class. */
  private final int associatedChunk;

  /** Atomic updater for the deserializer field. */
  @SuppressWarnings("rawtypes")
  private static final AtomicReferenceFieldUpdater<MetadataClass, Deserializer>
      DESERIALIZER_UPDATER =
          AtomicReferenceFieldUpdater.newUpdater(
              MetadataClass.class, Deserializer.class, "deserializer");

  private static final AtomicReferenceFieldUpdater<MetadataClass, TypeSkipper>
      TYPE_SKIPPER_UPDATER =
          AtomicReferenceFieldUpdater.newUpdater(
              MetadataClass.class, TypeSkipper.class, "typeSkipper");

  /** The deserializer associated with this class. */
  private volatile Deserializer<?> deserializer;

  private volatile TypeSkipper typeSkipper;

  /**
   * Constructs a new MetadataClass from the recording stream and event.
   *
   * @param stream the recording stream to read from
   * @param eventr the metadata event containing subelements
   * @throws IOException if an I/O error occurs during construction
   */
  MetadataClass(RecordingStream stream, MetadataEvent eventr) throws IOException {
    super(stream, MetadataElementKind.CLASS);
    this.associatedChunk = stream.getContext().getChunkIndex();
    readSubelements(eventr);
    metadataLookup.addClass(getId(), this);
  }

  /**
   * Handles attributes encountered during parsing.
   *
   * @param key the attribute key
   * @param value the attribute value
   */
  @Override
  protected void onAttribute(String key, String value) {
    if (key.equals("superType")) {
      superType = value;
    } else if (key.equals("simpleType")) {
      simpleTypeVal = value;
    }
  }

  /**
   * Binds a deserializer to this class.
   *
   * <p>This method ensures that a deserializer is available for this class, either by retrieving an
   * existing one or creating a new one.
   */
  public void bindDeserializer() {
    DESERIALIZER_UPDATER.updateAndGet(
        this,
        v ->
            (v == null)
                ? Optional.ofNullable(getContext().get(DeserializerCache.class))
                    .map(
                        c ->
                            c.computeIfAbsent(
                                new TypedParserContext.DeserializerKey(MetadataClass.this),
                                k -> Deserializer.forType(MetadataClass.this)))
                    .orElse(Deserializer.none())
                : v);
  }

  /**
   * Get the associated deserializer. Used in the generated handler classes.
   *
   * @return the associated deserializer
   */
  public Deserializer<?> getDeserializer() {
    Deserializer<?> ret = deserializer;
    if (ret == null) {
      bindDeserializer();
      ret = deserializer;
    }
    return ret == Deserializer.none() ? null : ret;
  }

  /**
   * Gets the super type of this class.
   *
   * @return the super type name, or null if none
   */
  public String getSuperType() {
    return superType;
  }

  /**
   * Checks if this class represents a primitive type.
   *
   * @return true if this is a primitive type, false otherwise
   */
  public boolean isPrimitive() {
    if (isPrimitive == null) {
      isPrimitive = primitiveTypeNames.contains(getName());
    }
    return isPrimitive;
  }

  /**
   * Checks if this class represents a simple type.
   *
   * @return true if this is a simple type, false otherwise
   */
  public boolean isSimpleType() {
    if (isSimpleType == null) {
      isSimpleType = Boolean.parseBoolean(simpleTypeVal);
    }
    return isSimpleType;
  }

  /**
   * Handles subelements encountered during parsing.
   *
   * @param count the total count of subelements
   * @param element the subelement that was read
   */
  protected void onSubelement(int count, AbstractMetadataElement element) {
    if (element.getKind() == MetadataElementKind.SETTING) {
      if (settings == null) {
        settings = new HashMap<>(count * 2, 0.5f);
      }
      MetadataSetting setting = (MetadataSetting) element;
      settings.put(setting.getName(), setting);
    } else if (element.getKind() == MetadataElementKind.ANNOTATION) {
      if (annotations == null) {
        annotations = new ArrayList<>(count);
      }
      annotations.add((MetadataAnnotation) element);
    } else if (element.getKind() == MetadataElementKind.FIELD) {
      if (fields == null) {
        fields = new ArrayList<>(count);
      }
      MetadataField field = (MetadataField) element;
      fields.add(field);
    } else {
      throw new IllegalStateException("Unexpected subelement: " + element.getKind());
    }
  }

  @Override
  public void accept(MetadataVisitor visitor) {
    visitor.visitClass(this);
    if (settings != null) {
      settings.values().forEach(s -> s.accept(visitor));
    }
    if (annotations != null) {
      annotations.forEach(a -> a.accept(visitor));
    }
    if (fields != null) {
      fields.forEach(f -> f.accept(visitor));
    }
    visitor.visitEnd(this);
  }

  /**
   * Gets the list of metadata fields for this class.
   *
   * @return an unmodifiable list of metadata fields
   */
  public List<MetadataField> getFields() {
    return Collections.unmodifiableList(fields == null ? Collections.emptyList() : fields);
  }

  /**
   * Skips over the data for this class in the recording stream.
   *
   * @param stream the recording stream to skip over
   * @throws IOException if an I/O error occurs during skipping
   */
  public void skip(RecordingStream stream) throws IOException {
    Deserializer<?> d = getDeserializer(); // lazily initialize deserializer
    if (d == null) {
      // no deserializer; use type skipper
      TypeSkipper skipper =
          TYPE_SKIPPER_UPDATER.updateAndGet(
              this, ts -> ts == null ? TypeSkipper.createSkipper(this) : ts);
      skipper.skip(stream);
    } else {
      try {
        d.skip(stream);
      } catch (Exception e) {
        throw new IOException(e);
      }
    }
  }

  /**
   * Reads and deserializes data for this class from the recording stream.
   *
   * @param <T> the expected type of the deserialized object
   * @param stream the recording stream to read from
   * @return the deserialized object, or null if no deserializer is available
   * @throws RuntimeException if deserialization fails
   */
  @SuppressWarnings("unchecked")
  public <T> T read(RecordingStream stream) {
    if (deserializer == null) {
      return null;
    }
    try {
      return (T) deserializer.deserialize(stream);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static boolean isPrimitive(String typeName) {
    return typeName.equals("byte")
        || typeName.equals("short")
        || typeName.equals("char")
        || typeName.equals("int")
        || typeName.equals("long")
        || typeName.equals("float")
        || typeName.equals("double")
        || typeName.equals("boolean")
        || typeName.equals("java.lang.String");
  }

  @Override
  public String toString() {
    return "MetadataClass{"
        + "id='"
        + getId()
        + '\''
        + ", chunk="
        + associatedChunk
        + ", name='"
        + getName()
        + "'"
        + ", superType='"
        + superType
        + '\''
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    MetadataClass that = (MetadataClass) o;
    return getId() == that.getId()
        && Objects.equals(getName(), that.getName())
        && Objects.equals(superType, that.superType)
        && Objects.equals(fields, that.fields);
  }

  @Override
  public int hashCode() {
    if (!hasHashCode) {
      long mixed =
          getId() * 0x9E3779B97F4A7C15L
              + getName().hashCode() * 0xC6BC279692B5C323L
              + Objects.hashCode(superType) * 0xD8163841FDE6A8F9L
              + Objects.hashCode(fields) * 0xA3B195354A39B70DL;
      hashCode = Long.hashCode(mixed);
      hasHashCode = true;
    }
    return hashCode;
  }
}

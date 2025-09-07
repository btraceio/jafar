package io.jafar.parser.internal_api.metadata;

import io.jafar.parser.impl.TypedParserContext;
import io.jafar.parser.internal_api.Deserializer;
import io.jafar.parser.internal_api.DeserializerCache;
import io.jafar.parser.internal_api.RecordingStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public final class MetadataClass extends AbstractMetadataElement {
  private boolean hasHashCode = false;
  private int hashCode;

  private static final Set<String> primitiveTypeNames =
      Set.of(
          "byte", "char", "short", "int", "long", "float", "double", "boolean", "java.lang.String");

  private Map<String, MetadataSetting> settings = null;
  private List<MetadataAnnotation> annotations = null;
  private List<MetadataField> fields = null;
  private String superType;
  private Boolean isPrimitive;
  private Boolean isSimpleType;
  private String simpleTypeVal;
  private final int associatedChunk;

  @SuppressWarnings("rawtypes")
  private static final AtomicReferenceFieldUpdater<MetadataClass, Deserializer>
      DESERIALIZER_UPDATER =
          AtomicReferenceFieldUpdater.newUpdater(
              MetadataClass.class, Deserializer.class, "deserializer");

  private volatile Deserializer<?> deserializer;

  MetadataClass(RecordingStream stream, MetadataEvent eventr) throws IOException {
    super(stream, MetadataElementKind.CLASS);
    this.associatedChunk = stream.getContext().getChunkIndex();
    readSubelements(eventr);
    metadataLookup.addClass(getId(), this);
  }

  @Override
  protected void onAttribute(String key, String value) {
    if (key.equals("superType")) {
      superType = value;
    } else if (key.equals("simpleType")) {
      simpleTypeVal = value;
    }
  }

  public void bindDeserializer() {
    DESERIALIZER_UPDATER.updateAndGet(
        this,
        v ->
            (v == null)
                ? getContext()
                    .get(DeserializerCache.class)
                    .computeIfAbsent(
                        new TypedParserContext.DeserializerKey(MetadataClass.this),
                        k -> Deserializer.forType(MetadataClass.this))
                : v);
  }

  public Deserializer<?> getDeserializer() {
    Deserializer<?> ret = deserializer;
    if (ret == null) {
      bindDeserializer();
      ret = deserializer;
    }
    return ret;
  }

  public String getSuperType() {
    return superType;
  }

  public boolean isEvent() {
    return getSuperType() != null && "jdk.jfr.Event".equals(getSuperType());
  }

  public boolean isAnnotation() {
    return getSuperType() != null && "java.lang.annotation.Annotation".equals(getSuperType());
  }

  public boolean isSettingControl() {
    return getSuperType() != null && "jdk.jfr.SettingControl".equals(getSuperType());
  }

  public boolean isPrimitive() {
    if (isPrimitive == null) {
      isPrimitive = primitiveTypeNames.contains(getName());
    }
    return isPrimitive;
  }

  public boolean isSimpleType() {
    if (isSimpleType == null) {
      isSimpleType = Objects.equals("true", simpleTypeVal);
    }
    return isSimpleType;
  }

  public List<MetadataAnnotation> getAnnotations() {
    if (annotations == null) annotations = Collections.emptyList();
    return annotations;
  }

  public List<MetadataField> getFields() {
    if (fields == null) fields = Collections.emptyList();
    return fields;
  }

  public Map<String, MetadataSetting> getSettings() {
    if (settings == null) settings = Collections.emptyMap();
    return settings;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    MetadataClass that = (MetadataClass) o;
    return getId() == that.getId();
  }

  @Override
  public int hashCode() {
    if (!hasHashCode) {
      hashCode = Objects.hash(getId());
      hasHashCode = true;
    }
    return hashCode;
  }

  @Override
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
}

package io.jafar.parser.internal_api.metadata;

import io.jafar.parser.internal_api.Deserializer;
import io.jafar.parser.internal_api.DeserializerCache;
import io.jafar.parser.internal_api.RecordingParserContext;
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

    private static final Set<String> primitiveTypeNames = Set.of("byte", "char", "short", "int", "long", "float", "double", "boolean", "java.lang.String");

    private Map<String, MetadataSetting> settings = null;
    private List<MetadataAnnotation> annotations = null;
    private List<MetadataField> fields = null;

    private String superType;
    private Boolean isPrimitive;
    private Boolean isSimpleType;
    private String simpleTypeVal;

    private final int associatedChunk;

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<MetadataClass, Deserializer> DESERIALIZER_UPDATER = AtomicReferenceFieldUpdater.newUpdater(MetadataClass.class, Deserializer.class, "deserializer");
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
        DESERIALIZER_UPDATER.updateAndGet(this, v -> (v == null) ? getContext().get(DeserializerCache.class).computeIfAbsent(new RecordingParserContext.DeserializerKey(MetadataClass.this), k -> Deserializer.forType(MetadataClass.this)) : v);
    }

    /**
     * Get the associated deserializer.
     * Used in the generated handler classes.
     * @return the associated deserializer
     */
    public Deserializer<?> getDeserializer() {
        return deserializer;
    }

    public String getSuperType() {
        return superType;
    }

    public boolean isPrimitive() {
        if (isPrimitive == null) {
            isPrimitive = primitiveTypeNames.contains(getName());
        }
        return isPrimitive;
    }

    public boolean isSimpleType() {
        if (isSimpleType == null) {
            isSimpleType = Boolean.parseBoolean(simpleTypeVal);
        }
        return isSimpleType;
    }

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

    public List<MetadataField> getFields() {
        return Collections.unmodifiableList(fields == null ? Collections.emptyList() : fields);
    }

    public void skip(RecordingStream stream) throws IOException {
        if (deserializer == null) {
            return;
        }
        try {
            deserializer.skip(stream);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

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
        return typeName.equals("byte") ||
                typeName.equals("short") ||
                typeName.equals("char") ||
                typeName.equals("int") ||
                typeName.equals("long") ||
                typeName.equals("float") ||
                typeName.equals("double") ||
                typeName.equals("boolean") ||
                typeName.equals("java.lang.String");
    }

    @Override
    public String toString() {
        return "MetadataClass{" +
                "id='" + getId() + '\'' +
                ", chunk=" + associatedChunk +
                ", name='" + getName() + "'" +
                ", superType='" + superType + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MetadataClass that = (MetadataClass) o;
        return getId() == that.getId() && Objects.equals(getName(), that.getName()) && Objects.equals(superType, that.superType) && Objects.equals(fields, that.fields);
    }

    @Override
    public int hashCode() {
        if (!hasHashCode) {
            long mixed = getId() * 0x9E3779B97F4A7C15L
                    + getName().hashCode() * 0xC6BC279692B5C323L
                    + Objects.hashCode(superType) * 0xD8163841FDE6A8F9L
                    + Objects.hashCode(fields) * 0xA3B195354A39B70DL;
            hashCode = Long.hashCode(mixed);
            hasHashCode = true;
        }
        return hashCode;
    }
}

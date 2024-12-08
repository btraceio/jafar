package io.jafar.parser.internal_api.metadata;

import io.jafar.parser.internal_api.RecordingStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class MetadataAnnotation extends AbstractMetadataElement {
    private boolean hasHashCode = false;
    private int hashCode;

    private List<MetadataAnnotation> annotations = null;

    public Long classId;
    public String value;
    MetadataAnnotation(RecordingStream stream, ElementReader reader) throws IOException {
        super(stream, MetadataElementKind.ANNOTATION);
        readSubelements(reader);
    }

    @Override
    protected void onAttribute(String key, String value) {
        switch (key) {
            case "class":
                classId = Long.parseLong(value);
                break;
            case "value":
                this.value = value;
                break;
        }
    }

    public MetadataClass getType() {
        return metadataLookup.getClass(classId);
    }

    public long getClassId() {
        return classId;
    }

    public String getValue() {
        return value;
    }

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
        return "MetadataAnnotation{" +
                "type='" + (getType() != null ? getType().getName() : getClassId()) + '\'' +
                ", value='" + getValue() + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MetadataAnnotation that = (MetadataAnnotation) o;
        return getClassId() == that.getClassId() && Objects.equals(annotations, that.annotations) && Objects.equals(getValue(), that.getValue());
    }

    @Override
    public int hashCode() {
        if (!hasHashCode) {
            hashCode = Objects.hash(annotations, getClassId(), getValue());
            hasHashCode = true;
        }
        return hashCode;
    }
}

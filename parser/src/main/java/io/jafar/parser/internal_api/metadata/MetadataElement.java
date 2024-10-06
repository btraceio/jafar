package io.jafar.parser.internal_api.metadata;

import io.jafar.parser.internal_api.RecordingStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class MetadataElement extends AbstractMetadataElement {
    private final List<MetadataClass> classes = new ArrayList<>();

    MetadataElement(RecordingStream stream, ElementReader reader) throws IOException {
        super(stream, MetadataElementKind.META);
        resetAttributes();
        readSubelements(reader);
    }

    @Override
    protected void onSubelement(AbstractMetadataElement element) {
        if (element.getKind() == MetadataElementKind.CLASS) {
            MetadataClass clz = (MetadataClass) element;
            classes.add(clz);
        } else {
            throw new IllegalStateException("Unexpected subelement: " + element.getKind());
        }
    }

    @Override
    public void accept(MetadataVisitor visitor) {
        visitor.visitMetadata(this);
        classes.forEach(c -> c.accept(visitor));
    }

    @Override
    public String toString() {
        return "MetadataElement";
    }
}
package io.jafar.parser.internal_api.metadata;

import io.jafar.parser.internal_api.MutableMetadataLookup;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.RecordingStream;

import java.io.IOException;

public abstract class AbstractMetadataElement {
    private final RecordingStream stream;

    final MutableMetadataLookup metadataLookup;

    private String id = "-1";
    private String name = null;
    private String simpleName = null;
    private final MetadataElementKind kind;

    AbstractMetadataElement(RecordingStream stream, MetadataElementKind kind) throws IOException {
        this.stream = stream;
        this.kind = kind;
        this.metadataLookup = (MutableMetadataLookup) stream.getContext().getMetadataLookup();
        processAttributes();
    }

    protected final void readSubelements(MetadataEvent event) throws IOException {
        // now inspect all the enclosed elements
        int elemCount = (int) stream.readVarint();
        for (int i = 0; i < elemCount; i++) {
            onSubelement(elemCount, event.readElement(stream));
        }
    }

    protected void onSubelement(int count, AbstractMetadataElement element) {}

    abstract public void accept(MetadataVisitor visitor);

    protected void onAttribute(String key, String value) {}

    protected final void processAttributes() throws IOException {
        int attrCount = (int) stream.readVarint();
        for (int i = 0; i < attrCount; i++) {
            int kv = (int) stream.readVarint();
            String key = metadataLookup.getString(kv);
            int vv = (int) stream.readVarint();
            String value = metadataLookup.getString(vv);
            if ("id".equals(key)) {
                id = value;
            }
            if ("name".equals(key)) {
                name = value;
            }
            onAttribute(key, value);
        }
    }

    public long getId() {
        return Long.parseLong(id);
    }

    public String getName() {
        return name;
    }

    public String getSimpleName() {
        if (simpleName == null) {
            int idx = name.lastIndexOf('.');
            simpleName = idx == -1 ? name : name.substring(idx + 1);
        }
        return simpleName;
    }

    public MetadataElementKind getKind() {
        return kind;
    }

    public ParserContext getContext() {
        return stream.getContext();
    }
}

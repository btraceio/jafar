package io.jafar.parser.internal_api.metadata;

import java.io.IOException;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.MutableMetadataLookup;
import io.jafar.parser.internal_api.RecordingStream;

/**
 * Abstract base class for metadata elements in JFR recordings.
 * <p>
 * This class provides common functionality for parsing and managing metadata elements
 * such as classes, methods, and other structural information from JFR data.
 * </p>
 */
public abstract class AbstractMetadataElement {
    private final RecordingStream stream;

    final MutableMetadataLookup metadataLookup;

    private String id = "-1";
    private String name = null;
    private String simpleName = null;
    private final MetadataElementKind kind;

    /**
     * Constructs a new AbstractMetadataElement with the given stream and kind.
     * 
     * @param stream the recording stream to read from
     * @param kind the kind of metadata element
     * @throws IOException if an I/O error occurs during construction
     */
    AbstractMetadataElement(RecordingStream stream, MetadataElementKind kind) throws IOException {
        this.stream = stream;
        this.kind = kind;
        this.metadataLookup = (MutableMetadataLookup) stream.getContext().getMetadataLookup();
        processAttributes();
    }

    /**
     * Reads and processes subelements from the metadata event.
     * 
     * @param event the metadata event containing subelements
     * @throws IOException if an I/O error occurs during reading
     */
    protected final void readSubelements(MetadataEvent event) throws IOException {
        // now inspect all the enclosed elements
        int elemCount = (int) stream.readVarint();
        for (int i = 0; i < elemCount; i++) {
            onSubelement(elemCount, event.readElement(stream));
        }
    }

    /**
     * Callback method called when a subelement is encountered.
     * 
     * @param count the total count of subelements
     * @param element the subelement that was read
     */
    protected void onSubelement(int count, AbstractMetadataElement element) {}

    /**
     * Accepts a metadata visitor for processing this element.
     * 
     * @param visitor the visitor to accept
     */
    abstract public void accept(MetadataVisitor visitor);

    /**
     * Callback method called when an attribute is encountered.
     * 
     * @param key the attribute key
     * @param value the attribute value
     */
    protected void onAttribute(String key, String value) {}

    /**
     * Processes all attributes for this metadata element.
     * 
     * @throws IOException if an I/O error occurs during processing
     */
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

    /**
     * Gets the ID of this metadata element.
     * 
     * @return the element ID as a long value
     */
    public long getId() {
        return Long.parseLong(id);
    }

    /**
     * Gets the full name of this metadata element.
     * 
     * @return the full name of the element
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the simple name of this metadata element (without package).
     * 
     * @return the simple name of the element
     */
    public String getSimpleName() {
        if (simpleName == null) {
            int idx = name.lastIndexOf('.');
            simpleName = idx == -1 ? name : name.substring(idx + 1);
        }
        return simpleName;
    }

    /**
     * Gets the kind of this metadata element.
     * 
     * @return the element kind
     */
    public MetadataElementKind getKind() {
        return kind;
    }

    /**
     * Gets the parser context associated with this element.
     * 
     * @return the parser context
     */
    public ParserContext getContext() {
        return stream.getContext();
    }
}

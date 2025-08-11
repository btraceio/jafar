package io.jafar.parser.internal_api.metadata;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.jafar.parser.internal_api.RecordingStream;

/**
 * Represents a metadata element in JFR recordings.
 * <p>
 * This class extends AbstractMetadataElement to provide specific functionality
 * for handling metadata elements, particularly class definitions.
 * </p>
 */
public final class MetadataElement extends AbstractMetadataElement {
    /** Flag indicating whether the hash code has been computed. */
    private boolean hasHashCode = false;
    
    /** Cached hash code value. */
    private int hashCode;

    /** List of metadata classes contained within this element. */
    private List<MetadataClass> classes = null;

    /**
     * Constructs a new MetadataElement from the recording stream and event.
     * 
     * @param stream the recording stream to read from
     * @param event the metadata event containing subelements
     * @throws IOException if an I/O error occurs during construction
     */
    MetadataElement(RecordingStream stream, MetadataEvent event) throws IOException {
        super(stream, MetadataElementKind.META);
        readSubelements(event);
    }

    /**
     * Handles subelements encountered during parsing.
     * 
     * @param count the total count of subelements
     * @param element the subelement that was read
     */
    @Override
    protected void onSubelement(int count, AbstractMetadataElement element) {
        if (element.getKind() == MetadataElementKind.CLASS) {
            if (classes == null) {
                classes = new ArrayList<>(count);
            }
            MetadataClass clz = (MetadataClass) element;
            classes.add(clz);
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
        visitor.visitMetadata(this);
        if (classes != null) {
            classes.forEach(c -> c.accept(visitor));
        }
    }

    @Override
    public String toString() {
        return "MetadataElement";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MetadataElement that = (MetadataElement) o;
        return Objects.equals(classes, that.classes);
    }

    @Override
    public int hashCode() {
        if (!hasHashCode) {
            hashCode = Objects.hash(classes);
            hasHashCode = true;
        }
        return hashCode;
    }
}

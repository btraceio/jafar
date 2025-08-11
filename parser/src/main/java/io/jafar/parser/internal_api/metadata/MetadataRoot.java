package io.jafar.parser.internal_api.metadata;

import java.io.IOException;
import java.util.Objects;

import io.jafar.parser.internal_api.RecordingStream;

/**
 * Represents the root metadata element in JFR recordings.
 * <p>
 * This class extends AbstractMetadataElement to provide the top-level
 * metadata structure containing metadata and region information.
 * </p>
 */
public final class MetadataRoot extends AbstractMetadataElement {
    /** Flag indicating whether the hash code has been computed. */
    private boolean hasHashHascode = false;
    
    /** Cached hash code value. */
    private int hashCode;

    /** The metadata element containing class definitions. */
    private MetadataElement metadata;
    
    /** The region element containing timezone information. */
    private MetadataRegion region;

    /**
     * Constructs a new MetadataRoot from the recording stream and event.
     * 
     * @param stream the recording stream to read from
     * @param event the metadata event containing subelements
     * @throws IOException if an I/O error occurs during construction
     */
    MetadataRoot(RecordingStream stream, MetadataEvent event) throws IOException {
        super(stream, MetadataElementKind.ROOT);
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
        if (element.getKind() == MetadataElementKind.META) {
            metadata = (MetadataElement) element;
        } else if (element.getKind() == MetadataElementKind.REGION) {
            region = (MetadataRegion) element;
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
        visitor.visitRoot(this);
        metadata.accept(visitor);
        region.accept(visitor);
        visitor.visitEnd(this);
    }

    @Override
    public String toString() {
        return "MetadataRoot";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MetadataRoot that = (MetadataRoot) o;
        return Objects.equals(metadata, that.metadata) && Objects.equals(region, that.region);
    }

    @Override
    public int hashCode() {
        if (!hasHashHascode) {
            hashCode = Objects.hash(metadata, region);
            hasHashHascode = true;
        }
        return hashCode;
    }
}

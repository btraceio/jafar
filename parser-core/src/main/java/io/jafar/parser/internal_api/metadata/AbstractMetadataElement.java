package io.jafar.parser.internal_api.metadata;

import io.jafar.parser.ParsingUtils;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.MutableMetadataLookup;
import io.jafar.parser.internal_api.RecordingStream;
import java.io.IOException;

/**
 * Abstract base class for metadata elements in JFR recordings.
 *
 * <p>This class provides common functionality for parsing and managing metadata elements such as
 * classes, methods, and other structural information from JFR data.
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
    int elemCount = plausibleCount("child");
    for (int i = 0; i < elemCount; i++) {
      AbstractMetadataElement el = event.readElement(stream);
      if (el.getKind() != MetadataElementKind.UNKNOWN) {
        onSubelement(elemCount, el);
      }
    }
  }

  /**
   * Callback method called when a subelement is encountered.
   *
   * @param count the total count of subelements
   * @param element the subelement that was read
   */
  protected void onSubelement(int count, AbstractMetadataElement element) throws IOException {}

  /**
   * The failure for a subelement of a kind this element cannot contain.
   *
   * <p>Which elements nest in which is decided by the recording's bytes, so a wrong one is a
   * property of the recording - an {@code IOException} like every other malformed-metadata
   * condition - and not an illegal state of the parser.
   *
   * @param element the subelement that was read
   * @return the exception to throw
   */
  protected final IOException unexpectedSubelement(AbstractMetadataElement element) {
    return new IOException(
        "Unexpected subelement " + element.getKind() + " in metadata element " + kind);
  }

  /**
   * Reads an attribute or child count and checks it against the data that is left: each attribute
   * and each child takes at least one byte, so a larger count cannot be honest, and following it
   * would loop (and, for children, recurse) on garbage until the data runs out.
   */
  private int plausibleCount(String what) throws IOException {
    long count = stream.readVarint();
    if (count < 0 || count > stream.available()) {
      throw new IOException(
          "Metadata element " + kind + ": implausible " + what + " count " + count);
    }
    return (int) count;
  }

  /**
   * Accepts a metadata visitor for processing this element.
   *
   * @param visitor the visitor to accept
   */
  public abstract void accept(MetadataVisitor visitor);

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
    int attrCount = plausibleCount("attribute");
    for (int i = 0; i < attrCount; i++) {
      int kv = (int) stream.readVarint();
      String key = ParsingUtils.metadataString(metadataLookup, kv);
      int vv = (int) stream.readVarint();
      String value = ParsingUtils.metadataString(metadataLookup, vv);
      if (key == null) {
        // The string table may hold null strings (encoding 0); one cannot name an attribute.
        throw new IOException(
            "Metadata element "
                + kind
                + ": attribute whose name is a null string (index "
                + kv
                + ")");
      }
      if (value == null) {
        // A null value says nothing about the attribute: leave it at its default, exactly as if
        // the attribute were absent, instead of overwriting the default with null (the id
        // attribute's default is what getId() parses).
        continue;
      }
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
    return ParsingUtils.parseLongSWAR(id);
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
      if (name == null) {
        return null;
      }
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

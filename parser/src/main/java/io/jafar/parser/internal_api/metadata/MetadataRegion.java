package io.jafar.parser.internal_api.metadata;

import io.jafar.parser.internal_api.RecordingStream;
import java.io.IOException;
import java.util.Objects;

/**
 * Represents a metadata region element in JFR recordings.
 *
 * <p>This class extends AbstractMetadataElement to provide timezone and locale information for the
 * JFR recording.
 */
public final class MetadataRegion extends AbstractMetadataElement {
  /** Flag indicating whether the hash code has been computed. */
  private boolean hasHashCode = false;

  /** Cached hash code value. */
  private int hashCode;

  /** Daylight saving time offset in milliseconds. */
  private long dst;

  /** GMT offset in milliseconds. */
  private long gmtOffset;

  /** Locale string (e.g., "en_US"). */
  private String locale;

  /**
   * Constructs a new MetadataRegion from the recording stream and event.
   *
   * @param stream the recording stream to read from
   * @param event the metadata event containing subelements
   * @throws IOException if an I/O error occurs during construction
   */
  MetadataRegion(RecordingStream stream, MetadataEvent event) throws IOException {
    super(stream, MetadataElementKind.REGION);
    readSubelements(event);
  }

  /**
   * Handles attributes encountered during parsing.
   *
   * @param key the attribute key
   * @param value the attribute value
   */
  @Override
  protected void onAttribute(String key, String value) {
    switch (key) {
      case "dst":
        dst = value != null ? Long.parseLong(value) : 0L;
        break;
      case "gmtOffset":
        gmtOffset = value != null ? Long.parseLong(value) : 0L;
        break;
      case "locale":
        locale = value != null ? value : "en_US";
        break;
    }
  }

  /**
   * Gets the daylight saving time offset.
   *
   * @return the DST offset in milliseconds
   */
  public long getDst() {
    return dst;
  }

  /**
   * Gets the GMT offset.
   *
   * @return the GMT offset in milliseconds
   */
  public long getGmtOffset() {
    return gmtOffset;
  }

  /**
   * Gets the locale string.
   *
   * @return the locale string
   */
  public String getLocale() {
    return locale;
  }

  /**
   * Handles subelements encountered during parsing.
   *
   * @param count the total count of subelements
   * @param element the subelement that was read
   */
  @Override
  protected void onSubelement(int count, AbstractMetadataElement element) {
    throw new IllegalStateException("Unexpected subelement: " + element.getKind());
  }

  /**
   * Accepts a metadata visitor for processing this element.
   *
   * @param visitor the visitor to accept
   */
  @Override
  public void accept(MetadataVisitor visitor) {
    visitor.visitRegion(this);
    visitor.visitEnd(this);
  }

  @Override
  public String toString() {
    return "MetadataRegion{"
        + "dst="
        + dst
        + ", gmtOffset="
        + gmtOffset
        + ", locale='"
        + locale
        + '\''
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    MetadataRegion that = (MetadataRegion) o;
    return dst == that.dst && gmtOffset == that.gmtOffset && Objects.equals(locale, that.locale);
  }

  @Override
  public int hashCode() {
    if (!hasHashCode) {
      long mixed =
          dst * 0x9E3779B97F4A7C15L
              + gmtOffset * 0xC6BC279692B5C323L
              + Objects.hashCode(locale) * 0xD8163841FDE6A8F9L;
      hashCode = Long.hashCode(mixed);
      hasHashCode = true;
    }
    return hashCode;
  }
}

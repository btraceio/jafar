package io.jafar.parser.internal_api.metadata;

import io.jafar.parser.ParsingUtils;
import io.jafar.parser.internal_api.RecordingStream;
import java.io.IOException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class MetadataSetting extends AbstractMetadataElement {
  private static final Logger log = LoggerFactory.getLogger(MetadataSetting.class);

  private boolean hasHashCode = false;
  private int hashCode;

  private String value;
  private long typeId = -1;
  private String typeName = null;

  public MetadataSetting(RecordingStream stream, MetadataEvent event) throws IOException {
    super(stream, MetadataElementKind.SETTING);
    readSubelements(event);
  }

  @Override
  protected void onAttribute(String key, String value) {
    switch (key) {
      case "defaultValue":
        this.value = value;
        break;
      case "class":
        try {
          typeId = ParsingUtils.parseLongSWAR(value);
        } catch (NumberFormatException e) {
          // Some custom JFR producers (e.g. dd-trace-java) write the class name
          // as a string instead of a numeric type ID. Store the name for a
          // deferred name-based lookup in getType().
          typeName = value;
        }
        break;
    }
  }

  public String getValue() {
    return value;
  }

  public MetadataClass getType() {
    if (typeId >= 0) {
      return metadataLookup.getClass(typeId);
    }
    if (typeName != null) {
      MetadataClass resolved = metadataLookup.getClass(typeName);
      if (resolved == null) {
        log.debug(
            "Setting '{}': class name '{}' could not be resolved in metadata", getName(), typeName);
      }
      return resolved;
    }
    return null;
  }

  @Override
  protected void onSubelement(int count, AbstractMetadataElement element) {
    throw new IllegalStateException("Unexpected subelement: " + element.getKind());
  }

  @Override
  public void accept(MetadataVisitor visitor) {
    visitor.visitSetting(this);
    visitor.visitEnd(this);
  }

  @Override
  public String toString() {
    String typeStr;
    if (typeId >= 0) {
      MetadataClass t = getType();
      typeStr = t != null ? t.getName() : String.valueOf(typeId);
    } else {
      typeStr = typeName != null ? typeName : "unknown";
    }
    return "MetadataSetting{"
        + "type='"
        + typeStr
        + "'"
        + ", name='"
        + getName()
        + "'"
        + ", value='"
        + value
        + '\''
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    MetadataSetting that = (MetadataSetting) o;
    return typeId == that.typeId
        && Objects.equals(typeName, that.typeName)
        && Objects.equals(value, that.value);
  }

  @Override
  public int hashCode() {
    if (!hasHashCode) {
      long mixed = typeId * 0x9E3779B97F4A7C15L + Objects.hashCode(value) * 0xC6BC279692B5C323L;
      mixed ^= Objects.hashCode(typeName) * 0xBF58476D1CE4E5B9L;
      hashCode = Long.hashCode(mixed);
      hasHashCode = true;
    }
    return hashCode;
  }
}

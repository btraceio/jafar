package io.jafar.parser.internal_api.metadata;

import io.jafar.parser.ParsingUtils;
import io.jafar.parser.internal_api.RecordingStream;
import java.io.IOException;
import java.util.Objects;

final class MetadataSetting extends AbstractMetadataElement {
  private boolean hasHashCode = false;
  private int hashCode;

  private String value;
  private long typeId;

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
        typeId = ParsingUtils.parseLongSWAR(value);
        break;
    }
  }

  public String getValue() {
    return value;
  }

  public MetadataClass getType() {
    return metadataLookup.getClass(typeId);
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
    return "MetadataSetting{"
        + "type='"
        + (getType() != null ? getType().getName() : typeId)
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
    return typeId == that.typeId && Objects.equals(value, that.value);
  }

  @Override
  public int hashCode() {
    if (!hasHashCode) {
      long mixed = typeId * 0x9E3779B97F4A7C15L + Objects.hashCode(value) * 0xC6BC279692B5C323L;
      hashCode = Long.hashCode(mixed);
      hasHashCode = true;
    }
    return hashCode;
  }
}

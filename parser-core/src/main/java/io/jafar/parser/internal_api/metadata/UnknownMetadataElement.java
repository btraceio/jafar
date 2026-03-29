package io.jafar.parser.internal_api.metadata;

import io.jafar.parser.internal_api.RecordingStream;
import java.io.IOException;

/** Placeholder for unrecognised metadata element types; reads and discards the element data. */
final class UnknownMetadataElement extends AbstractMetadataElement {
  UnknownMetadataElement(RecordingStream stream, MetadataEvent event) throws IOException {
    super(stream, MetadataElementKind.UNKNOWN);
    readSubelements(event);
  }

  @Override
  public void accept(MetadataVisitor visitor) {
    // no-op: unknown elements are silently ignored
  }
}

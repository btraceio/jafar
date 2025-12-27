package {{PACKAGE}};

import io.jafar.parser.api.ConstantPools;
import io.jafar.parser.api.MetadataLookup;
import io.jafar.parser.internal_api.RecordingStream;
import io.jafar.parser.internal_api.TypeSkipper;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataField;
import java.io.IOException;
import java.util.List;

/**
 * Generated handler implementation for {@link {{INTERFACE_NAME}}}.
 * JFR type: {{JFR_TYPE_NAME}}
 */
public final class {{HANDLER_NAME}} implements {{INTERFACE_NAME}} {

{{STATIC_TYPE_ID_FIELDS}}

  // Instance fields for event data
  private ConstantPools constantPools;
{{INSTANCE_FIELDS}}

  /**
   * Binds type IDs from the recording metadata.
   * Must be called before using handlers with a new recording.
   */
  public static void bind(MetadataLookup metadata) {
{{BIND_BODY}}
  }

  /**
   * Reads event data from the stream.
   * @param stream the recording stream positioned at event data
   * @param metadata the metadata class for this event type
   * @param constantPools the constant pools for resolving references
   * @throws IOException if reading fails
   */
  public void read(RecordingStream stream, MetadataClass metadata, ConstantPools constantPools) throws IOException {
    this.constantPools = constantPools;
    List<MetadataField> metaFields = metadata.getFields();
    for (MetadataField metaField : metaFields) {
      String fieldName = metaField.getName();
      switch (fieldName) {
{{SWITCH_CASES}}
        default:
          TypeSkipper.skip(metaField, stream);
          break;
      }
    }
  }

  /** Resets instance fields for reuse. */
  public void reset() {
    this.constantPools = null;
{{RESET_FIELDS}}
  }

{{GETTER_METHODS}}
}

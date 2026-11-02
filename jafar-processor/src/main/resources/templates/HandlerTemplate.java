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

  /**
   * Checks that the recording stores a field the way this handler reads it. The handler is
   * generated without the recording's metadata: a complex-typed accessor is read as a constant pool
   * reference and a primitive or String accessor as an inline value. A field stored otherwise (for
   * example jdk.types.StackTrace's inline frames array, or a String accessor for a pooled
   * jdk.types.Symbol) cannot be read by this handler; reading it anyway would misread it and every
   * field after it.
   */
  private static void requireStorage(
      MetadataClass metadata, MetadataField field, boolean pooled, boolean array)
      throws IOException {
    boolean isPooled = field.hasConstantPool();
    boolean isArray = field.getDimension() > 0;
    if (isPooled != pooled || isArray != array) {
      throw new IOException(
          "{{HANDLER_NAME}} cannot read "
              + metadata.getName()
              + "."
              + field.getName()
              + ": the recording stores it as "
              + describe(isPooled, isArray)
              + ", but {{INTERFACE_NAME}} declares it as "
              + describe(pooled, array));
    }
  }

  private static String describe(boolean pooled, boolean array) {
    String what = pooled ? "a constant pool reference" : "an inline value";
    return array ? "an array of " + what.substring(what.indexOf(' ') + 1) + "s" : what;
  }

  /** Resets instance fields for reuse. */
  public void reset() {
    this.constantPools = null;
{{RESET_FIELDS}}
  }

{{GETTER_METHODS}}
}

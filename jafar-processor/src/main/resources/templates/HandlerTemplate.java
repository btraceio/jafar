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

  /** The metadata class this instance last checked with validate(); not cleared by reset(). */
  private MetadataClass validatedFor;
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
    if (metadata != validatedFor) {
      validate(metadata);
      validatedFor = metadata;
    }
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
   * Checks the declared fields against the recording's metadata, once per metadata class: the
   * reads in {@link #read} are fixed at build time, without the metadata, so a field stored or typed
   * differently would be misread together with every field after it.
   */
  private static void validate(MetadataClass metadata) throws IOException {
    for (MetadataField metaField : metadata.getFields()) {
      switch (metaField.getName()) {
{{VALIDATE_CASES}}
        default:
          break;
      }
    }
  }

  /**
   * Checks one field: how it is stored ({@code pooled}: 0 inline, 1 a constant pool reference, 2
   * either - a raw accessor reads a pooled field's index), whether it is an array, and its JFR type
   * (for an inline field the type a simple type wraps) against {@code types}.
   */
  private static void requireStorage(
      MetadataClass metadata, MetadataField field, int pooled, boolean array, String[] types)
      throws IOException {
    boolean isPooled = field.hasConstantPool();
    boolean isArray = field.getDimension() > 0;
    MetadataClass type = field.getType();
    if (!isPooled) {
      int depth = 0;
      while (type != null && type.isSimpleType() && type.getFields().size() == 1 && depth++ < 10) {
        type = type.getFields().get(0).getType();
      }
    }
    String typeName = type != null ? type.getName() : "?";
    boolean storageOk = pooled == 2 || isPooled == (pooled == 1);
    boolean typeOk = (pooled == 2 && isPooled) || java.util.Arrays.asList(types).contains(typeName);
    if (!storageOk || isArray != array || !typeOk) {
      throw new IOException(
          "{{HANDLER_NAME}} cannot read "
              + metadata.getName()
              + "."
              + field.getName()
              + ": the recording stores it as "
              + describe(isPooled, isArray)
              + " of type "
              + typeName
              + ", but {{INTERFACE_NAME}} declares it as "
              + describe(pooled == 1 || (pooled == 2 && isPooled), array)
              + " of type "
              + (types.length == 0 ? "?" : String.join(" or ", types)));
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

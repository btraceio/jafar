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

  /**
   * The read plan of the metadata class this instance last read; not cleared by {@link #reset}.
   */
  private Plan plan;

  /**
   * The plan last built by any instance: pooled entries are read by a new instance each ({@code
   * HandlerFactory.create}), and the metadata walk is done once per metadata class, not per entry.
   */
  private static volatile Plan lastPlan;
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
    Plan p = this.plan;
    if (p == null || p.metadata != metadata) {
      p = lastPlan;
      if (p == null || p.metadata != metadata) {
        p = new Plan(metadata);
        lastPlan = p;
      }
      this.plan = p;
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
   * How {@link #read} reads each declared field from recordings with one metadata class, decided
   * once per metadata class: the reads are generated at build time, without the metadata, so a
   * field's storage (inline or a constant pool reference) is known only here. A field stored in a
   * way no read handles fails here, naming it, instead of being misread together with every field
   * after it.
   */
  private static final class Plan {
    final MetadataClass metadata;
{{PLAN_FIELDS}}

    Plan(MetadataClass metadata) throws IOException {
      this.metadata = metadata;
{{PLAN_DEFAULTS}}
      for (MetadataField metaField : metadata.getFields()) {
        switch (metaField.getName()) {
{{VALIDATE_CASES}}
          default:
            break;
        }
      }
{{PLAN_ASSIGN}}
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

  /**
   * Plans a field read through a complex-typed accessor: a constant pool reference ({@code false})
   * or an inline value read in place by {@code inlineHandler}, the generated handler of the
   * accessor's type ({@code true}; {@code null} when there is none to call).
   */
  private static boolean planComplex(
      MetadataClass metadata, MetadataField field, String type, boolean array, String inlineHandler)
      throws IOException {
    boolean isPooled = field.hasConstantPool();
    boolean isArray = field.getDimension() > 0;
    MetadataClass fieldType = field.getType();
    String typeName = fieldType != null ? fieldType.getName() : "?";
    if (isArray != array || !type.equals(typeName)) {
      throw mismatch(metadata, field, isPooled, isArray, typeName, isPooled, array, type);
    }
    if (!isPooled && inlineHandler == null) {
      throw new IOException(
          "{{HANDLER_NAME}} cannot read "
              + metadata.getName()
              + "."
              + field.getName()
              + ": the recording stores it as "
              + describe(false, isArray)
              + " of type "
              + typeName
              + ", which needs the generated handler of the accessor's type, and {{INTERFACE_NAME}}"
              + " declares it with a type that has none (annotate a top-level interface with"
              + " @JfrType(\""
              + type
              + "\"))");
    }
    return !isPooled;
  }

  /**
   * Plans a field read through a {@code String} accessor: an inline string ({@code -1}), or the id
   * of the constant pool the field refers to, whose entry is a string or a simple type wrapping one,
   * unwrapped by {@link #unwrapString} - for example a {@code jdk.types.Symbol} or a {@code
   * jdk.types.ThreadState}.
   */
  private static long planString(MetadataClass metadata, MetadataField field) throws IOException {
    boolean isPooled = field.hasConstantPool();
    boolean isArray = field.getDimension() > 0;
    MetadataClass type = field.getType();
    int depth = 0;
    // simple types are transparent, as in requireStorage and the runtime-generated handlers
    while (type != null && type.isSimpleType() && type.getFields().size() == 1 && depth++ < 10) {
      type = type.getFields().get(0).getType();
    }
    String typeName = type != null ? type.getName() : "?";
    if (isArray || !"java.lang.String".equals(typeName)) {
      MetadataClass declared = field.getType();
      throw mismatch(
          metadata,
          field,
          isPooled,
          isArray,
          isPooled && declared != null ? declared.getName() : typeName,
          isPooled,
          false,
          isPooled ? "java.lang.String or a simple type wrapping one" : "java.lang.String");
    }
    return isPooled ? field.getType().getId() : -1L;
  }

  /**
   * The string of a constant pool entry referred to through a {@code String} accessor: the entry
   * itself, or the single field of the type wrapping it. Such an entry has no handler of its own,
   * so its pool decodes it with a runtime deserializer, which unwraps simple types to the string,
   * or into a map of its fields.
   */
  private static String unwrapString(Object value, String field) {
    Object v = value;
    for (int depth = 0; depth < 10; depth++) {
      if (v == null || v instanceof String) {
        return (String) v;
      }
      if (!(v instanceof java.util.Map) || ((java.util.Map<?, ?>) v).size() != 1) {
        break;
      }
      v = ((java.util.Map<?, ?>) v).values().iterator().next();
    }
    throw new IllegalStateException(
        "{{INTERFACE_NAME}}."
            + field
            + "(): the constant pool entry is not a string or a single-field wrapper of one: "
            + value.getClass().getName());
  }

  private static IOException mismatch(
      MetadataClass metadata,
      MetadataField field,
      boolean isPooled,
      boolean isArray,
      String typeName,
      boolean declaredPooled,
      boolean declaredArray,
      String declaredType) {
    return new IOException(
        "{{HANDLER_NAME}} cannot read "
            + metadata.getName()
            + "."
            + field.getName()
            + ": the recording stores it as "
            + describe(isPooled, isArray)
            + " of type "
            + typeName
            + ", but {{INTERFACE_NAME}} declares it as "
            + describe(declaredPooled, declaredArray)
            + " of type "
            + declaredType);
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

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
   * Does nothing; kept because {@code HandlerFactory.bind} calls it. Type ids are chunk-local, so
   * nothing can be bound once for a recording: each read takes them from the {@link Plan} of the
   * metadata class it is given.
   */
  public static void bind(MetadataLookup metadata) {}

  /**
   * Reads event data from the stream.
   * @param stream the recording stream positioned at event data
   * @param metadata the metadata class for this event type
   * @param constantPools the constant pools for resolving references
   * @throws IOException if reading fails
   */
  public void read(RecordingStream stream, MetadataClass metadata, ConstantPools constantPools) throws IOException {
    Plan p = this.plan;
    if (p == null || p.metadata != metadata) {
      p = lastPlan;
      if (p == null || p.metadata != metadata) {
        p = new Plan(metadata);
        lastPlan = p;
      }
    }
    if (p != this.plan || constantPools != this.constantPools) {
      // the pools the getters resolved belong to the previous chunk or metadata class
{{CP_MEMO_CLEAR}}
      this.plan = p;
      this.constantPools = constantPools;
    }
    // The metadata fields in recording order, each resolved once per metadata class (Plan) to
    // what reads it: an int switch, like the straight-line code CodeGenerator emits per layout,
    // instead of a name switch over metadata.getFields() for every value.
    MetadataField[] fields = p.fields;
    int[] ops = p.ops;
    for (int fieldIndex = 0; fieldIndex < ops.length; fieldIndex++) {
      MetadataField metaField = fields[fieldIndex];
      switch (ops[fieldIndex]) {
{{SWITCH_CASES}}
        case SKIP_REFERENCE:
          stream.readVarint();
          break;
        default:
          TypeSkipper.skip(metaField, stream);
          break;
      }
    }
  }

{{GROUP_READ}}  /**
   * The op of a field no accessor reads that holds one constant pool reference: a varint, skipped
   * as {@code CodeGenerator.addFieldSkipper} skips it ({@code skipSimpleRef}). Every other field no
   * accessor reads is op {@code 0}, skipped by its {@link TypeSkipper}.
   */
  private static final int SKIP_REFERENCE = -1;

  /** The op of the declared field named {@code name}: its 1-based declaration index, or 0. */
  private static int opOf(String name) {
    switch (name) {
{{OP_CASES}}
      default:
        return 0;
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
    /** The metadata class's fields, in the order the recording stores them. */
    final MetadataField[] fields;
    /** What reads each of {@link #fields}: a declared field's op, {@link #SKIP_REFERENCE} or 0. */
    final int[] ops;
{{PLAN_FIELDS}}

    Plan(MetadataClass metadata) throws IOException {
      this.metadata = metadata;
      List<MetadataField> all = metadata.getFields();
      this.fields = all.toArray(new MetadataField[0]);
      this.ops = new int[fields.length];
      for (int i = 0; i < fields.length; i++) {
        MetadataField f = fields[i];
        int op = opOf(f.getName());
        // a scalar field: getDimension() is -1 (CodeGenerator tests "> 0" for an array)
        if (op == 0 && f.hasConstantPool() && f.getDimension() <= 0) {
          op = SKIP_REFERENCE;
        }
        ops[i] = op;
      }
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
    MetadataClass type = isPooled ? field.getType() : unwrapSimple(field.getType());
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

  /** The type a chain of single-field simple types wraps, or {@code type} itself. */
  private static MetadataClass unwrapSimple(MetadataClass type) {
    MetadataClass t = type;
    int depth = 0;
    while (t != null && t.isSimpleType() && t.getFields().size() == 1 && depth++ < 10) {
      t = t.getFields().get(0).getType();
    }
    return t;
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
    // simple types are transparent, as in requireStorage and the runtime-generated handlers
    MetadataClass type = unwrapSimple(field.getType());
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

package io.jafar.parser.internal_api;

import io.jafar.parser.api.Control;
import io.jafar.parser.api.Internal;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.UntypedStrategy;
import io.jafar.parser.impl.ConstantPoolAccessor;
import io.jafar.parser.impl.GeneratedDeserializerSupport;
import io.jafar.parser.impl.LazyEventMap;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataField;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates bytecode for untyped event deserializers (Tier 3 optimization).
 *
 * <p>This generator creates specialized deserializer classes that read JFR events directly from
 * {@link RecordingStream} and construct {@code Map<String, Object>} representations without the
 * overhead of ValueProcessor callbacks.
 *
 * <p><b>Contract:</b> a generated deserializer yields the same values as the generic untyped path
 * ({@code GenericValueReader} driving {@code MapValueBuilder}) for the same bytes. Concretely:
 *
 * <ul>
 *   <li>{@code byte}, {@code short}, {@code char}, {@code boolean}, {@code float}, {@code double}
 *       box to their own wrappers; {@code short} and {@code char} are varint-encoded
 *   <li>{@code int} and {@code long} box to {@link Long}, after the tick normalization the generic
 *       path applies to {@code @Timestamp}/{@code @Timespan} fields
 *   <li>constant-pool references become {@link ConstantPoolAccessor}
 *   <li>arrays become the generic path's {@code ArrayHolder} (via {@link
 *       GeneratedDeserializerSupport})
 *   <li>every other field — including single-field "simple types" — is a nested {@link HashMap}
 * </ul>
 *
 * <p>The parity test {@code GeneratedUntypedParityTest} holds this against a real recording.
 *
 * <p>Strategy:
 *
 * <ul>
 *   <li><b>Simple events</b> (≤10 fields, minimal nesting): Generate eager HashMap-based
 *       deserializers
 *   <li><b>Complex events</b> (&gt;10 fields or deeply nested): Generate lazy deserializers that
 *       fill an exact-size {@code Object[]} and return a {@link LazyEventMap} over it and a
 *       per-class constant key array (two allocations per event besides the values themselves)
 * </ul>
 *
 * <p>A generated class bakes in type ids (constant-pool type of each reference) and field layout of
 * one chunk's metadata. Ids are not stable across chunks, so a deserializer may only be reused for
 * a type with the same {@link #cacheKey}.
 *
 * @see UntypedEventDeserializer
 */
@Internal
public final class UntypedCodeGenerator {

  private static final Logger log = LoggerFactory.getLogger(UntypedCodeGenerator.class);

  /** Global counter for generating unique class names. */
  private static final AtomicLong CLASS_COUNTER = new AtomicLong(0);

  /** Threshold for simple vs complex event classification. */
  private static final int SIMPLE_EVENT_FIELD_THRESHOLD = 10;

  private static final int SIMPLE_EVENT_NESTED_THRESHOLD = 2;

  private static final String STREAM = Type.getInternalName(RecordingStream.class);
  private static final String SUPPORT = Type.getInternalName(GeneratedDeserializerSupport.class);
  private static final String MAP = Type.getInternalName(Map.class);
  private static final String HASH_MAP = Type.getInternalName(HashMap.class);
  private static final String LAZY_MAP = Type.getInternalName(LazyEventMap.class);
  private static final String CHUNK_INFO = Type.getInternalName(Control.ChunkInfo.class);
  private static final String READ_DESC =
      Type.getMethodDescriptor(
          Type.getType(Map.class),
          Type.getType(RecordingStream.class),
          Type.getType(ParserContext.class));

  /** Nested-type helpers also take the event's chunk info, looked up once per event. */
  private static final String HELPER_DESC =
      Type.getMethodDescriptor(
          Type.getType(Map.class),
          Type.getType(RecordingStream.class),
          Type.getType(ParserContext.class),
          Type.getType(Control.ChunkInfo.class));

  private static final String READ_SIGNATURE =
      "(Lio/jafar/parser/internal_api/RecordingStream;Lio/jafar/parser/api/ParserContext;)"
          + "Ljava/util/Map<Ljava/lang/String;Ljava/lang/Object;>;";

  private UntypedCodeGenerator() {
    // Static utility class
  }

  /**
   * Generates a deserializer for the given event type with default SPARSE_ACCESS strategy.
   *
   * @param eventType the metadata for the event type
   * @return a generated deserializer instance
   */
  public static UntypedEventDeserializer generate(MetadataClass eventType) {
    return generate(eventType, UntypedStrategy.SPARSE_ACCESS);
  }

  /**
   * Generates a deserializer for the given event type with specified strategy.
   *
   * @param eventType the metadata for the event type
   * @param strategy the optimization strategy
   * @return a generated deserializer instance
   * @throws RuntimeException if generation fails; the cause carries the reason
   */
  public static UntypedEventDeserializer generate(
      MetadataClass eventType, UntypedStrategy strategy) {
    try {
      String className = generateClassName(eventType);
      ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

      // Java 8 class files: parser-codegen supports JDK 8 at run time (see the test8 task).
      cw.visit(
          Opcodes.V1_8,
          Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
          className.replace('.', '/'),
          null,
          Type.getInternalName(Object.class),
          new String[] {Type.getInternalName(UntypedEventDeserializer.class)});

      Generation gen = new Generation(cw, className.replace('.', '/'));
      generateConstructor(cw);
      gen.generateDeserialize(eventType, shouldUseEagerDeserialization(eventType, strategy));
      gen.generatePendingHelpers();

      cw.visitEnd();

      byte[] bytecode = cw.toByteArray();
      Class<?> generatedClass =
          ClassDefiners.best().define(bytecode, UntypedEventDeserializer.class);
      return (UntypedEventDeserializer) generatedClass.getDeclaredConstructor().newInstance();

    } catch (Throwable e) {
      log.debug("Failed to generate deserializer for {}", eventType.getName(), e);
      throw new RuntimeException("Code generation failed for " + eventType.getName(), e);
    }
  }

  /**
   * Returns the key under which a deserializer generated for {@code eventType} may be cached and
   * reused.
   *
   * <p>Two event types share a key only if the generator would emit identical code for them: same
   * name, id and strategy, and the same layout — field names, field type ids and names, dimensions,
   * constant-pool flags, simple-type flags and tick normalization — for every type reachable
   * through non-constant-pool fields. Constant-pool fields contribute their type id, which the
   * generated code embeds.
   *
   * <p>Metadata class ids alone are not a valid key: they are assigned per chunk, and chunks from
   * different producers reuse them for unrelated types.
   *
   * @param eventType the event type
   * @param strategy the strategy the deserializer is generated for
   * @return a structural key; equal keys mean interchangeable deserializers
   */
  public static String cacheKey(MetadataClass eventType, UntypedStrategy strategy) {
    StringBuilder sb = new StringBuilder(256);
    sb.append(strategy != null ? strategy : UntypedStrategy.SPARSE_ACCESS).append('|');
    Set<Long> seen = new HashSet<>();
    Deque<MetadataClass> todo = new ArrayDeque<>();
    todo.add(eventType);
    while (!todo.isEmpty()) {
      MetadataClass type = todo.poll();
      if (!seen.add(type.getId())) {
        continue;
      }
      sb.append(type.getId()).append(' ');
      appendName(sb, type.getName());
      if (type.isSimpleType()) {
        sb.append(" simple");
      }
      sb.append('{');
      for (MetadataField field : type.getFields()) {
        MetadataClass fieldType = field.getType();
        appendName(sb, field.getName());
        sb.append(':').append(fieldType.getId()).append(' ');
        appendName(sb, fieldType.getName());
        sb.append('/').append(field.getDimension());
        if (field.hasConstantPool()) {
          sb.append("@cp");
        } else if (fieldType.isPrimitive()) {
          int kind = GeneratedDeserializerSupport.temporalKind(type, field.getName());
          if (kind != 0) {
            sb.append("~t").append(kind);
          }
        } else {
          todo.add(fieldType);
        }
        sb.append(';');
      }
      sb.append('}');
    }
    return sb.toString();
  }

  /**
   * Appends a metadata name length-prefixed ({@code 5"jdk.T}), so that no name can be mistaken for
   * key syntax: names come verbatim from the recording and may contain any separator used here.
   */
  private static void appendName(StringBuilder sb, String name) {
    sb.append(name.length()).append('"').append(name);
  }

  /**
   * Determines whether to use eager HashMap deserialization based on strategy and event structure.
   *
   * @param type the event type metadata
   * @param strategy the optimization strategy
   * @return true if eager HashMap should be used, false if lazy deserialization should be used
   */
  private static boolean shouldUseEagerDeserialization(
      MetadataClass type, UntypedStrategy strategy) {
    UntypedStrategy effectiveStrategy = strategy != null ? strategy : UntypedStrategy.SPARSE_ACCESS;

    switch (effectiveStrategy) {
      case FULL_ITERATION:
        // Always use eager HashMap for full iteration to avoid materialization overhead
        return true;

      case SPARSE_ACCESS:
        // Use hybrid: eager for simple events, lazy for complex events
        return isSimpleEvent(type);

      case AUTO:
        // TODO: Implement adaptive logic based on runtime profiling
        // For now, fall back to SPARSE_ACCESS behavior
        return isSimpleEvent(type);

      default:
        return isSimpleEvent(type);
    }
  }

  /**
   * Generates a unique class name for the event type.
   *
   * <p>Generated classes must be in the same package as UntypedEventDeserializer (internal_api) for
   * hidden class loading to work correctly.
   */
  private static String generateClassName(MetadataClass eventType) {
    return "io.jafar.parser.internal_api.UntypedDeserializer_"
        + sanitizeName(eventType.getName())
        + "_"
        + CLASS_COUNTER.incrementAndGet();
  }

  /**
   * Classifies event as simple or complex based on structure.
   *
   * <p>Simple events: ≤10 fields AND (no nested objects OR ≤2 small nested objects)
   *
   * @param type the event type metadata
   * @return true if simple event (use eager HashMap), false if complex (use lazy ArrayPool)
   */
  private static boolean isSimpleEvent(MetadataClass type) {
    int fieldCount = type.getFields().size();
    if (fieldCount > SIMPLE_EVENT_FIELD_THRESHOLD) {
      return false;
    }

    int nestedObjectCount = 0;
    for (MetadataField field : type.getFields()) {
      MetadataClass fieldType = field.getType();
      // Count non-primitive, non-simple-type fields (excluding arrays and CP refs)
      if (!fieldType.isSimpleType()
          && !fieldType.isPrimitive()
          && field.getDimension() == 0
          && !field.hasConstantPool()) {
        nestedObjectCount++;
        if (nestedObjectCount > SIMPLE_EVENT_NESTED_THRESHOLD) {
          return false;
        }
      }
    }

    return true;
  }

  private static void generateConstructor(ClassWriter cw) {
    MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
    ctor.visitCode();
    ctor.visitVarInsn(Opcodes.ALOAD, 0);
    ctor.visitMethodInsn(
        Opcodes.INVOKESPECIAL, Type.getInternalName(Object.class), "<init>", "()V", false);
    ctor.visitInsn(Opcodes.RETURN);
    ctor.visitMaxs(0, 0);
    ctor.visitEnd();
  }

  /** State for generating one class: the helper methods it needs, each emitted exactly once. */
  private static final class Generation {
    private final ClassWriter cw;
    private final String owner;

    /** Nested type id → helper method name. One chunk's metadata: ids are unique here. */
    private final Map<Long, String> helpers = new LinkedHashMap<>();

    private final Deque<MetadataClass> pendingHelpers = new ArrayDeque<>();

    Generation(ClassWriter cw, String owner) {
      this.cw = cw;
      this.owner = owner;
    }

    /**
     * Emits {@code deserialize(RecordingStream, ParserContext)}: locals 0=this, 1=stream,
     * 2=context, 3=chunk info, 4=container (HashMap or Object[]), 5..7=array loop.
     */
    void generateDeserialize(MetadataClass type, boolean eager) {
      MethodVisitor mv =
          cw.visitMethod(Opcodes.ACC_PUBLIC, "deserialize", READ_DESC, READ_SIGNATURE, null);
      mv.visitCode();
      Locals locals = new Locals(1, 2, 3, 4, 5);

      // One context lookup per event instead of one per tick-valued field; null when no field
      // reachable without a constant pool needs normalizing.
      if (needsChunkInfo(type)) {
        mv.visitVarInsn(Opcodes.ALOAD, locals.context);
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            SUPPORT,
            "chunkInfo",
            Type.getMethodDescriptor(
                Type.getType(Control.ChunkInfo.class), Type.getType(ParserContext.class)),
            false);
      } else {
        mv.visitInsn(Opcodes.ACONST_NULL);
      }
      mv.visitVarInsn(Opcodes.ASTORE, locals.chunkInfo);

      if (eager) {
        newHashMap(mv, type, locals.container);
        readFields(mv, type, locals, false);
        mv.visitVarInsn(Opcodes.ALOAD, locals.container);
      } else {
        // Exact-size values array, fresh per event (a retained map must not see later events);
        // the keys are a per-class constant shared by every map this class returns.
        generateKeysField(type);
        pushInt(mv, type.getFields().size());
        mv.visitTypeInsn(Opcodes.ANEWARRAY, Type.getInternalName(Object.class));
        mv.visitVarInsn(Opcodes.ASTORE, locals.container);
        readFields(mv, type, locals, true);
        // return new LazyEventMap(KEYS, values)
        mv.visitTypeInsn(Opcodes.NEW, LAZY_MAP);
        mv.visitInsn(Opcodes.DUP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "KEYS", "[Ljava/lang/String;");
        mv.visitVarInsn(Opcodes.ALOAD, locals.container);
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            LAZY_MAP,
            "<init>",
            "([Ljava/lang/String;[Ljava/lang/Object;)V",
            false);
      }
      mv.visitInsn(Opcodes.ARETURN);
      mv.visitMaxs(0, 0);
      mv.visitEnd();
    }

    /** {@code private static final String[] KEYS = {field names in metadata order}}. */
    private void generateKeysField(MetadataClass type) {
      cw.visitField(
              Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
              "KEYS",
              "[Ljava/lang/String;",
              null,
              null)
          .visitEnd();
      MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
      clinit.visitCode();
      pushInt(clinit, type.getFields().size());
      clinit.visitTypeInsn(Opcodes.ANEWARRAY, Type.getInternalName(String.class));
      int i = 0;
      for (MetadataField field : type.getFields()) {
        clinit.visitInsn(Opcodes.DUP);
        pushInt(clinit, i++);
        clinit.visitLdcInsn(field.getName());
        clinit.visitInsn(Opcodes.AASTORE);
      }
      clinit.visitFieldInsn(Opcodes.PUTSTATIC, owner, "KEYS", "[Ljava/lang/String;");
      clinit.visitInsn(Opcodes.RETURN);
      clinit.visitMaxs(0, 0);
      clinit.visitEnd();
    }

    /** Emits every helper requested so far, including those requested by helpers. */
    void generatePendingHelpers() {
      MetadataClass type;
      while ((type = pendingHelpers.poll()) != null) {
        generateHelper(type, helpers.get(type.getId()));
      }
    }

    /**
     * Emits {@code static Map name(RecordingStream, ParserContext, ChunkInfo)} for a nested type:
     * locals 0=stream, 1=context, 2=chunk info, 3=HashMap, 4..6=array loop. Recursive types call
     * themselves.
     */
    private void generateHelper(MetadataClass type, String name) {
      MethodVisitor mv =
          cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, name, HELPER_DESC, null, null);
      mv.visitCode();
      Locals locals = new Locals(0, 1, 2, 3, 4);
      newHashMap(mv, type, locals.container);
      readFields(mv, type, locals, false);
      mv.visitVarInsn(Opcodes.ALOAD, locals.container);
      mv.visitInsn(Opcodes.ARETURN);
      mv.visitMaxs(0, 0);
      mv.visitEnd();
    }

    private String helperFor(MetadataClass type) {
      String name = helpers.get(type.getId());
      if (name == null) {
        name = "read_" + type.getId() + "_" + sanitizeName(type.getName());
        helpers.put(type.getId(), name);
        pendingHelpers.add(type);
      }
      return name;
    }

    private static void newHashMap(MethodVisitor mv, MetadataClass type, int var) {
      mv.visitTypeInsn(Opcodes.NEW, HASH_MAP);
      mv.visitInsn(Opcodes.DUP);
      // Sized so that all fields fit without a resize (load factor 0.75). The generic path uses
      // the default capacity; capacity does not affect the map's contents or equality.
      pushInt(mv, type.getFields().size() * 4 / 3 + 1);
      mv.visitMethodInsn(Opcodes.INVOKESPECIAL, HASH_MAP, "<init>", "(I)V", false);
      mv.visitVarInsn(Opcodes.ASTORE, var);
    }

    /**
     * Reads every field of {@code type}, in metadata order, into the container local: {@code
     * map.put(name, value)} or {@code values[i] = value}.
     */
    private void readFields(
        MethodVisitor mv, MetadataClass type, Locals locals, boolean valuesArray) {
      int i = 0;
      for (MetadataField field : type.getFields()) {
        if (field.getDimension() == 1) {
          // Read the whole array into a local first, so no loop runs with a non-empty stack.
          readArray(mv, type, field, locals);
        }
        mv.visitVarInsn(Opcodes.ALOAD, locals.container);
        if (valuesArray) {
          pushInt(mv, i);
        } else {
          mv.visitLdcInsn(field.getName());
        }
        if (field.getDimension() == 1) {
          mv.visitVarInsn(Opcodes.ALOAD, locals.arrayHolder());
        } else {
          readValue(mv, type, field, locals);
        }
        if (valuesArray) {
          mv.visitInsn(Opcodes.AASTORE);
        } else {
          mv.visitMethodInsn(
              Opcodes.INVOKEINTERFACE,
              MAP,
              "put",
              Type.getMethodDescriptor(
                  Type.getType(Object.class),
                  Type.getType(Object.class),
                  Type.getType(Object.class)),
              true);
          mv.visitInsn(Opcodes.POP);
        }
        i++;
      }
    }

    /**
     * {@code holder = newArray(elementType, (int) readVarint()); for (i...) addToArray(holder,
     * element)}. Leaves the stack unchanged; the result is in {@code locals.arrayHolder()}.
     */
    private void readArray(
        MethodVisitor mv, MetadataClass owner, MetadataField field, Locals locals) {
      int len = locals.arrayLen();
      int holder = locals.arrayHolder();
      int index = locals.arrayIndex();

      mv.visitVarInsn(Opcodes.ALOAD, locals.stream);
      mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STREAM, "readVarint", "()J", false);
      mv.visitInsn(Opcodes.L2I);
      mv.visitVarInsn(Opcodes.ISTORE, len);

      PrimitiveArray direct = field.hasConstantPool() ? null : PrimitiveArray.of(field.getType());
      if (direct != null) {
        readPrimitiveArray(mv, owner, field, locals, direct);
        return;
      }

      mv.visitLdcInsn(field.getType().getName());
      mv.visitVarInsn(Opcodes.ILOAD, len);
      mv.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          SUPPORT,
          "newArray",
          "(Ljava/lang/String;I)Ljava/lang/Object;",
          false);
      mv.visitVarInsn(Opcodes.ASTORE, holder);

      mv.visitInsn(Opcodes.ICONST_0);
      mv.visitVarInsn(Opcodes.ISTORE, index);
      Label loop = new Label();
      Label end = new Label();
      mv.visitLabel(loop);
      mv.visitVarInsn(Opcodes.ILOAD, index);
      mv.visitVarInsn(Opcodes.ILOAD, len);
      mv.visitJumpInsn(Opcodes.IF_ICMPGE, end);
      mv.visitVarInsn(Opcodes.ALOAD, holder);
      readValue(mv, owner, field, locals);
      mv.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          SUPPORT,
          "addToArray",
          "(Ljava/lang/Object;Ljava/lang/Object;)V",
          false);
      mv.visitIincInsn(index, 1);
      mv.visitJumpInsn(Opcodes.GOTO, loop);
      mv.visitLabel(end);
    }

    /**
     * {@code raw = new T[len]; for (i...) raw[i] = element; holder = wrapArray(elementType, raw)}:
     * elements go straight into the primitive array {@code ArrayHolder} would allocate, with no
     * boxing and no {@code Array.set}. Leaves the stack unchanged.
     */
    private void readPrimitiveArray(
        MethodVisitor mv,
        MetadataClass owner,
        MetadataField field,
        Locals locals,
        PrimitiveArray kind) {
      int len = locals.arrayLen();
      int index = locals.arrayIndex();
      int raw = locals.rawArray();

      mv.visitVarInsn(Opcodes.ILOAD, len);
      mv.visitIntInsn(Opcodes.NEWARRAY, kind.newArrayType);
      mv.visitVarInsn(Opcodes.ASTORE, raw);

      mv.visitInsn(Opcodes.ICONST_0);
      mv.visitVarInsn(Opcodes.ISTORE, index);
      Label loop = new Label();
      Label end = new Label();
      mv.visitLabel(loop);
      mv.visitVarInsn(Opcodes.ILOAD, index);
      mv.visitVarInsn(Opcodes.ILOAD, len);
      mv.visitJumpInsn(Opcodes.IF_ICMPGE, end);
      mv.visitVarInsn(Opcodes.ALOAD, raw);
      mv.visitVarInsn(Opcodes.ILOAD, index);
      readPrimitive(mv, owner, field, locals);
      mv.visitInsn(kind.storeOpcode);
      mv.visitIincInsn(index, 1);
      mv.visitJumpInsn(Opcodes.GOTO, loop);
      mv.visitLabel(end);

      mv.visitLdcInsn(field.getType().getName());
      mv.visitVarInsn(Opcodes.ALOAD, raw);
      mv.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          SUPPORT,
          "wrapArray",
          "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;",
          false);
      mv.visitVarInsn(Opcodes.ASTORE, locals.arrayHolder());
    }

    /**
     * Pushes one value of {@code field}'s type (one element, for an array field), boxed exactly as
     * {@code MapValueBuilder} would box it.
     */
    private void readValue(MethodVisitor mv, MetadataClass owner, MetadataField field, Locals l) {
      MetadataClass type = field.getType();

      if (field.hasConstantPool()) {
        // new ConstantPoolAccessor(context, typeId, stream.readVarint())
        String cpa = Type.getInternalName(ConstantPoolAccessor.class);
        mv.visitTypeInsn(Opcodes.NEW, cpa);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, l.context);
        mv.visitLdcInsn(type.getId());
        mv.visitVarInsn(Opcodes.ALOAD, l.stream);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STREAM, "readVarint", "()J", false);
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            cpa,
            "<init>",
            Type.getMethodDescriptor(
                Type.VOID_TYPE, Type.getType(ParserContext.class), Type.LONG_TYPE, Type.LONG_TYPE),
            false);
        return;
      }

      if (!type.isPrimitive()) {
        // Any non-primitive is a nested struct — simple types (one-field wrappers) included,
        // exactly as GenericValueReader recurses into them.
        mv.visitVarInsn(Opcodes.ALOAD, l.stream);
        mv.visitVarInsn(Opcodes.ALOAD, l.context);
        mv.visitVarInsn(Opcodes.ALOAD, l.chunkInfo);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner(), helperFor(type), HELPER_DESC, false);
        return;
      }

      readPrimitive(mv, owner, field, l);
      switch (type.getName()) {
        case "short":
          box(mv, Short.class, Type.SHORT_TYPE);
          break;
        case "char":
          box(mv, Character.class, Type.CHAR_TYPE);
          break;
        case "int":
        case "long":
          box(mv, Long.class, Type.LONG_TYPE);
          break;
        case "byte":
          box(mv, Byte.class, Type.BYTE_TYPE);
          break;
        case "boolean":
          box(mv, Boolean.class, Type.BOOLEAN_TYPE);
          break;
        case "float":
          box(mv, Float.class, Type.FLOAT_TYPE);
          break;
        case "double":
          box(mv, Double.class, Type.DOUBLE_TYPE);
          break;
        default: // java.lang.String: already an object
          break;
      }
    }

    /**
     * Pushes one unboxed value of primitive {@code field}'s type, decoded and normalized exactly as
     * {@code GenericValueReader} and {@code MapValueBuilder} do: {@code short}/{@code char} as
     * int-category values, {@code int} and {@code long} as {@code long} (after tick normalization),
     * {@code String} as a reference.
     */
    private void readPrimitive(
        MethodVisitor mv, MetadataClass owner, MetadataField field, Locals l) {
      MetadataClass type = field.getType();
      switch (type.getName()) {
        case "short":
          readVarint(mv, l);
          mv.visitInsn(Opcodes.L2I);
          mv.visitInsn(Opcodes.I2S);
          break;
        case "char":
          readVarint(mv, l);
          mv.visitInsn(Opcodes.L2I);
          mv.visitInsn(Opcodes.I2C);
          break;
        case "int":
        case "long":
          {
            int kind = GeneratedDeserializerSupport.temporalKind(owner, field.getName());
            if (kind != 0) {
              mv.visitVarInsn(Opcodes.ALOAD, l.chunkInfo);
              pushInt(mv, kind);
            }
            readVarint(mv, l);
            if (type.getName().equals("int")) {
              // GenericValueReader: (int) readVarint(), widened back to long by onIntValue
              mv.visitInsn(Opcodes.L2I);
              mv.visitInsn(Opcodes.I2L);
            }
            if (kind != 0) {
              mv.visitMethodInsn(
                  Opcodes.INVOKESTATIC, SUPPORT, "normalize", "(L" + CHUNK_INFO + ";IJ)J", false);
            }
            break;
          }
        case "byte":
          mv.visitVarInsn(Opcodes.ALOAD, l.stream);
          mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STREAM, "read", "()B", false);
          break;
        case "boolean":
          mv.visitVarInsn(Opcodes.ALOAD, l.stream);
          mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STREAM, "readBoolean", "()Z", false);
          break;
        case "float":
          mv.visitVarInsn(Opcodes.ALOAD, l.stream);
          mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STREAM, "readFloat", "()F", false);
          break;
        case "double":
          mv.visitVarInsn(Opcodes.ALOAD, l.stream);
          mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STREAM, "readDouble", "()D", false);
          break;
        case "java.lang.String":
          mv.visitVarInsn(Opcodes.ALOAD, l.stream);
          mv.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL, STREAM, "readUTF8", "()Ljava/lang/String;", false);
          break;
        default:
          throw new IllegalArgumentException("Unsupported primitive type: " + type.getName());
      }
    }

    private String owner() {
      return owner;
    }

    private static void readVarint(MethodVisitor mv, Locals l) {
      mv.visitVarInsn(Opcodes.ALOAD, l.stream);
      mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STREAM, "readVarint", "()J", false);
    }

    private static void box(MethodVisitor mv, Class<?> wrapper, Type primitive) {
      mv.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          Type.getInternalName(wrapper),
          "valueOf",
          Type.getMethodDescriptor(Type.getType(wrapper), primitive),
          false);
    }
  }

  /** Local variable slots of one generated method. Array loops never nest within a method. */
  private static final class Locals {
    final int stream;
    final int context;
    final int chunkInfo;
    final int container;
    private final int firstFree;

    Locals(int stream, int context, int chunkInfo, int container, int firstFree) {
      this.stream = stream;
      this.context = context;
      this.chunkInfo = chunkInfo;
      this.container = container;
      this.firstFree = firstFree;
    }

    int arrayLen() {
      return firstFree;
    }

    int arrayHolder() {
      return firstFree + 1;
    }

    int arrayIndex() {
      return firstFree + 2;
    }

    int rawArray() {
      return firstFree + 3;
    }
  }

  /**
   * Element types whose arrays are filled directly as primitive arrays, in the backing array class
   * {@code ArrayHolder} uses for them: {@code int} elements go into a {@code long[]}, as {@code
   * MapValueBuilder} stores them (the untyped model represents every {@code int} as a {@code
   * long}). Elements are tick-normalized before they are stored, as the generic path does.
   */
  private enum PrimitiveArray {
    BYTE("byte", Opcodes.T_BYTE, Opcodes.BASTORE),
    BOOLEAN("boolean", Opcodes.T_BOOLEAN, Opcodes.BASTORE),
    SHORT("short", Opcodes.T_SHORT, Opcodes.SASTORE),
    INT("int", Opcodes.T_LONG, Opcodes.LASTORE),
    CHAR("char", Opcodes.T_CHAR, Opcodes.CASTORE),
    LONG("long", Opcodes.T_LONG, Opcodes.LASTORE),
    FLOAT("float", Opcodes.T_FLOAT, Opcodes.FASTORE),
    DOUBLE("double", Opcodes.T_DOUBLE, Opcodes.DASTORE);

    final String jfrName;
    final int newArrayType;
    final int storeOpcode;

    PrimitiveArray(String jfrName, int newArrayType, int storeOpcode) {
      this.jfrName = jfrName;
      this.newArrayType = newArrayType;
      this.storeOpcode = storeOpcode;
    }

    static PrimitiveArray of(MetadataClass elementType) {
      for (PrimitiveArray p : values()) {
        if (p.jfrName.equals(elementType.getName())) {
          return p;
        }
      }
      return null;
    }
  }

  /**
   * Whether any {@code int}/{@code long} field reachable from {@code type} without passing through
   * a constant pool is tick-normalized — i.e. whether the generated code reads the chunk info.
   */
  private static boolean needsChunkInfo(MetadataClass type) {
    Set<Long> seen = new HashSet<>();
    Deque<MetadataClass> todo = new ArrayDeque<>();
    todo.add(type);
    while (!todo.isEmpty()) {
      MetadataClass t = todo.poll();
      if (!seen.add(t.getId())) {
        continue;
      }
      for (MetadataField field : t.getFields()) {
        if (field.hasConstantPool()) {
          continue;
        }
        if (field.getType().isPrimitive()) {
          if (GeneratedDeserializerSupport.temporalKind(t, field.getName()) != 0) {
            return true;
          }
        } else {
          todo.add(field.getType());
        }
      }
    }
    return false;
  }

  /** Pushes an integer constant onto the stack (handles ICONST, BIPUSH, SIPUSH, LDC). */
  private static void pushInt(MethodVisitor mv, int value) {
    if (value >= -1 && value <= 5) {
      mv.visitInsn(Opcodes.ICONST_0 + value);
    } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
      mv.visitIntInsn(Opcodes.BIPUSH, value);
    } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
      mv.visitIntInsn(Opcodes.SIPUSH, value);
    } else {
      mv.visitLdcInsn(value);
    }
  }

  /** Sanitizes type name for use in class and method names. */
  private static String sanitizeName(String name) {
    return name.replaceAll("[^a-zA-Z0-9_]", "_");
  }
}

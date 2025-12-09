package io.jafar.parser.internal_api;

import io.jafar.parser.api.Internal;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.impl.LazyEventMap;
import io.jafar.parser.impl.LazyMapValueBuilder;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataField;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.HashSet;
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
 * <p>Strategy:
 *
 * <ul>
 *   <li><b>Simple events</b> (≤10 fields, minimal nesting): Generate eager HashMap-based
 *       deserializers
 *   <li><b>Complex events</b> (>10 fields or deeply nested): Generate lazy deserializers using
 *       LazyMapValueBuilder.ArrayPool
 * </ul>
 *
 * <p>All generated code includes stack state comments for debugging/maintenance.
 *
 * @see UntypedEventDeserializer
 */
@Internal
public final class UntypedCodeGenerator implements Opcodes {

  private static final boolean LOGS_ENABLED = false;
  private static final Logger log = LoggerFactory.getLogger(UntypedCodeGenerator.class);

  /** Global counter for generating unique class names. */
  private static final AtomicLong CLASS_COUNTER = new AtomicLong(0);

  /** Threshold for simple vs complex event classification. */
  private static final int SIMPLE_EVENT_FIELD_THRESHOLD = 10;

  private static final int SIMPLE_EVENT_NESTED_THRESHOLD = 2;

  private UntypedCodeGenerator() {
    // Static utility class
  }

  /**
   * Generates a deserializer for the given event type.
   *
   * @param eventType the metadata for the event type
   * @return a generated deserializer instance
   */
  public static UntypedEventDeserializer generate(MetadataClass eventType) {
    try {
      String className = generateClassName(eventType);
      ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

      // Generate class header
      cw.visit(
          V11, // Java 11
          ACC_PUBLIC | ACC_FINAL,
          className.replace('.', '/'),
          null,
          Type.getInternalName(Object.class),
          new String[] {Type.getInternalName(UntypedEventDeserializer.class)});

      // Decide strategy: eager (simple) vs lazy (complex)
      boolean isSimple = isSimpleEvent(eventType);

      if (isSimple) {
        generateEagerDeserializer(cw, className, eventType);
      } else {
        generateLazyDeserializer(cw, className, eventType);
      }

      cw.visitEnd();

      // Load the generated class
      byte[] bytecode = cw.toByteArray();
      Class<?> generatedClass =
          ClassDefiners.best().define(bytecode, UntypedEventDeserializer.class);

      // Return instance (stateless deserializer)
      return (UntypedEventDeserializer) generatedClass.getDeclaredConstructor().newInstance();

    } catch (Throwable e) {
      log.error("Failed to generate deserializer for {}", eventType.getName(), e);
      throw new RuntimeException("Code generation failed for " + eventType.getName(), e);
    }
  }

  /**
   * Generates a unique class name for the event type.
   *
   * <p>Generated classes must be in the same package as UntypedEventDeserializer (internal_api) for
   * hidden class loading to work correctly.
   */
  private static String generateClassName(MetadataClass eventType) {
    String sanitized =
        eventType.getName().replace('.', '_').replace('$', '_').replaceAll("[^a-zA-Z0-9_]", "_");
    return "io.jafar.parser.internal_api.UntypedDeserializer_"
        + sanitized
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

  /**
   * Generates eager deserializer for simple events.
   *
   * <p>Pattern: new HashMap(size) → put(field, value) for each field → return
   */
  private static void generateEagerDeserializer(
      ClassWriter cw, String className, MetadataClass type) {

    // Generate constructor (no-op)
    MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
    ctor.visitCode();
    // Stack: []
    ctor.visitVarInsn(ALOAD, 0);
    // Stack: [this]
    ctor.visitMethodInsn(INVOKESPECIAL, Type.getInternalName(Object.class), "<init>", "()V", false);
    // Stack: []
    ctor.visitInsn(RETURN);
    ctor.visitMaxs(0, 0);
    ctor.visitEnd();

    // Generate deserialize() method
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "deserialize",
            Type.getMethodDescriptor(
                Type.getType(Map.class),
                Type.getType(RecordingStream.class),
                Type.getType(ParserContext.class)),
            "(Lio/jafar/parser/internal_api/RecordingStream;Lio/jafar/parser/api/ParserContext;)Ljava/util/Map<Ljava/lang/String;Ljava/lang/Object;>;",
            null);

    mv.visitCode();
    addLog(mv, "Eager deserialize: " + type.getName());

    // Create HashMap with exact size
    // Stack: []
    int fieldCount = type.getFields().size();
    mv.visitTypeInsn(NEW, Type.getInternalName(HashMap.class));
    // Stack: [HashMap]
    mv.visitInsn(DUP);
    // Stack: [HashMap, HashMap]
    pushInt(mv, fieldCount);
    // Stack: [HashMap, HashMap, int]
    mv.visitMethodInsn(
        INVOKESPECIAL,
        Type.getInternalName(HashMap.class),
        "<init>",
        Type.getMethodDescriptor(Type.VOID_TYPE, Type.INT_TYPE),
        false);
    // Stack: [HashMap]
    mv.visitVarInsn(ASTORE, 3); // Store map in local var 3 (0=this, 1=stream, 2=context)
    // Stack: []

    // Track which nested types we need helper methods for
    Set<MetadataClass> nestedTypes = new HashSet<>();

    // For each field: read and put in map
    for (MetadataField field : type.getFields()) {
      // Stack: []
      mv.visitVarInsn(ALOAD, 3); // Load map
      // Stack: [Map]
      mv.visitLdcInsn(field.getName()); // Load field name
      // Stack: [Map, String]
      generateFieldRead(mv, field, 1, 2, className, nestedTypes); // stream at 1, context at 2
      // Stack: [Map, String, Object] (after generateFieldRead returns)
      mv.visitMethodInsn(
          INVOKEINTERFACE,
          Type.getInternalName(Map.class),
          "put",
          Type.getMethodDescriptor(
              Type.getType(Object.class), Type.getType(Object.class), Type.getType(Object.class)),
          true);
      // Stack: [Object] (old value or null)
      mv.visitInsn(POP); // Discard old value
      // Stack: []
    }

    // Stack: []
    mv.visitVarInsn(ALOAD, 3); // Load map
    // Stack: [Map]
    mv.visitInsn(ARETURN);
    // Stack: [] (method returns)

    mv.visitMaxs(0, 0);
    mv.visitEnd();

    // Generate helper methods for nested types
    for (MetadataClass nestedType : nestedTypes) {
      generateNestedObjectHelper(cw, className, nestedType);
    }
  }

  /**
   * Generates lazy deserializer for complex events.
   *
   * <p>Pattern: ArrayPool pool = TLS.get() → pool.add(field, value) for each → new
   * LazyEventMap(pool, size)
   */
  private static void generateLazyDeserializer(
      ClassWriter cw, String className, MetadataClass type) {

    // Add ThreadLocal<ArrayPool> static field
    cw.visitField(
            ACC_PRIVATE | ACC_STATIC | ACC_FINAL,
            "ARRAY_POOL_TL",
            Type.getDescriptor(ThreadLocal.class),
            "Ljava/lang/ThreadLocal<Lio/jafar/parser/impl/LazyMapValueBuilder$ArrayPool;>;",
            null)
        .visitEnd();

    // Static initializer for ThreadLocal
    MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
    clinit.visitCode();
    // Stack: []
    // Create lambda supplier: () -> new ArrayPool()
    clinit.visitInvokeDynamicInsn(
        "get",
        "()Ljava/util/function/Supplier;",
        new org.objectweb.asm.Handle(
            Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory",
            "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
            false),
        Type.getMethodType("()Ljava/lang/Object;"),
        new org.objectweb.asm.Handle(
            Opcodes.H_NEWINVOKESPECIAL,
            Type.getInternalName(LazyMapValueBuilder.ArrayPool.class),
            "<init>",
            "()V",
            false),
        Type.getMethodType("()Lio/jafar/parser/impl/LazyMapValueBuilder$ArrayPool;"));
    // Stack: [Supplier]
    clinit.visitMethodInsn(
        INVOKESTATIC,
        Type.getInternalName(ThreadLocal.class),
        "withInitial",
        Type.getMethodDescriptor(
            Type.getType(ThreadLocal.class), Type.getType(java.util.function.Supplier.class)),
        false);
    // Stack: [ThreadLocal]
    clinit.visitFieldInsn(
        PUTSTATIC,
        className.replace('.', '/'),
        "ARRAY_POOL_TL",
        Type.getDescriptor(ThreadLocal.class));
    // Stack: []
    clinit.visitInsn(RETURN);
    clinit.visitMaxs(0, 0);
    clinit.visitEnd();

    // Generate constructor
    MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
    ctor.visitCode();
    ctor.visitVarInsn(ALOAD, 0);
    ctor.visitMethodInsn(INVOKESPECIAL, Type.getInternalName(Object.class), "<init>", "()V", false);
    ctor.visitInsn(RETURN);
    ctor.visitMaxs(0, 0);
    ctor.visitEnd();

    // Generate deserialize() method
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "deserialize",
            Type.getMethodDescriptor(
                Type.getType(Map.class),
                Type.getType(RecordingStream.class),
                Type.getType(ParserContext.class)),
            "(Lio/jafar/parser/internal_api/RecordingStream;Lio/jafar/parser/api/ParserContext;)Ljava/util/Map<Ljava/lang/String;Ljava/lang/Object;>;",
            null);

    mv.visitCode();
    addLog(mv, "Lazy deserialize: " + type.getName());

    // ArrayPool pool = ARRAY_POOL_TL.get();
    // Stack: []
    mv.visitFieldInsn(
        GETSTATIC,
        className.replace('.', '/'),
        "ARRAY_POOL_TL",
        Type.getDescriptor(ThreadLocal.class));
    // Stack: [ThreadLocal]
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        Type.getInternalName(ThreadLocal.class),
        "get",
        Type.getMethodDescriptor(Type.getType(Object.class)),
        false);
    // Stack: [Object]
    mv.visitTypeInsn(CHECKCAST, Type.getInternalName(LazyMapValueBuilder.ArrayPool.class));
    // Stack: [ArrayPool]
    mv.visitVarInsn(ASTORE, 3); // pool at var 3
    // Stack: []

    // pool.reset();
    // Stack: []
    mv.visitVarInsn(ALOAD, 3);
    // Stack: [ArrayPool]
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        Type.getInternalName(LazyMapValueBuilder.ArrayPool.class),
        "reset",
        Type.getMethodDescriptor(Type.VOID_TYPE),
        false);
    // Stack: []

    // Track nested types
    Set<MetadataClass> nestedTypes = new HashSet<>();

    // For each field: read and add to pool
    for (MetadataField field : type.getFields()) {
      // Stack: []
      mv.visitVarInsn(ALOAD, 3); // Load pool
      // Stack: [ArrayPool]
      mv.visitLdcInsn(field.getName()); // Load field name
      // Stack: [ArrayPool, String]
      generateFieldRead(mv, field, 1, 2, className, nestedTypes); // Load value
      // Stack: [ArrayPool, String, Object] (after generateFieldRead returns)
      mv.visitMethodInsn(
          INVOKEVIRTUAL,
          Type.getInternalName(LazyMapValueBuilder.ArrayPool.class),
          "add",
          Type.getMethodDescriptor(
              Type.VOID_TYPE, Type.getType(String.class), Type.getType(Object.class)),
          false);
      // Stack: []
    }

    // return new LazyEventMap(pool, pool.size);
    // Stack: []
    mv.visitTypeInsn(NEW, Type.getInternalName(LazyEventMap.class));
    // Stack: [LazyEventMap]
    mv.visitInsn(DUP);
    // Stack: [LazyEventMap, LazyEventMap]
    mv.visitVarInsn(ALOAD, 3); // pool
    // Stack: [LazyEventMap, LazyEventMap, ArrayPool]
    mv.visitVarInsn(ALOAD, 3);
    // Stack: [LazyEventMap, LazyEventMap, ArrayPool, ArrayPool]
    mv.visitFieldInsn(
        GETFIELD, Type.getInternalName(LazyMapValueBuilder.ArrayPool.class), "size", "I");
    // Stack: [LazyEventMap, LazyEventMap, ArrayPool, int]
    mv.visitMethodInsn(
        INVOKESPECIAL,
        Type.getInternalName(LazyEventMap.class),
        "<init>",
        Type.getMethodDescriptor(
            Type.VOID_TYPE, Type.getType(LazyMapValueBuilder.ArrayPool.class), Type.INT_TYPE),
        false);
    // Stack: [LazyEventMap]
    mv.visitInsn(ARETURN);
    // Stack: [] (method returns)

    mv.visitMaxs(0, 0);
    mv.visitEnd();

    // Generate helper methods for nested types
    for (MetadataClass nestedType : nestedTypes) {
      generateNestedObjectHelper(cw, className, nestedType);
    }
  }

  /**
   * Generates bytecode to read a single field value.
   *
   * <p>Entry: Stack depends on caller context (e.g., [Map, String] for eager, [ArrayPool, String]
   * for lazy)
   *
   * <p>Exit: Stack has one additional element: the field value
   *
   * @param mv method visitor
   * @param field metadata for the field
   * @param streamVar local variable index for RecordingStream
   * @param contextVar local variable index for ParserContext
   * @param className name of the generated class (for calling helpers)
   * @param nestedTypes set to collect nested types (for helper generation)
   */
  private static void generateFieldRead(
      MethodVisitor mv,
      MetadataField field,
      int streamVar,
      int contextVar,
      String className,
      Set<MetadataClass> nestedTypes) {

    MetadataClass fieldType = field.getType();

    // Handle arrays
    if (field.getDimension() == 1) {
      generateArrayRead(mv, field, streamVar, contextVar, className, nestedTypes);
      // Stack: [..., Object[]] (array as Object)
      return;
    }

    // Handle constant pool references - store as Long
    if (field.hasConstantPool()) {
      // Stack: [...]
      mv.visitVarInsn(ALOAD, streamVar); // Load stream
      // Stack: [..., RecordingStream]
      mv.visitMethodInsn(
          INVOKEVIRTUAL,
          Type.getInternalName(RecordingStream.class),
          "readVarint",
          Type.getMethodDescriptor(Type.LONG_TYPE),
          false);
      // Stack: [..., long]
      mv.visitMethodInsn(
          INVOKESTATIC,
          Type.getInternalName(Long.class),
          "valueOf",
          Type.getMethodDescriptor(Type.getType(Long.class), Type.LONG_TYPE),
          false);
      // Stack: [..., Long]
      return;
    }

    // Handle nested complex objects (inline deserialization via helper method)
    if (!fieldType.isSimpleType() && !fieldType.isPrimitive()) {
      nestedTypes.add(fieldType); // Register for helper generation
      generateNestedObjectInline(mv, fieldType, streamVar, contextVar, className);
      // Stack: [..., Map<String,Object>]
      return;
    }

    // Handle primitives and strings
    // Stack: [...]
    mv.visitVarInsn(ALOAD, streamVar); // Load stream
    // Stack: [..., RecordingStream]

    String readMethod = getReadMethod(fieldType);
    Type returnType = getReadReturnType(fieldType);

    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        Type.getInternalName(RecordingStream.class),
        readMethod,
        Type.getMethodDescriptor(returnType),
        false);
    // Stack: [..., primitive_or_String]

    // Box primitives (but not String - String is already an object)
    if (returnType.getSort() != Type.OBJECT && returnType.getSort() != Type.ARRAY) {
      // Stack: [..., primitive]
      Class<?> wrapperClass = getWrapperClass(fieldType);
      Type primitiveType = returnType;
      mv.visitMethodInsn(
          INVOKESTATIC,
          Type.getInternalName(wrapperClass),
          "valueOf",
          Type.getMethodDescriptor(Type.getType(wrapperClass), primitiveType),
          false);
      // Stack: [..., Wrapper]
    }
    // Stack: [..., Object] (boxed primitive or String)
  }

  /** Generates inline call to nested object helper method. */
  private static void generateNestedObjectInline(
      MethodVisitor mv, MetadataClass nestedType, int streamVar, int contextVar, String className) {
    // Entry: Stack: [...] (depends on caller)
    // Exit: Stack: [..., Map<String,Object>]

    String helperName = "deserialize_" + sanitizeName(nestedType.getName());

    // Call the helper: helperName(stream, context)
    // Stack: [...]
    mv.visitVarInsn(ALOAD, streamVar); // stream
    // Stack: [..., RecordingStream]
    mv.visitVarInsn(ALOAD, contextVar); // context
    // Stack: [..., RecordingStream, ParserContext]
    mv.visitMethodInsn(
        INVOKESTATIC,
        className.replace('.', '/'),
        helperName,
        Type.getMethodDescriptor(
            Type.getType(Map.class),
            Type.getType(RecordingStream.class),
            Type.getType(ParserContext.class)),
        false);
    // Stack: [..., Map<String,Object>]
  }

  /** Generates helper method for deserializing nested objects (always uses small HashMap). */
  private static void generateNestedObjectHelper(
      ClassWriter cw, String className, MetadataClass nestedType) {

    String helperName = "deserialize_" + sanitizeName(nestedType.getName());
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PRIVATE | ACC_STATIC,
            helperName,
            Type.getMethodDescriptor(
                Type.getType(Map.class),
                Type.getType(RecordingStream.class),
                Type.getType(ParserContext.class)),
            "(Lio/jafar/parser/internal_api/RecordingStream;Lio/jafar/parser/api/ParserContext;)Ljava/util/Map<Ljava/lang/String;Ljava/lang/Object;>;",
            null);

    mv.visitCode();
    addLog(mv, "Nested deserialize: " + nestedType.getName());

    // Always use small HashMap for nested objects (typically <5 fields)
    // Stack: []
    int fieldCount = nestedType.getFields().size();
    mv.visitTypeInsn(NEW, Type.getInternalName(HashMap.class));
    // Stack: [HashMap]
    mv.visitInsn(DUP);
    // Stack: [HashMap, HashMap]
    pushInt(mv, fieldCount);
    // Stack: [HashMap, HashMap, int]
    mv.visitMethodInsn(
        INVOKESPECIAL,
        Type.getInternalName(HashMap.class),
        "<init>",
        Type.getMethodDescriptor(Type.VOID_TYPE, Type.INT_TYPE),
        false);
    // Stack: [HashMap]
    mv.visitVarInsn(ASTORE, 2); // var 2 (0=stream, 1=context)
    // Stack: []

    // Track nested types within nested types (recursive)
    Set<MetadataClass> innerNestedTypes = new HashSet<>();

    // Read all nested fields
    for (MetadataField field : nestedType.getFields()) {
      // Stack: []
      mv.visitVarInsn(ALOAD, 2);
      // Stack: [Map]
      mv.visitLdcInsn(field.getName());
      // Stack: [Map, String]
      generateFieldRead(mv, field, 0, 1, className, innerNestedTypes); // stream at 0, context at 1
      // Stack: [Map, String, Object]
      mv.visitMethodInsn(
          INVOKEINTERFACE,
          Type.getInternalName(Map.class),
          "put",
          Type.getMethodDescriptor(
              Type.getType(Object.class), Type.getType(Object.class), Type.getType(Object.class)),
          true);
      // Stack: [Object] (old value or null)
      mv.visitInsn(POP);
      // Stack: []
    }

    // Stack: []
    mv.visitVarInsn(ALOAD, 2);
    // Stack: [Map]
    mv.visitInsn(ARETURN);
    // Stack: [] (method returns)

    mv.visitMaxs(0, 0);
    mv.visitEnd();

    // Generate helpers for inner nested types (recursive)
    for (MetadataClass innerNestedType : innerNestedTypes) {
      if (!innerNestedType.equals(nestedType)) { // Avoid infinite recursion
        generateNestedObjectHelper(cw, className, innerNestedType);
      }
    }
  }

  /** Generates array reading bytecode. */
  private static void generateArrayRead(
      MethodVisitor mv,
      MetadataField field,
      int streamVar,
      int contextVar,
      String className,
      Set<MetadataClass> nestedTypes) {
    // Entry: Stack: [...] (depends on caller)
    // Exit: Stack: [..., Object[]]

    // int len = (int) stream.readVarint();
    // Stack: [...]
    mv.visitVarInsn(ALOAD, streamVar);
    // Stack: [..., RecordingStream]
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        Type.getInternalName(RecordingStream.class),
        "readVarint",
        Type.getMethodDescriptor(Type.LONG_TYPE),
        false);
    // Stack: [..., long]
    mv.visitInsn(L2I);
    // Stack: [..., int]
    int lenVar = streamVar + contextVar + 10; // Allocate safe var index
    mv.visitVarInsn(ISTORE, lenVar); // len
    // Stack: [...]

    // Object[] array = new Object[len];
    // Stack: [...]
    mv.visitVarInsn(ILOAD, lenVar);
    // Stack: [..., int]
    mv.visitTypeInsn(ANEWARRAY, Type.getInternalName(Object.class));
    // Stack: [..., Object[]]
    int arrayVar = lenVar + 1;
    mv.visitVarInsn(ASTORE, arrayVar); // array
    // Stack: [...]

    // for (int i = 0; i < len; i++)
    // Stack: [...]
    mv.visitInsn(ICONST_0);
    // Stack: [..., int]
    int iVar = arrayVar + 1;
    mv.visitVarInsn(ISTORE, iVar); // i
    // Stack: [...]

    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);
    // Loop iteration starts
    // Stack: [...]
    mv.visitVarInsn(ILOAD, iVar); // i
    // Stack: [..., int]
    mv.visitVarInsn(ILOAD, lenVar); // len
    // Stack: [..., int, int]
    mv.visitJumpInsn(IF_ICMPGE, loopEnd); // if i >= len goto loopEnd
    // Stack: [...] (comparison consumed both ints)

    // array[i] = readElement(stream);
    // Stack: [...]
    mv.visitVarInsn(ALOAD, arrayVar); // Load array
    // Stack: [..., Object[]]
    mv.visitVarInsn(ILOAD, iVar); // Load index
    // Stack: [..., Object[], int]

    // Read array element using the field's type (arrays are already reduced to element type)
    generateFieldRead(mv, field, streamVar, contextVar, className, nestedTypes);
    // Stack: [..., Object[], int, Object] (after generateFieldRead returns)
    mv.visitInsn(AASTORE); // array[i] = value
    // Stack: [...] (AASTORE consumes array, index, and value)

    // i++
    // Stack: [...]
    mv.visitIincInsn(iVar, 1); // i++ (doesn't affect stack)
    // Stack: [...]
    mv.visitJumpInsn(GOTO, loopStart);
    // Stack: [...]

    mv.visitLabel(loopEnd);
    // Loop ended
    // Stack: [...]
    mv.visitVarInsn(ALOAD, arrayVar); // Load array as result
    // Stack: [..., Object[]]
  }

  /** Pushes an integer constant onto the stack (handles BIPUSH, SIPUSH, LDC). */
  private static void pushInt(MethodVisitor mv, int value) {
    if (value >= -1 && value <= 5) {
      mv.visitInsn(ICONST_0 + value); // ICONST_0 through ICONST_5
    } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
      mv.visitIntInsn(BIPUSH, value);
    } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
      mv.visitIntInsn(SIPUSH, value);
    } else {
      mv.visitLdcInsn(value);
    }
  }

  /** Returns the RecordingStream read method name for the given field type. */
  private static String getReadMethod(MetadataClass fieldType) {
    String typeName = fieldType.getName();
    switch (typeName) {
      case "byte":
        return "read";
      case "boolean":
        return "readBoolean";
      case "short":
        return "readShort";
      case "char":
        return "readShort"; // char stored as short in JFR
      case "int":
        return "readVarint";
      case "long":
        return "readVarint";
      case "float":
        return "readFloat";
      case "double":
        return "readDouble";
      case "java.lang.String":
        return "readUTF8";
      default:
        throw new IllegalArgumentException("Unsupported field type: " + typeName);
    }
  }

  /** Returns the return type for the read method. */
  private static Type getReadReturnType(MetadataClass fieldType) {
    String typeName = fieldType.getName();
    switch (typeName) {
      case "byte":
        return Type.BYTE_TYPE;
      case "boolean":
        return Type.BOOLEAN_TYPE;
      case "short":
      case "char":
        return Type.SHORT_TYPE;
      case "int":
      case "long":
        return Type.LONG_TYPE; // varint returns long
      case "float":
        return Type.FLOAT_TYPE;
      case "double":
        return Type.DOUBLE_TYPE;
      case "java.lang.String":
        return Type.getType(String.class);
      default:
        throw new IllegalArgumentException("Unsupported field type: " + typeName);
    }
  }

  /** Returns the wrapper class for primitive types. */
  private static Class<?> getWrapperClass(MetadataClass fieldType) {
    String typeName = fieldType.getName();
    switch (typeName) {
      case "byte":
        return Byte.class;
      case "boolean":
        return Boolean.class;
      case "short":
        return Short.class;
      case "char":
        return Character.class;
      case "int":
      case "long":
        return Long.class; // varint stored as Long
      case "float":
        return Float.class;
      case "double":
        return Double.class;
      default:
        throw new IllegalArgumentException("Not a primitive type: " + typeName);
    }
  }

  /** Sanitizes type name for use in method names. */
  private static String sanitizeName(String name) {
    return name.replace('.', '_').replace('$', '_').replaceAll("[^a-zA-Z0-9_]", "_");
  }

  /** Adds debug logging statement (only if LOGS_ENABLED). */
  private static void addLog(MethodVisitor mv, String msg) {
    if (LOGS_ENABLED) {
      mv.visitFieldInsn(
          GETSTATIC,
          Type.getInternalName(System.class),
          "out",
          Type.getDescriptor(PrintStream.class));
      mv.visitLdcInsn(msg);
      mv.visitMethodInsn(
          INVOKEVIRTUAL,
          Type.getInternalName(PrintStream.class),
          "println",
          Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(String.class)),
          false);
    }
  }
}

package io.jafar.parser.internal_api;

import io.jafar.parser.ParsingUtils;
import io.jafar.parser.api.ConstantPool;
import io.jafar.parser.api.ConstantPools;
import io.jafar.parser.api.JafarSerializationException;
import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrIgnore;
import io.jafar.parser.api.MetadataLookup;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.impl.TypedParserContext;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataField;
import java.io.PrintStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class CodeGenerator {

  private static final boolean LOGS_ENABLED = false;
  private static final Logger log = LoggerFactory.getLogger(CodeGenerator.class);

  private static void addLog(MethodVisitor mv, String msg) {
    if (LOGS_ENABLED) {
      mv.visitFieldInsn(
          Opcodes.GETSTATIC,
          Type.getInternalName(System.class),
          "out",
          Type.getDescriptor(PrintStream.class));
      mv.visitLdcInsn(msg);
      mv.visitMethodInsn(
          Opcodes.INVOKEVIRTUAL,
          Type.getInternalName(PrintStream.class),
          "println",
          Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(String.class)),
          false);
    }
  }

  private static void addLogIntWithMsg(MethodVisitor mv, String msg) {
    if (LOGS_ENABLED) {
      // [int]
      mv.visitInsn(Opcodes.DUP); // [int, int]
      mv.visitFieldInsn(
          Opcodes.GETSTATIC,
          Type.getInternalName(System.class),
          "out",
          Type.getDescriptor(PrintStream.class)); // [int, int, out]
      mv.visitInsn(Opcodes.DUP); // [int, int, out, out]
      mv.visitLdcInsn(msg); // [int, int, out, out, msg]
      mv.visitMethodInsn(
          Opcodes.INVOKEVIRTUAL,
          Type.getInternalName(PrintStream.class),
          "print",
          Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(String.class)),
          false); // [int, int, out]
      mv.visitInsn(Opcodes.SWAP); // [int, out, int]
      mv.visitMethodInsn(
          Opcodes.INVOKEVIRTUAL,
          Type.getInternalName(PrintStream.class),
          "println",
          Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(int.class)),
          false); // [int]
    }
  }

  static void handleFieldRef(
      ClassVisitor cv,
      String clzName,
      long typeId,
      boolean isArray,
      Class<?> fldType,
      String fldName,
      FieldMapping mapping,
      boolean generateRefField) {
    if (fldType == null && !mapping.raw()) {
      // field is never accessed directly, can skip the rest
      return;
    }
    clzName = clzName.replace('.', '/');
    String fldRefName = fldName + "_ref";

    if (generateRefField) {
      cv.visitField(
              Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
              fldRefName,
              (isArray ? "[" : "") + "J",
              null,
              null)
          .visitEnd();
    }
    if (mapping.raw()) {
      assert !isArray;
      MethodVisitor mv =
          cv.visitMethod(
              Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, mapping.method(), "()J", null, null);
      mv.visitCode();
      mv.visitVarInsn(Opcodes.ALOAD, 0); // stack: [this]
      mv.visitFieldInsn(Opcodes.GETFIELD, clzName, fldRefName, "J"); // stack: [fld]
      mv.visitInsn(Opcodes.LRETURN); // stack: []
      mv.visitMaxs(0, 0);
      mv.visitEnd();
      return;
    }

    String fldCpName = fldName + "_cp";
    String mthdCpName = fldCpName + "$get";

    cv.visitField(
            Opcodes.ACC_PRIVATE, fldCpName, Type.getDescriptor(ConstantPool.class), null, null)
        .visitEnd();

    MethodVisitor mv =
        cv.visitMethod(
            Opcodes.ACC_PRIVATE,
            mthdCpName,
            Type.getMethodDescriptor(Type.getType(ConstantPool.class)),
            null,
            null);
    mv.visitCode();
    Label l = new Label();
    mv.visitVarInsn(Opcodes.ALOAD, 0); // stack: [this]
    mv.visitFieldInsn(
        Opcodes.GETFIELD,
        clzName,
        fldCpName,
        Type.getDescriptor(ConstantPool.class)); // stack: [pool]
    mv.visitInsn(Opcodes.DUP); // stack: [pool, pool]
    mv.visitJumpInsn(Opcodes.IFNONNULL, l); // stack: [pool]
    mv.visitInsn(Opcodes.POP); // stack: []
    mv.visitVarInsn(Opcodes.ALOAD, 0); // stack: [this]
    mv.visitInsn(Opcodes.DUP); // stack: [this, this]
    mv.visitFieldInsn(
        Opcodes.GETFIELD,
        clzName,
        "context",
        Type.getDescriptor(TypedParserContext.class)); // stack: [this, context]
    mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        Type.getInternalName(TypedParserContext.class),
        "getConstantPools",
        Type.getMethodDescriptor(Type.getType(ConstantPools.class)),
        false); // stack: [this, pools]
    mv.visitLdcInsn(typeId); // stack: [this, pools, type]
    mv.visitMethodInsn(
        Opcodes.INVOKEINTERFACE,
        Type.getInternalName(ConstantPools.class),
        "getConstantPool",
        Type.getMethodDescriptor(Type.getType(ConstantPool.class), Type.LONG_TYPE),
        true); // stack: [this, pool]
    mv.visitInsn(Opcodes.DUP_X1); // stack: [pool, this, pool]
    mv.visitFieldInsn(
        Opcodes.PUTFIELD, clzName, fldCpName, Type.getDescriptor(ConstantPool.class)); // [pool]
    mv.visitInsn(Opcodes.ARETURN);
    mv.visitLabel(l); // stack: [pool]
    mv.visitInsn(Opcodes.ARETURN);
    mv.visitMaxs(3, 2);
    mv.visitEnd();

    mv =
        cv.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
            mapping.method(),
            "()" + (isArray ? "[" : "") + Type.getDescriptor(fldType),
            null,
            null);
    mv.visitCode();
    mv.visitVarInsn(Opcodes.ALOAD, 0);
    mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        clzName,
        mthdCpName,
        Type.getMethodDescriptor(Type.getType(ConstantPool.class)),
        false);
    mv.visitInsn(Opcodes.DUP);
    mv.visitVarInsn(Opcodes.ASTORE, 1);
    mv.visitVarInsn(Opcodes.ALOAD, 0);
    if (isArray) {
      mv.visitFieldInsn(
          Opcodes.GETFIELD, clzName, fldRefName, "[" + Type.LONG_TYPE.getDescriptor()); // [fld]
      mv.visitInsn(Opcodes.DUP); // [fld, fld]
      mv.visitVarInsn(Opcodes.ASTORE, 2); // [fld]
      mv.visitInsn(Opcodes.ARRAYLENGTH); // [int]
      mv.visitInsn(Opcodes.DUP); // [int, int]
      mv.visitVarInsn(Opcodes.ISTORE, 3); // [int]
      mv.visitTypeInsn(Opcodes.ANEWARRAY, Type.getInternalName(fldType)); // [array]
      mv.visitLdcInsn(0); // [array, int]
      mv.visitVarInsn(Opcodes.ISTORE, 4); // [array]
      Label l1 = new Label();
      Label l2 = new Label();
      mv.visitLabel(l1);
      mv.visitVarInsn(Opcodes.ILOAD, 3); // [array, int]
      mv.visitVarInsn(Opcodes.ILOAD, 4); // [array, int, int]
      mv.visitJumpInsn(Opcodes.IF_ICMPEQ, l2); // [array]
      mv.visitInsn(Opcodes.DUP); // [array, array]
      mv.visitVarInsn(Opcodes.ILOAD, 4); // [array, array, int]
      mv.visitInsn(Opcodes.DUP); // [array, array, int, int]
      mv.visitVarInsn(Opcodes.ALOAD, 1); // [array, array, int, int, cp]
      mv.visitInsn(Opcodes.SWAP); // [array, array, int, cp, int]
      mv.visitVarInsn(Opcodes.ALOAD, 2); // [array, array, int, cp, int, fld]
      mv.visitInsn(Opcodes.SWAP); // [array, array, int, cp, fld, int]
      mv.visitInsn(Opcodes.LALOAD); // [array, array, int, cp, long]
      mv.visitMethodInsn(
          Opcodes.INVOKEINTERFACE,
          Type.getInternalName(ConstantPool.class),
          "get",
          Type.getMethodDescriptor(Type.getType(Object.class), Type.LONG_TYPE),
          true); // [array, array, int, obj]
      mv.visitTypeInsn(
          Opcodes.CHECKCAST, Type.getInternalName(fldType)); // [array, array, int, fldval]
      mv.visitInsn(Opcodes.AASTORE); // [array]
      mv.visitIincInsn(4, 1); // [array]
      mv.visitJumpInsn(Opcodes.GOTO, l1);
      mv.visitLabel(l2);
    } else {
      mv.visitFieldInsn(Opcodes.GETFIELD, clzName, fldRefName, Type.LONG_TYPE.getDescriptor());
      mv.visitMethodInsn(
          Opcodes.INVOKEINTERFACE,
          Type.getInternalName(ConstantPool.class),
          "get",
          Type.getMethodDescriptor(Type.getType(Object.class), Type.LONG_TYPE),
          true);
      mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(fldType));
    }
    mv.visitInsn(Opcodes.ARETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  static void handleField(
      ClassVisitor cv,
      String clzName,
      boolean isArray,
      Class<?> fldType,
      String fieldName,
      String methodName) {
    if (fldType == null) {
      // field is never accessed directly, can skip the rest
      return;
    }

    String fldDescriptor = (isArray ? "[" : "") + Type.getDescriptor(fldType);
    cv.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, fieldName, fldDescriptor, null, null)
        .visitEnd();
    MethodVisitor mv =
        cv.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
            methodName,
            "()" + (isArray ? "[" : "") + Type.getDescriptor(fldType),
            null,
            null);
    mv.visitCode();
    mv.visitVarInsn(Opcodes.ALOAD, 0); // [this]
    mv.visitFieldInsn(
        Opcodes.GETFIELD, clzName.replace('.', '/'), fieldName, fldDescriptor); // [fld]
    if (isArray) {
      mv.visitInsn(Opcodes.ARETURN);
    } else {
      mv.visitInsn(Type.getType(fldType).getOpcode(Opcodes.IRETURN));
    }
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  static void addFieldSkipper(MethodVisitor mv, MetadataField fld, int streamIdx, int lastVarIdx) {
    // stack: [stream]
    if (fld.hasConstantPool()) {
      if (fld.getDimension() > 0) {
        skipArrayRef(lastVarIdx, mv); // []
      } else {
        skipSimpleRef(mv); // []
      }
    } else {
      if (fld.getDimension() > 0) {
        skipArrayField(fld, streamIdx, lastVarIdx, mv); // []
      } else {
        skipSimpleField(fld.getType(), streamIdx, lastVarIdx, false, mv); // []
      }
    }
  }

  static void addFieldLoader(
      MethodVisitor mv,
      MetadataField fld,
      String className,
      int streamIdx,
      int metadataIdx,
      int lastVarIdx,
      TypedParserContext context) {
    // stack: [this, stream]
    if (fld.hasConstantPool()) {
      if (fld.getDimension() > 0) {
        handleArrayRef(fld, className, metadataIdx, mv); // []
      } else {
        handleSimpleRef(fld, className, mv); // []
      }
    } else {
      if (fld.getDimension() > 0) {
        handleArrayField(fld, className, streamIdx, metadataIdx, lastVarIdx, context, mv); // []
      } else {
        handleSimpleField(fld, className, metadataIdx, lastVarIdx, context, mv); // []
      }
    }
  }

  private static void skipArrayRef(int lastVarIdx, MethodVisitor mv) {
    // stack: [stream]
    int arraySizeIdx = lastVarIdx + 1;
    mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        Type.getInternalName(RecordingStream.class),
        "readVarint",
        Type.getMethodDescriptor(Type.LONG_TYPE),
        false); // [stream, long]
    mv.visitInsn(Opcodes.L2I);
    mv.visitVarInsn(Opcodes.ISTORE, arraySizeIdx); // [stream]
    Label l1 = new Label();
    mv.visitLabel(l1);
    mv.visitInsn(Opcodes.DUP); // [stream, stream]
    mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        Type.getInternalName(RecordingStream.class),
        "readVarint",
        Type.getMethodDescriptor(Type.LONG_TYPE),
        false); // [stream, long]
    mv.visitInsn(Opcodes.POP2); // [stream]
    mv.visitVarInsn(Opcodes.ILOAD, arraySizeIdx); // [stream, int]
    mv.visitLdcInsn(1); // [stream, int, 1]
    mv.visitInsn(Opcodes.ISUB); // [stream, int]
    mv.visitInsn(Opcodes.DUP); // [stream, int, int]
    mv.visitVarInsn(Opcodes.ISTORE, arraySizeIdx); // [stream, int]
    mv.visitJumpInsn(Opcodes.IFNE, l1); // [stream]
    mv.visitInsn(Opcodes.POP); // []
  }

  private static void handleArrayRef(
      MetadataField fld, String className, int lastVarIdx, MethodVisitor mv) {
    // stack: [this, stream]
    int arrayCounterIdx = lastVarIdx + 1;
    int arraySizeIdx = arrayCounterIdx + 1;
    Label l1 = new Label();
    Label l2 = new Label();
    mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        Type.getInternalName(RecordingStream.class),
        "readVarint",
        Type.getMethodDescriptor(Type.LONG_TYPE),
        false); // [this, long]
    mv.visitInsn(Opcodes.L2I); // [this, int]
    mv.visitInsn(Opcodes.DUP); // [this, int, int]
    mv.visitVarInsn(Opcodes.ISTORE, arraySizeIdx); // [this, int]
    mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_LONG); // [this, array]
    mv.visitLdcInsn(0); // [this, array, int]
    mv.visitVarInsn(Opcodes.ISTORE, arrayCounterIdx); // [this, array]
    mv.visitLabel(l1);
    mv.visitVarInsn(Opcodes.ILOAD, arraySizeIdx); // [this, array, int]
    mv.visitVarInsn(Opcodes.ILOAD, arrayCounterIdx); // [this, array, int, int]
    mv.visitJumpInsn(Opcodes.IF_ICMPEQ, l2); // [this, array]
    mv.visitInsn(Opcodes.DUP); // [this, array, array]
    mv.visitVarInsn(Opcodes.ILOAD, arrayCounterIdx); // [this, array, array, int]
    mv.visitVarInsn(Opcodes.ALOAD, 1); // [this, array, array, int, stream]
    mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        Type.getInternalName(RecordingStream.class),
        "readVarint",
        Type.getMethodDescriptor(Type.LONG_TYPE),
        false); // [this, array, array, int, long]
    mv.visitInsn(Opcodes.LASTORE); // [this, array]
    mv.visitIincInsn(arrayCounterIdx, 1); // [this, array]
    mv.visitJumpInsn(Opcodes.GOTO, l1); // [this, array]
    mv.visitLabel(l2);
    mv.visitFieldInsn(
        Opcodes.PUTFIELD,
        className,
        fld.getName() + "_ref",
        "[" + Type.LONG_TYPE.getDescriptor()); // []
  }

  private static void skipSimpleRef(MethodVisitor mv) {
    mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        Type.getInternalName(RecordingStream.class),
        "readVarint",
        Type.getMethodDescriptor(Type.LONG_TYPE),
        false); // [long]
    mv.visitInsn(Opcodes.POP2); // []
  }

  private static void handleSimpleRef(MetadataField fld, String className, MethodVisitor mv) {
    // stack: [this, stream]
    mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        Type.getInternalName(RecordingStream.class),
        "readVarint",
        Type.getMethodDescriptor(Type.LONG_TYPE),
        false); // [this, long]
    mv.visitFieldInsn(
        Opcodes.PUTFIELD, className, fld.getName() + "_ref", Type.LONG_TYPE.getDescriptor()); // []
  }

  private static void skipArrayField(
      MetadataField fld, int streamIdx, int lastVarIdx, MethodVisitor mv) {
    // stack: [stream]
    String fldTypeName = fld.getType().getName();
    int arraySizeIdx = lastVarIdx + 1;
    lastVarIdx = arraySizeIdx;

    Type fldType = null;
    Type dataType = null;

    switch (fldTypeName) {
      case "byte", "boolean":
        {
          fldType = fldTypeName.equals("byte") ? Type.BYTE_TYPE : Type.BOOLEAN_TYPE;
          dataType = Type.BYTE_TYPE;
          break;
        }
      case "short", "char", "int", "long":
        {
          dataType = Type.LONG_TYPE;
          switch (fldTypeName) {
            case "short":
              {
                fldType = Type.SHORT_TYPE;
                break;
              }
            case "char":
              {
                fldType = Type.CHAR_TYPE;
                break;
              }
            case "int":
              {
                fldType = Type.INT_TYPE;
                break;
              }
            case "long":
              {
                fldType = Type.LONG_TYPE;
                break;
              }
          }
          break;
        }
      case "float":
        {
          dataType = Type.FLOAT_TYPE;
          fldType = Type.FLOAT_TYPE;
          break;
        }
      case "double":
        {
          dataType = Type.DOUBLE_TYPE;
          fldType = Type.DOUBLE_TYPE;
          break;
        }
    }
    if (fldType != null) {
      skipPrimitiveArray(dataType, arraySizeIdx, mv); // []
    } else if (fldTypeName.equals("java.lang.String")) {
      skipStringArray(arraySizeIdx, mv); // []
    } else {
      skipObjectArray(fld.getType(), arraySizeIdx, streamIdx, lastVarIdx, false, mv); // []
    }
  }

  private static void skipPrimitiveArray(Type dataType, int arraySizeIdx, MethodVisitor mv) {
    String operation = getPrimitiveReadOperation(dataType);
    // stack: [stream]
    Label l1 = new Label();
    Label l2 = new Label();
    Label l3 = new Label();
    mv.visitInsn(Opcodes.DUP); // [stream, stream]
    mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        Type.getInternalName(RecordingStream.class),
        "readVarint",
        Type.getMethodDescriptor(Type.LONG_TYPE),
        false); // [stream, long]
    mv.visitInsn(Opcodes.L2I); // [stream, int]
    mv.visitInsn(Opcodes.DUP); // [stream, int, int]
    mv.visitVarInsn(Opcodes.ISTORE, arraySizeIdx); // [stream, int]
    mv.visitJumpInsn(Opcodes.IFEQ, l2); // [stream]
    mv.visitLabel(l1);
    mv.visitInsn(Opcodes.DUP); // [stream, stream]
    mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        Type.getInternalName(RecordingStream.class),
        operation,
        Type.getMethodDescriptor(dataType),
        false); // [stream, value]
    mv.visitInsn(dataType.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP); // [stream]
    mv.visitVarInsn(Opcodes.ILOAD, arraySizeIdx); // [stream, int]
    mv.visitLdcInsn(1); // [stream, int, 1]
    mv.visitInsn(Opcodes.ISUB); // [stream, int]
    mv.visitInsn(Opcodes.DUP); // [stream, int, int]
    mv.visitVarInsn(Opcodes.ISTORE, arraySizeIdx); // [stream, int]
    mv.visitJumpInsn(Opcodes.IFNE, l1); // [stream]
    mv.visitInsn(Opcodes.POP); // []
    mv.visitJumpInsn(Opcodes.GOTO, l3);
    mv.visitLabel(l2); // [stream]
    mv.visitInsn(Opcodes.POP); // []
    mv.visitLabel(l3); // []
  }

  private static void skipStringArray(int arraySizeIdx, MethodVisitor mv) {
    // stack: [stream]
    Label l1 = new Label();
    Label l2 = new Label();
    mv.visitInsn(Opcodes.DUP); // [stream, stream]
    mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        Type.getInternalName(RecordingStream.class),
        "readVarint",
        Type.getMethodDescriptor(Type.LONG_TYPE),
        false); // [stream, long]
    mv.visitInsn(Opcodes.L2I); // [stream, int]
    mv.visitInsn(Opcodes.DUP); // [stream, int, int]
    mv.visitVarInsn(Opcodes.ISTORE, arraySizeIdx); // [stream, int]
    mv.visitJumpInsn(Opcodes.IFEQ, l2); // [stream]
    mv.visitLabel(l1);
    mv.visitInsn(Opcodes.DUP); // [stream, stream]
    mv.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        Type.getInternalName(ParsingUtils.class),
        "skipUTF8",
        Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(RecordingStream.class)),
        false); // [stream]
    mv.visitVarInsn(Opcodes.ILOAD, arraySizeIdx); // [stream, int]
    mv.visitLdcInsn(1); // [stream, int, 1]
    mv.visitInsn(Opcodes.ISUB); // [stream, int]
    mv.visitInsn(Opcodes.DUP); // [stream, int, int]
    mv.visitVarInsn(Opcodes.ISTORE, arraySizeIdx); // [stream, int]
    mv.visitJumpInsn(Opcodes.IFNE, l1); // [stream]
    mv.visitLabel(l2); // [stream]
    mv.visitInsn(Opcodes.POP); // []
    mv.visitInsn(Opcodes.RETURN);
  }

  private static void skipObjectArray(
      MetadataClass fldType,
      int arraySizeIdx,
      int streamIdx,
      int lastVarIdx,
      boolean keepStream,
      MethodVisitor mv) {
    // stack: [stream]
    Label l1 = new Label();
    Label l2 = new Label();
    Label l3 = new Label();
    mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        Type.getInternalName(RecordingStream.class),
        "readVarint",
        Type.getMethodDescriptor(Type.LONG_TYPE),
        false); // [long]
    mv.visitInsn(Opcodes.L2I); // [int]
    addLogIntWithMsg(mv, "Array size: "); // [int]
    mv.visitInsn(Opcodes.DUP); // [int, int]
    mv.visitJumpInsn(Opcodes.IFEQ, l2); // [int]
    mv.visitVarInsn(Opcodes.ISTORE, arraySizeIdx); // []
    mv.visitVarInsn(Opcodes.ALOAD, streamIdx); // [stream]
    mv.visitLabel(l1);
    skipSimpleField(fldType, streamIdx, lastVarIdx, true, mv); // [stream]
    mv.visitIincInsn(arraySizeIdx, -1); // [stream]
    mv.visitVarInsn(Opcodes.ILOAD, arraySizeIdx); // [stream, int]
    mv.visitJumpInsn(Opcodes.IFNE, l1); // [stream]
    if (!keepStream) {
      mv.visitInsn(Opcodes.POP); // []
    }
    mv.visitJumpInsn(Opcodes.GOTO, l3);
    mv.visitLabel(l2); // [int]
    mv.visitInsn(Opcodes.POP); // []
    mv.visitLabel(l3); // []
  }

  private static void skipSimpleField(
      MetadataClass fldType, int streamIdx, int lastVarIdx, boolean keepStream, MethodVisitor mv) {
    // stack: [stream]
    String fldTypeName = fldType.getName();
    switch (fldTypeName) {
      case "byte", "boolean":
        {
          if (keepStream) {
            mv.visitInsn(Opcodes.DUP); // [stream, stream]
          }
          mv.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL,
              Type.getInternalName(RecordingStream.class),
              "read",
              Type.getMethodDescriptor(Type.BYTE_TYPE),
              false); // [<stream>, byte]
          mv.visitInsn(Opcodes.POP); // [<stream>]
          return;
        }
      case "short":
      case "char":
      case "int":
      case "long":
        {
          if (keepStream) {
            mv.visitInsn(Opcodes.DUP); // [stream, stream]
          }
          mv.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL,
              Type.getInternalName(RecordingStream.class),
              "readVarint",
              Type.getMethodDescriptor(Type.LONG_TYPE),
              false); // [<stream>, long]
          mv.visitInsn(Opcodes.POP2); // [<stream>]
          return;
        }
      case "float":
        {
          if (keepStream) {
            mv.visitInsn(Opcodes.DUP); // [stream, stream]
          }
          mv.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL,
              Type.getInternalName(RecordingStream.class),
              "readFloat",
              Type.getMethodDescriptor(Type.FLOAT_TYPE),
              false); // [<stream>, float]
          mv.visitInsn(Opcodes.POP); // [<stream>]
          return;
        }
      case "double":
        {
          if (keepStream) {
            mv.visitInsn(Opcodes.DUP); // [stream, stream]
          }
          mv.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL,
              Type.getInternalName(RecordingStream.class),
              "readDouble",
              Type.getMethodDescriptor(Type.DOUBLE_TYPE),
              false); // [<stream>, double]
          mv.visitInsn(Opcodes.POP2); // [<stream>]
          return;
        }
      case "java.lang.String":
        {
          if (keepStream) {
            mv.visitInsn(Opcodes.DUP); // [stream, stream]
          }
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              Type.getInternalName(ParsingUtils.class),
              "skipUTF8",
              Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(RecordingStream.class)),
              false); // [<stream>]
          return;
        }
    }
    for (MetadataField fld : fldType.getFields()) {
      mv.visitInsn(Opcodes.DUP); // [stream, stream]
      addFieldSkipper(mv, fld, streamIdx, lastVarIdx); // [stream]
    }
    if (!keepStream) {
      mv.visitInsn(Opcodes.POP); // []
    }
  }

  private static void handleArrayField(
      MetadataField fld,
      String className,
      int streamIdx,
      int metadataIdx,
      int lastVarIdx,
      TypedParserContext context,
      MethodVisitor mv) {
    // stack: [this, stream]
    int arrayCounterIdx = lastVarIdx + 1;
    int arraySizeIdx = arrayCounterIdx + 1;
    lastVarIdx = arraySizeIdx;

    String fldTypeName = fld.getType().getName();
    Type fldType = null;
    Type dataType = null;
    int arrayOpcode = 0;

    switch (fldTypeName) {
      case "byte", "boolean":
        {
          fldType = fldTypeName.equals("byte") ? Type.BYTE_TYPE : Type.BOOLEAN_TYPE;
          arrayOpcode = fldTypeName.equals("byte") ? Opcodes.T_BYTE : Opcodes.T_BOOLEAN;
          dataType = Type.BYTE_TYPE;
          break;
        }
      case "short", "char", "int", "long":
        {
          dataType = Type.LONG_TYPE;
          switch (fldTypeName) {
            case "short":
              {
                fldType = Type.SHORT_TYPE;
                arrayOpcode = Opcodes.T_SHORT;
                break;
              }
            case "char":
              {
                fldType = Type.CHAR_TYPE;
                arrayOpcode = Opcodes.T_CHAR;
                break;
              }
            case "int":
              {
                fldType = Type.INT_TYPE;
                arrayOpcode = Opcodes.T_INT;
                break;
              }
            case "long":
              {
                fldType = Type.LONG_TYPE;
                arrayOpcode = Opcodes.T_LONG;
                break;
              }
          }
          break;
        }
      case "float":
        {
          dataType = Type.FLOAT_TYPE;
          fldType = Type.FLOAT_TYPE;
          arrayOpcode = Opcodes.T_FLOAT;
          break;
        }
      case "double":
        {
          dataType = Type.DOUBLE_TYPE;
          fldType = Type.DOUBLE_TYPE;
          arrayOpcode = Opcodes.T_DOUBLE;
          break;
        }
    }
    if (fldType != null) {
      readIntoPrimitiveArray(
          mv,
          className,
          fld.getName(),
          fldType,
          arrayOpcode,
          dataType,
          streamIdx,
          arraySizeIdx,
          arrayCounterIdx);
    } else if (fldTypeName.equals("java.lang.String")) {
      readIntoStringArray(className, fld.getName(), streamIdx, arraySizeIdx, arrayCounterIdx, mv);
    } else {
      // fall-back to the registered deserializer
      Class<?> fldClz = context.getClassTargetType(fld.getType().getName());
      if (fldClz == null) {
        throw new RuntimeException("No class found for type: " + fld.getType().getName());
      }
      readIntoObjectArray(
          className,
          fld.getName(),
          fld.getType(),
          Type.getType(fldClz),
          fldClz,
          streamIdx,
          arraySizeIdx,
          arrayCounterIdx,
          metadataIdx,
          lastVarIdx,
          mv);
    }
  }

  private static void readIntoPrimitiveArray(
      MethodVisitor mv,
      String className,
      String fldName,
      Type fldType,
      int arrayType,
      Type dataType,
      int streamIdx,
      int arraySizeIdx,
      int arrayCounterIdx) {
    String operation = getPrimitiveReadOperation(fldType);
    // stack: [this, stream]
    Label l1 = new Label();
    Label l2 = new Label();
    mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        Type.getInternalName(RecordingStream.class),
        "readVarint",
        Type.getMethodDescriptor(Type.LONG_TYPE),
        false); // [this, long]
    mv.visitInsn(Opcodes.L2I); // [this, int]
    mv.visitInsn(Opcodes.DUP); // [this, int, int]
    mv.visitVarInsn(Opcodes.ISTORE, arraySizeIdx); // [this, int]
    mv.visitIntInsn(Opcodes.NEWARRAY, arrayType); // [this, array]
    mv.visitLdcInsn(0); // [this, array, int]
    mv.visitVarInsn(Opcodes.ISTORE, arrayCounterIdx); // [this, array]
    mv.visitLabel(l1);
    mv.visitVarInsn(Opcodes.ILOAD, arraySizeIdx); // [this, array, int]
    mv.visitVarInsn(Opcodes.ILOAD, arrayCounterIdx); // [this, array, int, int]
    mv.visitJumpInsn(Opcodes.IF_ICMPEQ, l2); // [this, array]
    mv.visitInsn(Opcodes.DUP); // [this, array, array]
    mv.visitVarInsn(Opcodes.ILOAD, arrayCounterIdx); // [this, array, array, int]
    mv.visitVarInsn(Opcodes.ALOAD, streamIdx); // [this, array, array, int, stream]
    mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        Type.getInternalName(RecordingStream.class),
        operation,
        Type.getMethodDescriptor(dataType),
        false); // [this, array, array, int, value]
    if (dataType.getSort() == Type.LONG && fldType.getSort() != Type.LONG) {
      // the value is read as a long varint, need to convert to the target type (int, short, char)
      mv.visitInsn(Opcodes.L2I); // [this, array, array, int, int_value]
    }
    mv.visitInsn(fldType.getOpcode(Opcodes.IASTORE)); // [this, array]
    mv.visitIincInsn(arrayCounterIdx, 1); // [this, array]
    mv.visitJumpInsn(Opcodes.GOTO, l1); // [this, array]
    mv.visitLabel(l2);
    mv.visitFieldInsn(Opcodes.PUTFIELD, className, fldName, "[" + fldType.getDescriptor()); // []
  }

  private static void readIntoStringArray(
      String className,
      String fldName,
      int streamIdx,
      int arraySizeIdx,
      int arrayCounterIdx,
      MethodVisitor mv) {
    // stack: [this, stream]
    Label l1 = new Label();
    Label l2 = new Label();
    mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        Type.getInternalName(RecordingStream.class),
        "readVarint",
        Type.getMethodDescriptor(Type.LONG_TYPE),
        false); // [this, long]
    mv.visitInsn(Opcodes.L2I); // [this, int]
    mv.visitInsn(Opcodes.DUP); // [this, int, int]
    mv.visitVarInsn(Opcodes.ISTORE, arraySizeIdx); // [this, int]
    mv.visitTypeInsn(
        Opcodes.ANEWARRAY, Type.getType(String.class).getInternalName()); // [this, array]
    mv.visitLdcInsn(0); // [this, array, int]
    mv.visitVarInsn(Opcodes.ISTORE, arrayCounterIdx); // [this, array]
    mv.visitLabel(l1);
    mv.visitVarInsn(Opcodes.ILOAD, arraySizeIdx); // [this, array, int]
    mv.visitVarInsn(Opcodes.ILOAD, arrayCounterIdx); // [this, array, int, int]
    mv.visitJumpInsn(Opcodes.IF_ICMPEQ, l2); // [this, array]
    mv.visitInsn(Opcodes.DUP); // [this, array, array]
    mv.visitVarInsn(Opcodes.ILOAD, arrayCounterIdx); // [this, array, array, int]
    mv.visitVarInsn(Opcodes.ALOAD, streamIdx); // [this, array, array, int, stream]
    mv.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        Type.getInternalName(ParsingUtils.class),
        "readUTF8",
        Type.getMethodDescriptor(Type.getType(String.class), Type.getType(RecordingStream.class)),
        false); // [this, string]
    mv.visitInsn(Opcodes.AASTORE); // [this, array]
    mv.visitIincInsn(arrayCounterIdx, 1); // [this, array]
    mv.visitJumpInsn(Opcodes.GOTO, l1); // [this, array]
    mv.visitLabel(l2);
    mv.visitFieldInsn(
        Opcodes.PUTFIELD,
        className,
        fldName,
        "[" + Type.getType(String.class).getDescriptor()); // []
  }

  private static void readIntoObjectArray(
      String className,
      String fldName,
      MetadataClass fldClassType,
      Type fldType,
      Class<?> fldClz,
      int streamIdx,
      int arraySizeIdx,
      int arrayCounterIdx,
      int metadataIdx,
      int lastVarIdx,
      MethodVisitor mv) {
    // stack: [this, stream]
    int deserializerIdx = lastVarIdx + 1;
    mv.visitVarInsn(Opcodes.ALOAD, metadataIdx); // [this, stream, metadata]
    mv.visitLdcInsn(fldClassType.getId()); // [this, stream, metadata, long]
    mv.visitMethodInsn(
        Opcodes.INVOKEINTERFACE,
        Type.getInternalName(MetadataLookup.class),
        "getClass",
        Type.getMethodDescriptor(Type.getType(MetadataClass.class), Type.LONG_TYPE),
        true); // [this, stream, mclass]
    mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        Type.getInternalName(MetadataClass.class),
        "getDeserializer",
        Type.getMethodDescriptor(Type.getType(Deserializer.class)),
        false); // [this, stream, deserializer]
    mv.visitVarInsn(Opcodes.ASTORE, deserializerIdx); // [this, stream]

    Label l1 = new Label();
    Label l2 = new Label();
    mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        Type.getInternalName(RecordingStream.class),
        "readVarint",
        Type.getMethodDescriptor(Type.LONG_TYPE),
        false); // [this, long]
    mv.visitInsn(Opcodes.L2I); // [this, int]
    mv.visitInsn(Opcodes.DUP); // [this, int, int]
    mv.visitVarInsn(Opcodes.ISTORE, arraySizeIdx); // [this, int]
    mv.visitTypeInsn(Opcodes.ANEWARRAY, fldType.getInternalName()); // [this, array]
    mv.visitLdcInsn(0); // [this, array, int]
    mv.visitVarInsn(Opcodes.ISTORE, arrayCounterIdx); // [this, array]
    mv.visitLabel(l1);
    mv.visitVarInsn(Opcodes.ILOAD, arraySizeIdx); // [this, array, int]
    mv.visitJumpInsn(Opcodes.IFEQ, l2); // [this, array]
    mv.visitInsn(Opcodes.DUP); // [this, array, array]
    mv.visitVarInsn(Opcodes.ILOAD, arrayCounterIdx); // [this, array, array, int]
    mv.visitVarInsn(Opcodes.ALOAD, deserializerIdx); // [this, array, array, int, deserializer]
    mv.visitVarInsn(Opcodes.ALOAD, streamIdx); // [this, array, array, int, deserializer,  stream]
    mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        Type.getInternalName(Deserializer.class),
        "deserialize",
        Type.getMethodDescriptor(Type.getType(Object.class), Type.getType(RecordingStream.class)),
        false); // [this, obj]
    mv.visitTypeInsn(Opcodes.CHECKCAST, fldType.getInternalName()); // [this, fldval]
    mv.visitInsn(Opcodes.AASTORE); // [this, array]
    mv.visitIincInsn(arrayCounterIdx, 1); // [this, array]
    mv.visitIincInsn(arraySizeIdx, -1); // [this, array]
    mv.visitJumpInsn(Opcodes.GOTO, l1); // [this, array]
    mv.visitLabel(l2);
    mv.visitFieldInsn(Opcodes.PUTFIELD, className, fldName, "[" + fldType.getDescriptor()); // []
  }

  private static String getPrimitiveReadOperation(Type type) {
    return switch (type.getSort()) {
      case Type.BYTE -> "read";
      case Type.BOOLEAN -> "read";
      case Type.SHORT -> "readVarint";
      case Type.CHAR -> "readVarint";
      case Type.INT -> "readVarint";
      case Type.LONG -> "readVarint";
      case Type.FLOAT -> "readFloat";
      case Type.DOUBLE -> "readDouble";
      default -> throw new RuntimeException("Unexpected type: " + type.getDescriptor());
    };
  }

  private static void handleSimpleField(
      MetadataField fld,
      String className,
      int metadataIdx,
      int lastVarIdx,
      TypedParserContext context,
      MethodVisitor mv) {
    // stack: [this, stream]);
    String fldTypeName = fld.getType().getName();
    switch (fldTypeName) {
      case "byte", "boolean":
        {
          mv.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL,
              Type.getInternalName(RecordingStream.class),
              "read",
              Type.getMethodDescriptor(Type.BYTE_TYPE),
              false); // [this, int]
          mv.visitFieldInsn(
              Opcodes.PUTFIELD,
              className,
              fld.getName(),
              fld.getType().getName().equals("byte")
                  ? Type.BYTE_TYPE.getDescriptor()
                  : Type.BOOLEAN_TYPE.getDescriptor()); // []
          break;
        }
      case "short", "char", "int", "long":
        {
          mv.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL,
              Type.getInternalName(RecordingStream.class),
              "readVarint",
              Type.getMethodDescriptor(Type.LONG_TYPE),
              false); // [this, long]
          switch (fldTypeName) {
            case "short":
              {
                mv.visitInsn(Opcodes.L2I); // [this, int]
                mv.visitFieldInsn(
                    Opcodes.PUTFIELD,
                    className,
                    fld.getName(),
                    Type.SHORT_TYPE.getDescriptor()); // []
                break;
              }
            case "char":
              {
                mv.visitInsn(Opcodes.L2I); // [this, int]
                mv.visitFieldInsn(
                    Opcodes.PUTFIELD,
                    className,
                    fld.getName(),
                    Type.CHAR_TYPE.getDescriptor()); // []
                break;
              }
            case "int":
              {
                mv.visitInsn(Opcodes.L2I); // [this, int]
                mv.visitFieldInsn(
                    Opcodes.PUTFIELD,
                    className,
                    fld.getName(),
                    Type.INT_TYPE.getDescriptor()); // []
                break;
              }
            case "long":
              {
                mv.visitFieldInsn(
                    Opcodes.PUTFIELD,
                    className,
                    fld.getName(),
                    Type.LONG_TYPE.getDescriptor()); // []
                break;
              }
          }
          break;
        }
      case "float":
        {
          mv.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL,
              Type.getInternalName(RecordingStream.class),
              "readFloat",
              Type.getMethodDescriptor(Type.FLOAT_TYPE),
              false); // [this, float]
          mv.visitFieldInsn(
              Opcodes.PUTFIELD, className, fld.getName(), Type.FLOAT_TYPE.getDescriptor()); // []
          break;
        }
      case "double":
        {
          mv.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL,
              Type.getInternalName(RecordingStream.class),
              "readDouble",
              Type.getMethodDescriptor(Type.DOUBLE_TYPE),
              false); // [this, double]
          mv.visitFieldInsn(
              Opcodes.PUTFIELD, className, fld.getName(), Type.DOUBLE_TYPE.getDescriptor()); // []
          break;
        }
      case "java.lang.String":
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            Type.getInternalName(ParsingUtils.class),
            "readUTF8",
            Type.getMethodDescriptor(
                Type.getType(String.class), Type.getType(RecordingStream.class)),
            false); // [this, string]
        mv.visitFieldInsn(
            Opcodes.PUTFIELD,
            className,
            fld.getName(),
            Type.getType(String.class).getDescriptor()); // []
        break;
      default:
        {
          // fall-back to the metadata deserializer
          Class<?> fldClz = context.getClassTargetType(fldTypeName);
          mv.visitVarInsn(Opcodes.ALOAD, metadataIdx); // [this, stream, metadata]
          mv.visitLdcInsn(fld.getType().getId()); // [this, stream, metadata, long]
          mv.visitMethodInsn(
              Opcodes.INVOKEINTERFACE,
              Type.getInternalName(MetadataLookup.class),
              "getClass",
              Type.getMethodDescriptor(Type.getType(MetadataClass.class), Type.LONG_TYPE),
              true); // [this, stream, mclass]
          mv.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL,
              Type.getInternalName(MetadataClass.class),
              "getDeserializer",
              Type.getMethodDescriptor(Type.getType(Deserializer.class)),
              false); // [this, stream, deserializer]
          mv.visitInsn(Opcodes.SWAP); // [this, deserializer, stream]
          mv.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL,
              Type.getInternalName(Deserializer.class),
              "deserialize",
              Type.getMethodDescriptor(
                  Type.getType(Object.class), Type.getType(RecordingStream.class)),
              false); // [this, value]
          mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(fldClz)); // [this, fldval]
          mv.visitFieldInsn(
              Opcodes.PUTFIELD, className, fld.getName(), Type.getDescriptor(fldClz)); // []
        }
    }
  }

  static void prepareConstructor(
      ClassVisitor cv,
      String clzName,
      MetadataClass clz,
      List<MetadataField> allFields,
      Set<MetadataField> appliedFields,
      TypedParserContext context) {
    MethodVisitor mv =
        cv.visitMethod(
            Opcodes.ACC_PUBLIC,
            "<init>",
            Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(RecordingStream.class)),
            null,
            null);
    mv.visitCode();
    int contextIdx = 2;
    int meteadataIdx = 3;
    int lastVarIdx = meteadataIdx; // guard

    mv.visitVarInsn(Opcodes.ALOAD, 0);
    mv.visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        Type.getInternalName(Object.class),
        "<init>",
        Type.getMethodDescriptor(Type.VOID_TYPE),
        false);
    // store context field
    mv.visitVarInsn(Opcodes.ALOAD, 0); // [this]
    mv.visitVarInsn(Opcodes.ALOAD, 1); // [this, stream]
    mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        Type.getInternalName(RecordingStream.class),
        "getContext",
        Type.getMethodDescriptor(Type.getType(ParserContext.class)),
        false); // [this, ctx]
    mv.visitTypeInsn(
        Opcodes.CHECKCAST, Type.getInternalName(TypedParserContext.class)); // [this, ctx]
    mv.visitInsn(Opcodes.DUP); // [this, ctx, ctx]
    mv.visitVarInsn(Opcodes.ASTORE, contextIdx); // [this, ctx]
    mv.visitInsn(Opcodes.DUP); // [this, ctx, ctx]
    mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        Type.getInternalName(TypedParserContext.class),
        "getMetadataLookup",
        Type.getMethodDescriptor(Type.getType(MetadataLookup.class)),
        false); // [this, ctx, metadata]
    mv.visitVarInsn(Opcodes.ASTORE, meteadataIdx); // [this, ctx]
    mv.visitFieldInsn(
        Opcodes.PUTFIELD,
        clzName.replace('.', '/'),
        "context",
        Type.getDescriptor(TypedParserContext.class)); // []

    for (MetadataField fld : allFields) {
      ;
      if (!appliedFields.contains(fld)) {
        // skip
        mv.visitVarInsn(Opcodes.ALOAD, 1); // [stream]
        addFieldSkipper(mv, fld, 1, meteadataIdx); // []
        continue;
      }
      mv.visitVarInsn(Opcodes.ALOAD, 0); // [this]
      mv.visitVarInsn(Opcodes.ALOAD, 1); // [this, stream]
      addFieldLoader(
          mv, fld, clzName.replace('.', '/'), 1, meteadataIdx, lastVarIdx, context); // []
    }
    mv.visitInsn(Opcodes.RETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  static void prepareFactory(ClassVisitor cv, String clzInternalName) {
    // public static Object create(RecordingStream stream) { return new <clz>(stream); }
    MethodVisitor mv =
        cv.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "create",
            Type.getMethodDescriptor(
                Type.getType(Object.class), Type.getType(RecordingStream.class)),
            null,
            null);
    mv.visitCode();
    mv.visitTypeInsn(Opcodes.NEW, clzInternalName);
    mv.visitInsn(Opcodes.DUP);
    mv.visitVarInsn(Opcodes.ALOAD, 0);
    mv.visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        clzInternalName,
        "<init>",
        Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(RecordingStream.class)),
        false);
    mv.visitInsn(Opcodes.ARETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  static void prepareSkipHandler(ClassVisitor cv, MetadataClass clz) {
    MethodVisitor mv =
        cv.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            "skip",
            Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(RecordingStream.class)),
            null,
            null);
    mv.visitCode();
    int streamIdx = 0;
    int lastVarIdx = streamIdx;

    for (MetadataField fld : clz.getFields()) {
      mv.visitVarInsn(Opcodes.ALOAD, streamIdx); // [stream]
      addFieldSkipper(mv, fld, 0, lastVarIdx); // []
    }
    mv.visitInsn(Opcodes.RETURN);
    try {
      mv.visitMaxs(0, 0);
    } catch (Throwable t) {
      t.printStackTrace();
    }
    mv.visitEnd();
  }

  static void prepareEmptyMethod(Method m, ClassVisitor cv) {
    MethodVisitor mv =
        cv.visitMethod(Opcodes.ACC_PUBLIC, m.getName(), Type.getMethodDescriptor(m), null, null);
    mv.visitCode();
    Type retType = Type.getReturnType(m);
    if (retType.getDimensions() > 1) {
      mv.visitInsn(Opcodes.ACONST_NULL);
      mv.visitInsn(Opcodes.ARETURN);
    } else {
      switch (retType.getSort()) {
        case Type.VOID:
          mv.visitInsn(Opcodes.RETURN);
          break;
        case Type.OBJECT:
          mv.visitInsn(Opcodes.ACONST_NULL);
          mv.visitInsn(Opcodes.ARETURN);
          break;
        case Type.BYTE:
        case Type.BOOLEAN:
        case Type.SHORT:
        case Type.CHAR:
        case Type.INT:
          mv.visitLdcInsn(0);
          mv.visitInsn(Opcodes.IRETURN);
          break;
        case Type.LONG:
          mv.visitLdcInsn(0L);
          mv.visitInsn(Opcodes.LRETURN);
          break;
        case Type.FLOAT:
          mv.visitLdcInsn(0.0f);
          mv.visitInsn(Opcodes.FRETURN);
          break;
        case Type.DOUBLE:
          mv.visitLdcInsn(0.0d);
          mv.visitInsn(Opcodes.DRETURN);
          break;
        default:
          throw new RuntimeException("Unsupported type: " + retType);
      }
    }

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  @SuppressWarnings("unchecked")
  public static <T> Deserializer<T> generateDeserializer(MetadataClass clz)
      throws JafarSerializationException {
    ParserContext ctx = clz.getContext();
    if (!(ctx instanceof TypedParserContext)) {
      throw new JafarSerializationException("Invalid parser context type", clz.getName());
    }
    TypedParserContext context = (TypedParserContext) ctx;
    Class<T> target = (Class<T>) context.getClassTargetType(clz.getName());
    if (target != null && !target.isInterface()) {
      throw new RuntimeException("Unsupported type: " + clz.getName());
    }
    if (target == null) {
      // No target mapping requested; generate a skipper-only deserializer
      return new Deserializer.Generated<>(null, null, TypeSkipper.createSkipper(clz));
    }
    String origClzName = target != null ? target.getName() : clz.getName();
    String origSimpleName = target != null ? target.getSimpleName() : clz.getSimpleName();
    String clzName =
        CodeGenerator.class.getPackage().getName()
            + "."
            + (target != null ? target.getSimpleName() : clz.getSimpleName())
            + "$"
            + context.getChunkIndex();
    // generate handler class
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    cw.visit(
        Opcodes.V11,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
        clzName.replace('.', '/'),
        null,
        "java/lang/Object",
        target != null ? new String[] {origClzName.replace('.', '/')} : null);
    cw.visitField(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
            "context",
            Type.getDescriptor(TypedParserContext.class),
            null,
            null)
        .visitEnd();

    Map<String, Set<FieldMapping>> fieldMap = new HashMap<>();
    Set<String> usedAttributes = collectUsedAttributes(target, fieldMap);

    Deque<MetadataClass> stack = new ArrayDeque<>();
    stack.push(clz);
    // TODO ignore inheritence for now
    //                        while (true) {
    //                            MetadataClass superMd = context.getMetadataLookup().;
    //                            String superName = mdClass.getSuperType();
    //                            if (superName != null) {
    //
    // stack.push(context.getMetadataLookup().getClass(mdClass.getId()));
    //                            } else {
    //                                break;
    //                            }
    //                        }

    List<MetadataField> allFields = new ArrayList<>();
    Set<MetadataField> appliedFields = new HashSet<>();
    Set<String> generatedMethods = new HashSet<>();

    while (!stack.isEmpty()) {
      MetadataClass current = stack.pop();
      if (target != null) {
        for (MetadataField field : current.getFields()) {
          String fieldName = field.getName().replace('.', '_');
          allFields.add(field);
          if (usedAttributes.contains(fieldName)) {
            appliedFields.add(field);
          }

          MetadataClass fldType = field.getType();
          while (fldType.isSimpleType()) {
            fldType = fldType.getFields().getFirst().getType();
          }

          String fldTypeNameResolved = fldType.getName();
          Class<?> fldClz =
              switch (fldTypeNameResolved) {
                case "byte" -> byte.class;
                case "boolean" -> boolean.class;
                case "short" -> short.class;
                case "char" -> char.class;
                case "int" -> int.class;
                case "long" -> long.class;
                case "float" -> float.class;
                case "double" -> double.class;
                case "java.lang.String" -> String.class;
                default -> context.getClassTargetType(fldTypeNameResolved);
              };
          boolean withConstantPool = field.hasConstantPool();
          Set<FieldMapping> mappings = fieldMap.get(fieldName);
          if (mappings == null) {
            mappings = Set.of(new FieldMapping(fieldName, false));
          }
          boolean generateRefField = true;
          for (FieldMapping mapping : mappings) {
            if (withConstantPool) {
              boolean isArray = field.getDimension() > 0;
              if (mapping.raw() && isArray) {
                throw new RuntimeException("CP identity is not supported for arrays");
              }
              handleFieldRef(
                  cw,
                  clzName,
                  field.getType().getId(),
                  isArray,
                  fldClz,
                  fieldName,
                  mapping,
                  generateRefField);
              generateRefField = false;
            } else {
              if (mapping.raw()) {
                throw new RuntimeException("Field " + fieldName + " is not a constant pool entry");
              }
              handleField(
                  cw, clzName, field.getDimension() > 0, fldClz, fieldName, mapping.method());
            }
            generatedMethods.add(mapping.method());
          }
        }

        prepareConstructor(cw, clzName, current, allFields, appliedFields, context);
      }
      prepareSkipHandler(cw, current);
      ;
    }
    if (target != null) {
      Deque<Class<?>> checkClasses = new ArrayDeque<>();
      checkClasses.push(target);
      Class<?> checkClass = null;
      while ((checkClass = checkClasses.poll()) != null) {
        for (Method m : checkClass.getMethods()) {
          if (!generatedMethods.contains(m.getName())) {
            prepareEmptyMethod(m, cw);
          }
        }
        checkClasses.addAll(Arrays.stream(checkClass.getInterfaces()).toList());
      }
      // add factory method returning the created instance
      prepareFactory(cw, clzName.replace('.', '/'));
      cw.visitEnd();
    }
    byte[] classData = cw.toByteArray();

    Path debugPath = null;
    if (log.isDebugEnabled()) {
      debugPath = Paths.get("/tmp/" + origSimpleName + ".class");
      try {
        Files.write(debugPath, classData);
      } catch (Exception e) {
        log.warn("Failed to write debug bytecode to {}", debugPath, e);
        debugPath = null;
      }
    }

    try {
      // Define the class using the best available strategy for the current JDK
      Class<?> implClz;
      try {
        implClz = ClassDefiners.best().define(classData, CodeGenerator.class);
      } catch (Throwable t) {
        throw new Exception(t);
      }

      MethodHandle createHandle = null;
      if (target != null) {
        Method mCreate = implClz.getDeclaredMethod("create", RecordingStream.class);
        mCreate.setAccessible(true);
        createHandle = MethodHandles.lookup().unreflect(mCreate);
      }

      Method mSkip = implClz.getDeclaredMethod("skip", RecordingStream.class);
      mSkip.setAccessible(true);
      MethodHandle skipHandle = MethodHandles.lookup().unreflect(mSkip);

      return new Deserializer.Generated<>(createHandle, skipHandle, TypeSkipper.createSkipper(clz));
    } catch (Exception e) {
      log.error(
          "Failed to load generated handler class for {}, bytecode can be found at {}",
          clz,
          debugPath,
          e);
      if (debugPath != null) {
        throw JafarSerializationException.bytecodeGenerationFailed(clz.getName(), debugPath, e);
      } else {
        throw JafarSerializationException.bytecodeGenerationFailed(clz.getName(), e);
      }
    }
  }

  private static Set<String> collectUsedAttributes(
      Class<?> clz, Map<String, Set<FieldMapping>> fieldToMethodMap) {
    Set<String> usedAttributes = new HashSet<>();
    Class<?> c = clz;
    while (c != null) {
      usedAttributes.addAll(
          Arrays.stream(c.getMethods())
              .filter(m -> m.getAnnotation(JfrIgnore.class) == null)
              .map(
                  m -> {
                    String name = m.getName();
                    JfrField fieldAnnotation = m.getAnnotation(JfrField.class);
                    if (fieldAnnotation != null) {
                      name = fieldAnnotation.value();
                      fieldToMethodMap
                          .computeIfAbsent(name, k -> new HashSet<>())
                          .add(new FieldMapping(m.getName(), fieldAnnotation.raw()));
                    } else {
                      fieldToMethodMap
                          .computeIfAbsent(name, k -> new HashSet<>())
                          .add(new FieldMapping(m.getName(), false));
                    }
                    return name;
                  })
              .collect(Collectors.toSet()));
      Class<?> superClz = c.getSuperclass();
      if (superClz != null && superClz.isInterface()) {
        c = superClz;
      } else {
        c = null;
      }
    }
    return usedAttributes;
  }
}

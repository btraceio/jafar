package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.jafar.parser.internal_api.ClassDefiner;
import io.jafar.parser.internal_api.ClassDefiners;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Basic sanity for runtime class definition strategies. */
public class ClassDefinerTest {

  private static byte[] trivialClassBytes(String internalName) {
    ClassWriter cw = new ClassWriter(0);
    cw.visit(
        Opcodes.V1_8, // bytecode version compatible with 8+
        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
        internalName,
        null,
        "java/lang/Object",
        null);

    // public <init>() { super(); }
    MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
    mv.visitCode();
    mv.visitVarInsn(Opcodes.ALOAD, 0);
    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
    mv.visitInsn(Opcodes.RETURN);
    mv.visitMaxs(1, 1);
    mv.visitEnd();

    // public static int ping() { return 42; }
    MethodVisitor ping =
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "ping", "()I", null, null);
    ping.visitCode();
    ping.visitLdcInsn(42);
    ping.visitInsn(Opcodes.IRETURN);
    ping.visitMaxs(1, 0);
    ping.visitEnd();

    cw.visitEnd();
    return cw.toByteArray();
  }

  @Test
  void definesWithBestDefiner() throws Throwable {
    byte[] bytes = trivialClassBytes("io/jafar/parser/Dyn$Best");
    ClassDefiner definer = ClassDefiners.best();
    assertNotNull(definer);
    Class<?> c = definer.define(bytes, ClassDefinerTest.class);
    assertNotNull(c);
    Method ping = c.getDeclaredMethod("ping");
    Object out = ping.invoke(null);
    assertEquals(42, ((Number) out).intValue());
  }

  @Test
  void definesWithLookupIfAvailable() throws Throwable {
    // Only run if Lookup#defineClass is present (JDK 9+)
    boolean lookupAvailable;
    try {
      MethodHandles.lookup().getClass().getMethod("defineClass", byte[].class);
      MethodHandles.class.getMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class);
      lookupAvailable = true;
    } catch (Throwable t) {
      lookupAvailable = false;
    }
    assumeTrue(lookupAvailable);

    byte[] bytes = trivialClassBytes("io/jafar/parser/Dyn$Lookup");
    ClassDefiner definer = ClassDefiners.byName("lookup");
    Class<?> c = definer.define(bytes, ClassDefinerTest.class);
    Method ping = c.getDeclaredMethod("ping");
    Object out = ping.invoke(null);
    assertEquals(42, ((Number) out).intValue());
  }

  @Test
  void definesWithUnsafeIfAvailable() throws Throwable {
    // Only run if sun.misc.Unsafe#defineAnonymousClass is present (JDK 8..?)
    boolean unsafeAvailable;
    try {
      Class<?> unsafeClz = Class.forName("sun.misc.Unsafe");
      unsafeClz.getMethod("defineAnonymousClass", Class.class, byte[].class, Object[].class);
      unsafeAvailable = true;
    } catch (Throwable t) {
      unsafeAvailable = false;
    }
    assumeTrue(unsafeAvailable);

    byte[] bytes = trivialClassBytes("io/jafar/parser/Dyn$Unsafe");
    ClassDefiner definer = ClassDefiners.byName("unsafe");
    Class<?> c = definer.define(bytes, ClassDefinerTest.class);
    Method ping = c.getDeclaredMethod("ping");
    Object out = ping.invoke(null);
    assertEquals(42, ((Number) out).intValue());
  }
}

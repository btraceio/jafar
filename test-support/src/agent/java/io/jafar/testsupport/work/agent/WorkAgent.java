package io.jafar.testsupport.work.agent;

import io.jafar.testsupport.work.WorkProbes;
import io.jafar.testsupport.work.WorkProbes.Probe;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The work-budget agent: a test-only {@code java.lang.instrument} agent that inserts a call to
 * {@code WorkCounters.hit(<counter>)} at each probe of {@link WorkProbes} — at a method's entry, or
 * just before a named call inside a method. Nothing else in the class changes, and classes no probe
 * names are returned untouched.
 *
 * <p>Started with {@code -javaagent:<jafar-work-agent jar>} by the {@code workTest} tasks only. The
 * jar bundles ASM; the inserted call is resolved by the class loader of the instrumented parser
 * class, which has {@code test-support} (and so {@code WorkCounters}) on its classpath.
 *
 * <p>Failures are never silent (R3): the JVM ignores an exception thrown by a transformer and loads
 * the class unchanged, which would leave a counter at 0. So every failure, and every probe whose
 * method or call site is not in the class its owner names, is recorded in the system property
 * {@link WorkProbes#ERRORS_PROPERTY}, which {@code WorkCounters.measure} fails on.
 */
public final class WorkAgent {

  private WorkAgent() {}

  /**
   * Agent entry point.
   *
   * @param args ignored
   * @param instrumentation the JVM's instrumentation
   */
  public static void premain(String args, Instrumentation instrumentation) {
    System.setProperty(WorkProbes.AGENT_PROPERTY, "installed");
    instrumentation.addTransformer(new Transformer(), false);
  }

  /** The result of instrumenting one class. */
  public static final class Result {
    /** The new class bytes, or {@code null} when no probe applies to the class. */
    public final byte[] bytes;

    /**
     * For each probe of {@link WorkProbes#PROBES}, how many places it was inserted in the class.
     */
    public final int[] sites;

    Result(byte[] bytes, int[] sites) {
      this.bytes = bytes;
      this.sites = sites;
    }
  }

  /**
   * Inserts the probes that apply to the class {@code className} into {@code classBytes}.
   *
   * @return the instrumented bytes and the per-probe site counts; bytes {@code null} when no probe
   *     names the class
   */
  public static Result instrument(String className, byte[] classBytes) {
    final List<Probe> probes = WorkProbes.PROBES;
    final int[] sites = new int[probes.size()];
    boolean any = false;
    for (Probe probe : probes) {
      any |= probe.matchesClass(className);
    }
    if (!any) {
      return new Result(null, sites);
    }
    ClassReader reader = new ClassReader(classBytes);
    // COMPUTE_MAXS only: a probe pushes one int and consumes it before the next original
    // instruction, so the existing stack map frames stay valid; only max_stack may grow by one.
    ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
    reader.accept(
        new ClassVisitor(Opcodes.ASM9, writer) {
          @Override
          public MethodVisitor visitMethod(
              int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            final List<Integer> entries = new ArrayList<>();
            final List<Integer> calls = new ArrayList<>();
            for (int i = 0; i < probes.size(); i++) {
              Probe probe = probes.get(i);
              if (probe.matchesClass(className)
                  && probe.method.equals(name)
                  && probe.descriptor.equals(descriptor)) {
                (probe.isCall() ? calls : entries).add(i);
              }
            }
            if (entries.isEmpty() && calls.isEmpty()) {
              return mv;
            }
            return new MethodVisitor(Opcodes.ASM9, mv) {
              @Override
              public void visitCode() {
                super.visitCode();
                for (int i : entries) {
                  count(this, probes.get(i).counter);
                  sites[i]++;
                }
              }

              @Override
              public void visitMethodInsn(
                  int opcode, String owner, String callee, String calleeDesc, boolean isInterface) {
                for (int i : calls) {
                  Probe probe = probes.get(i);
                  if (probe.calleeOwner.equals(owner)
                      && probe.calleeName.equals(callee)
                      && probe.calleeDescriptor.equals(calleeDesc)) {
                    count(this, probe.counter);
                    sites[i]++;
                  }
                }
                super.visitMethodInsn(opcode, owner, callee, calleeDesc, isInterface);
              }
            };
          }
        },
        0);
    return new Result(writer.toByteArray(), sites);
  }

  /** Emits {@code WorkCounters.hit(counter)}. */
  private static void count(MethodVisitor mv, int counter) {
    if (counter <= 5) {
      mv.visitInsn(Opcodes.ICONST_0 + counter);
    } else {
      mv.visitIntInsn(Opcodes.BIPUSH, counter);
    }
    mv.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        WorkProbes.COUNTERS_CLASS,
        WorkProbes.HIT_METHOD,
        WorkProbes.HIT_DESCRIPTOR,
        false);
  }

  /**
   * The probes {@code result} should have placed in {@code className} but did not, as error
   * messages. A probe with a nested-class owner ({@code $*}) is not judged per class: most nested
   * classes legitimately lack its method. {@code WorkProbesTest} checks those statically.
   */
  public static List<String> missing(String className, Result result) {
    List<String> missing = new ArrayList<>();
    List<Probe> probes = WorkProbes.PROBES;
    for (int i = 0; i < probes.size(); i++) {
      Probe probe = probes.get(i);
      if (probe.owner.equals(className) && result.sites[i] == 0) {
        missing.add(
            "probe "
                + probe.id()
                + " found no "
                + (probe.isCall()
                    ? "call to " + probe.calleeName + probe.calleeDescriptor + " in "
                    : "method ")
                + probe.method
                + probe.descriptor
                + " of "
                + className);
      }
    }
    return missing;
  }

  private static final class Transformer implements ClassFileTransformer {
    @Override
    public byte[] transform(
        ClassLoader loader,
        String className,
        Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain,
        byte[] classfileBuffer) {
      if (className == null) {
        return null;
      }
      try {
        Result result = instrument(className, classfileBuffer);
        if (result.bytes == null) {
          return null;
        }
        List<Probe> probes = WorkProbes.PROBES;
        for (int i = 0; i < probes.size(); i++) {
          if (result.sites[i] > 0) {
            append(
                WorkProbes.APPLIED_PROPERTY,
                probes.get(i).id() + "@" + className + "=" + result.sites[i]);
          }
        }
        for (String message : missing(className, result)) {
          append(WorkProbes.ERRORS_PROPERTY, message);
        }
        return result.bytes;
      } catch (Throwable t) {
        append(WorkProbes.ERRORS_PROPERTY, "instrumenting " + className + " failed: " + t);
        return null;
      }
    }

    private static synchronized void append(String property, String entry) {
      String current = System.getProperty(property);
      System.setProperty(property, current == null ? entry : current + ";" + entry);
    }
  }
}

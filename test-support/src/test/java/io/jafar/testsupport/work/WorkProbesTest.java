package io.jafar.testsupport.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.testsupport.work.WorkProbes.Probe;
import io.jafar.testsupport.work.agent.WorkAgent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Holds {@link WorkProbes} to the compiled parser: every probe is placed exactly once by the
 * agent's own transformation, in the classes on this classpath (parser-core and parser-codegen). A
 * renamed method, a changed descriptor, a second decode call added next to the counted one, or a
 * typed listener moved out of {@code TypedJafarParserImpl} fails here, at {@code
 * :test-support:test}, whether or not a work test happens to load the class.
 */
class WorkProbesTest {

  @Test
  void everyProbeIsPlacedExactlyOnce() throws IOException {
    int[] sites = new int[WorkProbes.PROBES.size()];
    for (String className : probedClasses()) {
      WorkAgent.Result result = WorkAgent.instrument(className, bytes(className));
      for (int i = 0; i < sites.length; i++) {
        sites[i] += result.sites[i];
      }
    }
    List<String> wrong = new ArrayList<>();
    for (int i = 0; i < sites.length; i++) {
      if (sites[i] != 1) {
        wrong.add(WorkProbes.PROBES.get(i).id() + " placed " + sites[i] + " times");
      }
    }
    assertTrue(wrong.isEmpty(), "every probe must be placed exactly once: " + wrong);
  }

  @Test
  void everyCounterHasAProbeAndATwoWordName() {
    Set<Integer> fed = new HashSet<>();
    for (Probe probe : WorkProbes.PROBES) {
      fed.add(probe.counter);
    }
    for (int i = 0; i < WorkProbes.COUNTERS.size(); i++) {
      String counter = WorkProbes.COUNTERS.get(i);
      assertTrue(fed.contains(i), counter + " has no probe");
      // scripts/work-budget-check.sh splits <entry>.<counter> by dropping the last two words
      assertTrue(counter.matches("[a-z]+\\.[a-z]+"), counter + " is not two dot-separated words");
    }
  }

  @Test
  void probeIdsAreUnique() {
    Set<String> ids = new HashSet<>();
    for (Probe probe : WorkProbes.PROBES) {
      assertTrue(ids.add(probe.id()), "duplicate probe " + probe.id());
    }
  }

  @Test
  void theCounterIndexFitsTheEmittedInstruction() {
    // WorkAgent pushes the index with ICONST_n or BIPUSH
    assertTrue(WorkProbes.COUNTERS.size() <= Byte.MAX_VALUE);
  }

  /** The owner classes of every probe, a {@code $*} owner expanded to its nested classes. */
  static Set<String> probedClasses() throws IOException {
    Set<String> classes = new LinkedHashSet<>();
    for (Probe probe : WorkProbes.PROBES) {
      if (probe.owner.endsWith("$*")) {
        String outer = probe.owner.substring(0, probe.owner.length() - 2);
        Set<String> nested = nestedClasses(outer);
        assertTrue(!nested.isEmpty(), outer + " declares no nested class for " + probe.id());
        classes.addAll(nested);
      } else {
        classes.add(probe.owner);
      }
    }
    return classes;
  }

  private static Set<String> nestedClasses(String outer) throws IOException {
    final Set<String> nested = new LinkedHashSet<>();
    new ClassReader(bytes(outer))
        .accept(
            new ClassVisitor(Opcodes.ASM9) {
              @Override
              public void visitInnerClass(
                  String name, String outerName, String innerName, int access) {
                if (name.startsWith(outer + "$")) {
                  nested.add(name);
                }
              }
            },
            ClassReader.SKIP_CODE);
    return nested;
  }

  static byte[] bytes(String className) throws IOException {
    try (InputStream in =
        WorkProbesTest.class.getClassLoader().getResourceAsStream(className + ".class")) {
      assertNotNull(in, className + " is not on the classpath");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) > 0) {
        out.write(buffer, 0, n);
      }
      return out.toByteArray();
    }
  }

  @Test
  void aClassNoProbeNamesIsLeftAlone() throws IOException {
    WorkAgent.Result result =
        WorkAgent.instrument(
            "io/jafar/testsupport/GoldenFile", bytes("io/jafar/testsupport/GoldenFile"));
    assertEquals(null, result.bytes);
  }
}

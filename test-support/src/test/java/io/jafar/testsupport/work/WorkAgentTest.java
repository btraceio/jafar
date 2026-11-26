package io.jafar.testsupport.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.testsupport.work.agent.WorkAgent;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The agent's transformation, outside a {@code -javaagent} JVM: what it inserts passes the JVM's
 * bytecode verifier, and a probe it cannot place is reported rather than skipped (R3).
 */
class WorkAgentTest {

  /**
   * Every class a probe instruments is defined from its instrumented bytes in a loader of its own
   * and initialized, which runs the verifier over every method — the stack map frames the agent
   * leaves untouched must still hold with the inserted calls.
   */
  @Test
  void instrumentedClassesPassTheVerifier() throws Exception {
    int instrumented = 0;
    for (String className : WorkProbesTest.probedClasses()) {
      WorkAgent.Result result = WorkAgent.instrument(className, WorkProbesTest.bytes(className));
      int sites = 0;
      for (int n : result.sites) {
        sites += n;
      }
      if (sites == 0) {
        continue; // a nested class of a $* owner without the probed method: returned unchanged
      }
      Class<?> defined = new OneClassLoader(className, result.bytes).load();
      assertEquals(className.replace('/', '.'), defined.getName());
      instrumented++;
    }
    // StreamingChunkParser, EventStream, EventStreamGenerated, the typed listener,
    // RecordingStream, TypeSkipper, CheckpointEvent, MutableConstantPool
    assertEquals(8, instrumented, "classes instrumented");
  }

  /** A class that lacks the probed method — here, the bytes of another class — is reported. */
  @Test
  void aProbeThatFindsNoMethodIsReported() throws IOException {
    String owner = "io/jafar/parser/internal_api/RecordingStream";
    WorkAgent.Result result =
        WorkAgent.instrument(owner, WorkProbesTest.bytes("io/jafar/testsupport/GoldenFile"));
    List<String> missing = WorkAgent.missing(owner, result);
    assertEquals(1, missing.size(), missing.toString());
    assertTrue(
        missing.get(0).contains("varints.read")
            && missing.get(0).contains("found no method readVarint()J of " + owner),
        missing.get(0));
  }

  /** A call probe whose method exists but whose call is gone is reported too. */
  @Test
  void aCallProbeThatFindsNoCallIsReported() throws IOException {
    // StreamingChunkParser's bytes under EventStream's name: no EventStream.onEvent there at all;
    // and the probes of StreamingChunkParser itself are not judged against a class they do not name
    String owner = "io/jafar/parser/impl/EventStream";
    WorkAgent.Result result =
        WorkAgent.instrument(
            owner, WorkProbesTest.bytes("io/jafar/parser/internal_api/StreamingChunkParser"));
    List<String> missing = WorkAgent.missing(owner, result);
    assertEquals(1, missing.size(), missing.toString());
    assertTrue(missing.get(0).contains("found no call to readValue"), missing.get(0));
  }

  /** Defines one class from given bytes; everything else comes from the parent. */
  private static final class OneClassLoader extends ClassLoader {
    private final String name;
    private final byte[] bytes;

    OneClassLoader(String internalName, byte[] bytes) {
      super(WorkAgentTest.class.getClassLoader());
      this.name = internalName.replace('/', '.');
      this.bytes = bytes;
    }

    Class<?> load() throws ClassNotFoundException {
      Class<?> c = loadClass(name, true);
      // initialization runs verification of every method of the class
      Class.forName(name, true, this);
      return c;
    }

    @Override
    protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
      if (!className.equals(name)) {
        return super.loadClass(className, resolve);
      }
      synchronized (getClassLoadingLock(className)) {
        Class<?> c = findLoadedClass(className);
        if (c == null) {
          c = defineClass(className, bytes, 0, bytes.length);
        }
        if (resolve) {
          resolveClass(c);
        }
        return c;
      }
    }
  }
}

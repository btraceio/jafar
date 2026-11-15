package io.jafar.testrecordings;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.openjdk.jmc.flightrecorder.writer.TypeImpl;
import org.openjdk.jmc.flightrecorder.writer.TypedValueImpl;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.TypedValue;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

/**
 * The shared vocabulary of one chunk: threads, classes, methods and stack traces, built once and
 * reused so that events point at shared constant-pool entries, as in a real recording.
 *
 * <p>Everything is derived from a seed, so the same seed yields the same bytes.
 */
final class ChunkContent {
  private static final String[] STATES = {"STATE_RUNNABLE", "STATE_SLEEPING", "STATE_PARKED"};

  final Recording recording;
  final Random random;
  final List<TypedValue> threads = new ArrayList<>();
  final List<TypedValue> stacks = new ArrayList<>();
  final List<TypedValue> threadStates = new ArrayList<>();
  private final TypeImpl stringType;

  ChunkContent(Recording recording, long seed, int threadCount, int stackCount) {
    this.recording = recording;
    this.random = new Random(seed);
    this.stringType = (TypeImpl) recording.getTypes().getType(Types.Builtin.STRING);

    Type threadType = recording.getType(Types.JDK.THREAD);
    for (int i = 0; i < threadCount; i++) {
      int id = i;
      threads.add(
          threadType.asValue(
              b ->
                  b.putField("osName", "worker-" + id)
                      .putField("osThreadId", 1000L + id)
                      .putField("javaName", "worker-" + id)
                      .putField("javaThreadId", 100L + id)));
    }

    Type stateType = Shapes.threadState(recording);
    for (String s : STATES) {
      threadStates.add(stateType.asValue(b -> b.putField("name", s)));
    }

    buildStacks(stackCount);
  }

  /**
   * Stack traces walk a layered call graph: each frame's method is drawn from a small set per
   * depth, so different stacks share callers and callees (fan-in and fan-out, as profilers see).
   */
  private void buildStacks(int stackCount) {
    Type symbolType = recording.getType(Types.JDK.SYMBOL);
    Type classType = recording.getType(Types.JDK.CLASS);
    Type methodType = recording.getType(Types.JDK.METHOD);
    Type frameType = recording.getType(Types.JDK.STACK_FRAME);
    Type frameKind = recording.getType(Types.JDK.FRAME_TYPE);
    Type stackType = recording.getType(Types.JDK.STACK_TRACE);

    TypedValue interpreted = frameKind.asValue(b -> b.putField("description", "Interpreted"));
    TypedValue compiled = frameKind.asValue(b -> b.putField("description", "JIT compiled"));

    int layers = 12;
    int perLayer = 6;
    TypedValue[][] methods = new TypedValue[layers][perLayer];
    for (int layer = 0; layer < layers; layer++) {
      for (int m = 0; m < perLayer; m++) {
        String className = "com/example/layer" + layer + "/Component" + (m % 3);
        String methodName = layer == 0 ? "run" : "step" + layer + "_" + m;
        TypedValue className0 = symbolType.asValue(b -> b.putField("string", className));
        TypedValue clazz =
            classType.asValue(b -> b.putField("name", className0).putField("modifiers", 1));
        TypedValue name = symbolType.asValue(b -> b.putField("string", methodName));
        TypedValue descriptor = symbolType.asValue(b -> b.putField("string", "(J)V"));
        methods[layer][m] =
            methodType.asValue(
                b ->
                    b.putField("type", clazz)
                        .putField("name", name)
                        .putField("descriptor", descriptor)
                        .putField("modifiers", 1)
                        .putField("hidden", false));
      }
    }

    for (int s = 0; s < stackCount; s++) {
      int depth = 3 + random.nextInt(layers - 2);
      TypedValue[] frames = new TypedValue[depth];
      // Top frame first, as the JVM writes them; the root (layer 0) is last.
      for (int i = 0; i < depth; i++) {
        int layer = depth - 1 - i;
        TypedValue method = methods[layer][random.nextInt(perLayer)];
        int line = 10 + random.nextInt(400);
        TypedValue kind = random.nextInt(4) == 0 ? interpreted : compiled;
        frames[i] =
            frameType.asValue(
                b ->
                    b.putField("method", method)
                        .putField("lineNumber", line)
                        .putField("bytecodeIndex", line * 3)
                        .putField("type", kind));
      }
      stacks.add(stackType.asValue(b -> b.putField("truncated", false).putField("frames", frames)));
    }
  }

  TypedValue thread() {
    return threads.get(random.nextInt(threads.size()));
  }

  TypedValue stack() {
    // Skewed towards the first stacks, so some stacks recur often (hot paths).
    int n = stacks.size();
    int i = Math.min(n - 1, (int) Math.abs(random.nextGaussian() * n / 3));
    return stacks.get(i);
  }

  TypedValue state() {
    // Mostly runnable, as a CPU sampler sees.
    return threadStates.get(random.nextInt(10) < 8 ? 0 : 1 + random.nextInt(2));
  }

  /**
   * A string written inline in the event rather than through the string constant pool. The JMC
   * writer pools every string set through its public API; real JVM recordings write many strings
   * inline (for example jdk.InitialSystemProperty values), and the scrubber rewrites inline event
   * strings in place. {@code TypedValueImpl(TypeImpl, Object)} is the writer's own constructor for
   * an unpooled value; it lives outside the writer's {@code api} package, so this depends on JMC
   * writer internals (9.1.0).
   */
  TypedValue inlineString(String value) {
    return new TypedValueImpl(stringType, value);
  }
}

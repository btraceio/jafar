package io.jafar.testrecordings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.TypedValue;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

/**
 * Writes single chunks of the recordings the test suites use. A recording with several chunks is
 * several of these, concatenated: a JFR file is a sequence of self-contained chunks, so only one
 * chunk is ever held in memory (the JMC writer buffers a whole chunk).
 *
 * <p>What each shape contains is set by what the tests read from it; see the module README.
 */
final class Shapes {
  /** 2024-01-01T00:00:00Z: fixed, so the output does not depend on when it was generated. */
  static final long EPOCH_NANOS = 1_704_067_200_000_000_000L;

  /** Ticks are nanoseconds from this base; the writer treats 0 as "unset". */
  static final long START_TICKS = 1_000_000L;

  /** Each chunk covers this much time; event timestamps fall inside it. */
  static final long CHUNK_NANOS = 60_000_000_000L;

  enum Shape {
    /** Stands in for test-ap.jfr: CPU samples plus the JDK events the shell tests query. */
    AP,
    /** Stands in for test-jfr.jfr: a JDK-style recording whose type ids differ from AP's. */
    JFR,
    /** Stands in for test-dd.jfr: Datadog profiler events, as jfr-mcp's tests read them. */
    DD
  }

  private Shapes() {}

  /**
   * Writes chunk {@code index} of {@code shape} to {@code out}.
   *
   * @param events number of execution samples; other event types scale with it
   */
  static void writeChunk(OutputStream out, Shape shape, int index, int events) throws IOException {
    long chunkStart = EPOCH_NANOS + index * CHUNK_NANOS;
    // Chunks come out at about 13.5 bytes per event; the writer itself allocates ~33 KB per event.
    ByteArrayOutputStream chunk = new ByteArrayOutputStream(events * 16 + 4096);
    try (Recording r =
        Recordings.newRecording(
            chunk,
            s ->
                s.withTimestamp(chunkStart)
                    .withStartTicks(START_TICKS)
                    .withDuration(CHUNK_NANOS))) {
      // Instead of withJdkTypeInitialization: the writer's own definitions pool stack frames.
      JdkTypes.register(r);
      long seed = shape.ordinal() * 1_000_003L + index;
      switch (shape) {
        case AP:
          writeAp(r, seed, events);
          break;
        case JFR:
          writeJfr(r, seed, events);
          break;
        case DD:
          writeDd(r, seed, events);
          break;
        default:
          throw new IllegalArgumentException(shape.name());
      }
    } catch (Exception e) {
      throw new IOException("Failed to write chunk " + index + " of " + shape, e);
    }
    chunk.writeTo(out);
  }

  private static void writeAp(Recording r, long seed, int events) {
    ChunkContent c = new ChunkContent(r, seed, 8, 96);
    Type sample = executionSample(r, "jdk.ExecutionSample");
    Type cpuLoad = cpuLoad(r);
    Type heap = heapSummary(r);
    Type property = initialSystemProperty(r);

    Clock clock = new Clock(events + events / 20 + 64);
    writeProperties(r, c, property, clock);
    for (int i = 0; i < events; i++) {
      writeSample(r, c, sample, clock.next());
      if (i % 50 == 0) {
        writeCpuLoad(r, c, cpuLoad, clock.next());
      }
      if (i % 100 == 0) {
        writeGc(r, c, heap, i / 100, clock);
      }
    }
  }

  private static void writeJfr(Recording r, long seed, int events) {
    // Registered in a different order from AP, so the same type names get different ids.
    // ParsingContextReuseTest relies on two recordings disagreeing on ids.
    Type heap = heapSummary(r);
    Type cpuLoad = cpuLoad(r);
    Type sample = executionSample(r, "jdk.ExecutionSample");
    ChunkContent c = new ChunkContent(r, seed, 6, 64);

    Clock clock = new Clock(events + events / 10 + 64);
    for (int i = 0; i < events; i++) {
      writeSample(r, c, sample, clock.next());
      if (i % 40 == 0) {
        writeGc(r, c, heap, i / 40, clock);
      }
      if (i % 25 == 0) {
        writeCpuLoad(r, c, cpuLoad, clock.next());
      }
    }
  }

  private static void writeDd(Recording r, long seed, int events) {
    ChunkContent c = new ChunkContent(r, seed, 12, 128);
    Type ddSample =
        r.registerEventType(
            "datadog.ExecutionSample",
            b ->
                b.addField("spanId", Types.Builtin.LONG)
                    .addField("localRootSpanId", Types.Builtin.LONG)
                    .addField("state", threadState(r)));
    // Declared and sparsely present, as in the Datadog recording; tests list and query it.
    Type jdkSample = executionSample(r, "jdk.ExecutionSample");
    Type jvmInfo =
        r.registerEventType(
            "jdk.JVMInformation",
            b ->
                b.addField("jvmName", Types.Builtin.STRING)
                    .addField("jvmVersion", Types.Builtin.STRING)
                    .addField("jvmArguments", Types.Builtin.STRING)
                    .addField("javaArguments", Types.Builtin.STRING)
                    .addField("jvmStartTime", Types.Builtin.LONG)
                    .addField("pid", Types.Builtin.LONG));
    Type cpuLoad = cpuLoad(r);
    Type heap = heapSummary(r);

    Clock clock = new Clock(events + events / 20 + 64);
    r.writeEvent(
        jvmInfo.asValue(
            v ->
                v.putField("startTime", clock.next())
                    .putField("eventThread", c.thread())
                    .putField("jvmName", "OpenJDK 64-Bit Server VM")
                    .putField("jvmVersion", "21.0.4+7-LTS")
                    .putField("jvmArguments", "-Xmx2g -XX:+UseG1GC")
                    .putField("javaArguments", "com.example.Main")
                    .putField("jvmStartTime", EPOCH_NANOS / 1_000_000L)
                    .putField("pid", 4242L)));
    for (int i = 0; i < events; i++) {
      long t = clock.next();
      long span = 10_000L + c.random.nextInt(200);
      r.writeEvent(
          ddSample.asValue(
              v ->
                  v.putField("startTime", t)
                      .putField("eventThread", c.thread())
                      .putField("stackTrace", c.stack())
                      .putField("spanId", span)
                      .putField("localRootSpanId", span / 10)
                      .putField("state", c.state())));
      if (i % 200 == 0) {
        writeSample(r, c, jdkSample, clock.next());
      }
      if (i % 50 == 0) {
        writeCpuLoad(r, c, cpuLoad, clock.next());
      }
      if (i % 100 == 0) {
        writeGc(r, c, heap, i / 100, clock);
      }
    }
  }

  // --- event types, with the fields the JDK declares for them ------------------------------

  private static Type executionSample(Recording r, String name) {
    return r.registerEventType(
        name, b -> b.addField("sampledThread", Types.JDK.THREAD).addField("state", threadState(r)));
  }

  /** jdk.types.ThreadState: a constant-pool type, as the JDK declares it. */
  static Type threadState(Recording r) {
    return r.getTypes()
        .getOrAdd("jdk.types.ThreadState", b -> b.addField("name", Types.Builtin.STRING));
  }

  /** jdk.types.GCWhen: a constant-pool type, as the JDK declares it. */
  static Type gcWhen(Recording r) {
    return r.getTypes().getOrAdd("jdk.types.GCWhen", b -> b.addField("when", Types.Builtin.STRING));
  }

  /** jdk.types.VirtualSpace: an inline struct (no constant pool), as the JDK declares it. */
  static Type virtualSpace(Recording r) {
    return r.getTypes()
        .getOrAdd(
            "jdk.types.VirtualSpace",
            false,
            b ->
                b.addField("start", Types.Builtin.LONG)
                    .addField("committedEnd", Types.Builtin.LONG)
                    .addField("committedSize", Types.Builtin.LONG)
                    .addField("reservedEnd", Types.Builtin.LONG)
                    .addField("reservedSize", Types.Builtin.LONG));
  }

  private static Type cpuLoad(Recording r) {
    return r.registerEventType(
        "jdk.CPULoad",
        b ->
            b.addField("jvmUser", Types.Builtin.FLOAT)
                .addField("jvmSystem", Types.Builtin.FLOAT)
                .addField("machineTotal", Types.Builtin.FLOAT));
  }

  private static Type heapSummary(Recording r) {
    Type when = gcWhen(r);
    Type space = virtualSpace(r);
    return r.registerEventType(
        "jdk.GCHeapSummary",
        b ->
            b.addField("gcId", Types.Builtin.INT)
                .addField("when", when)
                .addField("heapSpace", space)
                .addField("heapUsed", Types.Builtin.LONG));
  }

  private static Type initialSystemProperty(Recording r) {
    return r.registerEventType(
        "jdk.InitialSystemProperty",
        b -> b.addField("key", Types.Builtin.STRING).addField("value", Types.Builtin.STRING));
  }

  // --- events -----------------------------------------------------------------------------

  private static void writeSample(Recording r, ChunkContent c, Type type, long t) {
    TypedValue thread = c.thread();
    r.writeEvent(
        type.asValue(
            v ->
                v.putField("startTime", t)
                    .putField("eventThread", thread)
                    .putField("stackTrace", c.stack())
                    .putField("sampledThread", thread)
                    .putField("state", c.state())));
  }

  private static void writeCpuLoad(Recording r, ChunkContent c, Type type, long t) {
    float user = c.random.nextFloat() * 0.5f;
    float system = c.random.nextFloat() * 0.2f;
    float machine = Math.min(1f, user + system + c.random.nextFloat() * 0.3f);
    r.writeEvent(
        type.asValue(
            v ->
                v.putField("startTime", t)
                    .putField("jvmUser", user)
                    .putField("jvmSystem", system)
                    .putField("machineTotal", machine)));
  }

  /** A "Before GC" / "After GC" pair, as the JVM emits around each collection. */
  private static void writeGc(Recording r, ChunkContent c, Type heap, int gcId, Clock clock) {
    Type whenType = gcWhen(r);
    Type spaceType = virtualSpace(r);
    long reserved = 2L << 30;
    long committed = 1L << 30;
    TypedValue space =
        spaceType.asValue(
            b ->
                b.putField("start", 0x7000_0000_0000L)
                    .putField("committedEnd", 0x7000_0000_0000L + committed)
                    .putField("committedSize", committed)
                    .putField("reservedEnd", 0x7000_0000_0000L + reserved)
                    .putField("reservedSize", reserved));
    long used = (256L << 20) + c.random.nextInt(512) * (1L << 20);
    for (String when : new String[] {"Before GC", "After GC"}) {
      long heapUsed = when.equals("After GC") ? used / 4 : used;
      long t = clock.next();
      TypedValue w = whenType.asValue(b -> b.putField("when", when));
      r.writeEvent(
          heap.asValue(
              v ->
                  v.putField("startTime", t)
                      .putField("gcId", gcId)
                      .putField("when", w)
                      .putField("heapSpace", space)
                      .putField("heapUsed", heapUsed)));
    }
  }

  private static void writeProperties(Recording r, ChunkContent c, Type type, Clock clock) {
    String[][] properties = {
      {"java.home", "/usr/lib/jvm/java-21-openjdk-amd64"},
      {"java.vm.version", "21.0.4+7-LTS"},
      {"java.vendor", "Eclipse Adoptium"},
      {"user.dir", "/home/app"},
      {"file.encoding", "UTF-8"},
    };
    for (String[] p : properties) {
      long t = clock.next();
      r.writeEvent(
          type.asValue(
              v ->
                  v.putField("startTime", t)
                      .putField("key", p[0])
                      .putField("value", c.inlineString(p[1]))));
    }
  }

  /** Hands out increasing tick values spread evenly across the chunk. */
  private static final class Clock {
    private final long step;
    private long now = START_TICKS;

    Clock(int expectedEvents) {
      this.step = Math.max(1, (CHUNK_NANOS - 1_000_000L) / Math.max(1, expectedEvents));
    }

    long next() {
      now += step;
      return Math.min(now, START_TICKS + CHUNK_NANOS - 1);
    }
  }
}

package io.jafar.hdump.bench;

import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapDumpParser;
import io.jafar.hdump.api.HeapDumpParser.ParserOptions;
import io.jafar.hdump.api.HeapObject;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Path queries on a freshly parsed dump, so whatever a dump caches for path queries is built inside
 * the measurement: {@link #firstPathOnFreshDump} is one query (the cost a user pays for the first
 * {@code findPathToGcRoot}); {@link #retentionPaths2000} is 2000 queries, the access pattern of
 * hdump-shell's {@code retentionPaths} over a class's instances.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(
    value = 1,
    jvmArgsAppend = {"-Xms8g", "-Xmx8g", "-XX:+UseParallelGC"})
public class PathQueriesBenchmark {

  @Param({"app-100000"})
  public String fixture;

  private Path file;
  private HeapDump dump;
  private List<HeapObject> targets;
  private long expected = -1;

  @Setup(Level.Trial)
  public void prepare() throws Exception {
    file = HeapDumpFixture.ensure(fixture);
  }

  @Setup(Level.Invocation)
  public void open() throws Exception {
    System.gc();
    dump = HeapDumpParser.parse(file, ParserOptions.IN_MEMORY);
    List<HeapObject> entities =
        dump.getObjectsOfClass("io/jafar/hdump/bench/HeapDumpFixture$Entity")
            .sorted(Comparator.comparingLong(HeapObject::getId))
            .toList();
    int step = Math.max(1, entities.size() / 2000);
    targets = new java.util.ArrayList<>();
    for (int i = 0; i < entities.size() && targets.size() < 2000; i += step) {
      targets.add(entities.get(i));
    }
  }

  @TearDown(Level.Invocation)
  public void close() throws Exception {
    dump.close();
    dump = null;
  }

  private long check(long total) {
    if (expected < 0) {
      expected = total;
    } else if (total != expected) {
      throw new IllegalStateException("path lengths changed: " + total + " vs " + expected);
    }
    return total;
  }

  @Benchmark
  public long firstPathOnFreshDump() {
    return dump.findPathToGcRoot(targets.get(targets.size() / 2)).size();
  }

  @Benchmark
  public long retentionPaths2000() {
    long total = 0;
    for (HeapObject t : targets) {
      total += dump.findPathToGcRoot(t).size();
    }
    return check(total);
  }
}

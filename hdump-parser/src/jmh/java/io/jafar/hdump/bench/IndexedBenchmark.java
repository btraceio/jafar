package io.jafar.hdump.bench;

import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapDumpParser;
import io.jafar.hdump.api.HeapDumpParser.ParserOptions;
import io.jafar.hdump.api.HeapObject;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * Operators in INDEXED mode, as a user meets them on a dump whose indexes exist but whose retained
 * sizes have never been computed.
 *
 * <p>The fixture is copied once per trial into a scratch directory and opened once to build its
 * indexes ({@code <file>.idx}). Before every invocation {@code inbound.idx} and {@code
 * retained.idx} are deleted (a reopen would otherwise load the persisted results and compute
 * nothing) and the dump is reopened — reopening is fast, the object/class/root indexes are reused;
 * that is outside the measurement.
 *
 * <p>The fork runs with a small heap ({@code -Xmx2g}), since INDEXED mode exists for dumps that do
 * not fit in memory; each invocation's peak heap use (all heap pools, after a reset) is printed by
 * the teardown, so a change that trades the mode's memory bound for speed shows up.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Fork(
    value = 1,
    jvmArgsAppend = {"-Xms2g", "-Xmx2g", "-XX:+UseParallelGC"})
public class IndexedBenchmark {

  @Param({"app-100000"})
  public String fixture;

  private Path copy;
  private Path indexDir;
  private HeapDump dump;
  private List<HeapObject> targets;
  private long expected = Long.MIN_VALUE;
  private String expectedOf;

  @Setup(Level.Trial)
  public void prepare() throws Exception {
    Path source = HeapDumpFixture.ensure(fixture);
    Path dir = Files.createTempDirectory(source.getParent(), "indexed-");
    copy = dir.resolve(source.getFileName());
    Files.copy(source, copy);
    indexDir = copy.resolveSibling(copy.getFileName() + ".idx");
    try (HeapDump d = HeapDumpParser.parse(copy, ParserOptions.INDEXED)) {
      if (d.getObjectCount() == 0) throw new IllegalStateException("empty fixture");
    }
  }

  @TearDown(Level.Trial)
  public void cleanup() throws Exception {
    try (var files = Files.walk(copy.getParent())) {
      files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }

  @Setup(Level.Invocation)
  public void open() throws Exception {
    Files.deleteIfExists(indexDir.resolve("inbound.idx"));
    Files.deleteIfExists(indexDir.resolve("retained.idx"));
    Files.deleteIfExists(indexDir.resolve("strongrefs.idx"));
    Files.deleteIfExists(indexDir.resolve("strongrefs-offsets.idx"));
    System.gc();
    dump = HeapDumpParser.parse(copy, ParserOptions.INDEXED);
    List<HeapObject> entities =
        dump.getObjectsOfClass("io/jafar/hdump/bench/HeapDumpFixture$Entity")
            .sorted(Comparator.comparingLong(HeapObject::getId))
            .limit(20_000)
            .toList();
    targets = new ArrayList<>();
    for (int i = 0; i < 20; i++) targets.add(entities.get(i * entities.size() / 20));
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      if (pool.getType() == MemoryType.HEAP) pool.resetPeakUsage();
    }
  }

  @TearDown(Level.Invocation)
  public void close() throws Exception {
    long peak = 0;
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      if (pool.getType() == MemoryType.HEAP) peak += pool.getPeakUsage().getUsed();
    }
    System.out.println("  [peak heap " + (peak >> 20) + " MB]");
    dump.close();
    dump = null;
  }

  private long check(String op, long value) {
    if (expected == Long.MIN_VALUE || !op.equals(expectedOf)) {
      expected = value;
      expectedOf = op;
    } else if (value != expected) {
      throw new IllegalStateException(op + ": result changed: " + value + " vs " + expected);
    }
    return value;
  }

  /** First retained-size query: builds inbound.idx, then retained.idx (streaming). */
  @Benchmark
  public long approximateRetainedSizes() {
    dump.computeDominators();
    long sum = 0;
    for (HeapObject t : targets) sum = sum * 31 + t.getRetainedSize();
    return check("approx", sum);
  }

  /**
   * Hybrid mode: approximate sizes for all objects (including the inbound/strong-ref index build),
   * then exact dominators for the top 100 retainers and their paths to the roots.
   */
  @Benchmark
  public long hybridDominators() {
    ((io.jafar.hdump.impl.HeapDumpImpl) dump).computeHybridDominators(100, null, null);
    long sum = 0;
    for (HeapObject t : targets) sum = sum * 31 + t.getRetainedSize();
    return check("hybrid", sum);
  }

  /** Paths for 20 entities (one BFS each in INDEXED mode). */
  @Benchmark
  public long pathsToGcRoot20() {
    long total = 0;
    for (HeapObject t : targets) total += dump.findPathToGcRoot(t).size();
    return check("paths", total);
  }
}

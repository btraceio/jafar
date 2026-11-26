package io.jafar.hdump.bench;

import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapDumpParser;
import io.jafar.hdump.api.HeapDumpParser.ParserOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
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
 * INDEXED approximate retained sizes on a dump holding one long singly linked list — the case a
 * per-object BFS handles in quadratic time. Same index handling as {@link IndexedBenchmark}: the
 * lazily built indexes are deleted before each invocation, so their build is measured.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@Fork(
    value = 1,
    jvmArgsAppend = {"-Xms2g", "-Xmx2g", "-XX:+UseParallelGC"})
public class IndexedChainBenchmark {

  @Param({"chain-50000"})
  public String fixture;

  private Path copy;
  private Path indexDir;
  private HeapDump dump;

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
    for (String f :
        new String[] {"inbound.idx", "retained.idx", "strongrefs.idx", "strongrefs-offsets.idx"}) {
      Files.deleteIfExists(indexDir.resolve(f));
    }
    System.gc();
    dump = HeapDumpParser.parse(copy, ParserOptions.INDEXED);
  }

  @TearDown(Level.Invocation)
  public void close() throws Exception {
    dump.close();
  }

  @Benchmark
  public long approximateRetainedSizes() {
    dump.computeDominators();
    return dump.getObjectsOfClass("io/jafar/hdump/bench/HeapDumpFixture$Link")
        .mapToLong(o -> o.getRetainedSize())
        .max()
        .orElseThrow();
  }
}

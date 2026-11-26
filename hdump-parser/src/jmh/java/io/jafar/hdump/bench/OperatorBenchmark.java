package io.jafar.hdump.bench;

import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapDumpParser;
import io.jafar.hdump.api.HeapDumpParser.ParserOptions;
import io.jafar.hdump.api.HeapObject;
import java.nio.file.Path;
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
 * Whole-heap operators on a freshly parsed (IN_MEMORY) dump: each invocation gets a new parse, so
 * the idempotent computations really run; the parse itself is outside the measurement.
 *
 * <p>What is timed: the operator as a user calls it on a fresh dump, so it includes decoding the
 * outbound references of objects whose references were not decoded during the parse (instances of
 * 256 bytes or more, and {@code java.lang.ref.Reference} subclasses). A {@code System.gc()} in the
 * invocation setup collects the previous invocation's dump so it is not collected inside the
 * measurement.
 *
 * <p>After every invocation (outside the measurement) the result is checked against the first
 * invocation's checksum over all retained sizes, so a computation that is not deterministic fails
 * the benchmark instead of reporting a time. Equality with a previous build's answer is not checked
 * here; that is the job of {@code DominatorOracleTest}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(
    value = 1,
    jvmArgsAppend = {"-Xms8g", "-Xmx8g", "-XX:+UseParallelGC"})
public class OperatorBenchmark {

  @Param({"app-100000"})
  public String fixture;

  private Path file;
  private HeapDump dump;
  private long expectedChecksum = Long.MIN_VALUE;
  private String checksumOf;

  @Setup(Level.Trial)
  public void prepare() throws Exception {
    file = HeapDumpFixture.ensure(fixture);
  }

  @Setup(Level.Invocation)
  public void open() throws Exception {
    System.gc();
    dump = HeapDumpParser.parse(file, ParserOptions.IN_MEMORY);
  }

  private String lastOp;

  @TearDown(Level.Invocation)
  public void close() throws Exception {
    if (lastOp != null) {
      verify(lastOp);
      lastOp = null;
    }
    dump.close();
    dump = null;
  }

  private long checksum() {
    long sum = 0;
    long n = 0;
    for (HeapObject o : (Iterable<HeapObject>) dump.getObjects()::iterator) {
      long r = o.getRetainedSizeIfAvailable();
      sum = sum * 31 + r;
      n++;
    }
    return sum ^ n;
  }

  private long verify(String op) {
    long c = checksum();
    if (expectedChecksum == Long.MIN_VALUE || !op.equals(checksumOf)) {
      expectedChecksum = c;
      checksumOf = op;
    } else if (c != expectedChecksum) {
      throw new IllegalStateException(
          op + ": result checksum changed between invocations: " + c + " vs " + expectedChecksum);
    }
    return c;
  }

  @Benchmark
  public long fullDominatorTree() {
    dump.computeFullDominatorTree(null);
    lastOp = "full";
    return dump.hasFullDominatorTree() ? 1 : 0;
  }

  @Benchmark
  public long approximateRetainedSizes() {
    dump.computeDominators();
    lastOp = "approx";
    return dump.hasDominators() ? 1 : 0;
  }
}

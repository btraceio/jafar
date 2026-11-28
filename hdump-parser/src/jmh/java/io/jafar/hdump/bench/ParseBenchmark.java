package io.jafar.hdump.bench;

import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapDumpParser;
import io.jafar.hdump.api.HeapDumpParser.ParserOptions;
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
import org.openjdk.jmh.annotations.Warmup;

/** Parse cost of a real heap dump in IN_MEMORY mode (open, scan all records, close). */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(
    value = 1,
    jvmArgsAppend = {"-Xms8g", "-Xmx8g", "-XX:+UseParallelGC"})
public class ParseBenchmark {

  @Param({"app-100000"})
  public String fixture;

  private Path file;

  @Setup(Level.Trial)
  public void prepare() throws Exception {
    file = HeapDumpFixture.ensure(fixture);
  }

  @Benchmark
  public int parseInMemory() throws Exception {
    try (HeapDump dump = HeapDumpParser.parse(file, ParserOptions.IN_MEMORY)) {
      return dump.getObjectCount();
    }
  }
}

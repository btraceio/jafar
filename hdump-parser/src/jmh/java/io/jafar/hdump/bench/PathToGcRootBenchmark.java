package io.jafar.hdump.bench;

import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapDumpParser;
import io.jafar.hdump.api.HeapDumpParser.ParserOptions;
import io.jafar.hdump.api.HeapObject;
import io.jafar.hdump.api.PathStep;
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
 * {@code findPathToGcRoot} for a fixed set of targets on one parsed dump: four entities picked by
 * id order, and the node a quarter of the way along the fixture's {@code LinkedList} (shortest path
 * about scale/4 steps, reached from {@code first}; the BFS has to cover most of the heap before it
 * gets there). The deep target's path length is asserted at setup.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 5)
@Fork(
    value = 1,
    jvmArgsAppend = {"-Xms8g", "-Xmx8g", "-XX:+UseParallelGC"})
public class PathToGcRootBenchmark {

  @Param({"app-100000"})
  public String fixture;

  private HeapDump dump;
  private List<HeapObject> targets;
  private int expectedTotalLength = -1;

  @Setup(Level.Trial)
  public void prepare() throws Exception {
    Path file = HeapDumpFixture.ensure(fixture);
    dump = HeapDumpParser.parse(file, ParserOptions.IN_MEMORY);
    List<HeapObject> entities =
        dump.getObjectsOfClass("io/jafar/hdump/bench/HeapDumpFixture$Entity")
            .sorted(Comparator.comparingLong(HeapObject::getId))
            .toList();
    if (entities.isEmpty()) {
      throw new IllegalStateException("fixture has no entities");
    }
    targets = new ArrayList<>();
    for (int i = 1; i <= 4; i++) {
      targets.add(entities.get(entities.size() * i / 5));
    }
    HeapObject world =
        dump.getObjectsOfClass("io/jafar/hdump/bench/HeapDumpFixture$World")
            .findFirst()
            .orElseThrow();
    HeapObject chain = (HeapObject) world.getFieldValue("chain");
    int size = (Integer) chain.getFieldValue("size");
    int steps = size / 4;
    HeapObject node = (HeapObject) chain.getFieldValue("first");
    for (int i = 0; i < steps; i++) {
      node = (HeapObject) node.getFieldValue("next");
    }
    targets.add(node);
    int deep = dump.findPathToGcRoot(node).size();
    // frame root -> World -> LinkedList -> first -> ... (steps more nodes)
    if (deep < steps) {
      throw new IllegalStateException("deep target path too short: " + deep + " < " + steps);
    }
  }

  @TearDown(Level.Trial)
  public void close() throws Exception {
    dump.close();
  }

  @Benchmark
  public int pathsToGcRoot() {
    int total = 0;
    for (HeapObject t : targets) {
      List<PathStep> path = dump.findPathToGcRoot(t);
      if (path.isEmpty()) {
        throw new IllegalStateException("no path to GC root for " + t);
      }
      total += path.size();
    }
    if (expectedTotalLength < 0) {
      expectedTotalLength = total;
    } else if (total != expectedTotalLength) {
      throw new IllegalStateException(
          "path lengths changed: " + total + " vs " + expectedTotalLength);
    }
    return total;
  }
}

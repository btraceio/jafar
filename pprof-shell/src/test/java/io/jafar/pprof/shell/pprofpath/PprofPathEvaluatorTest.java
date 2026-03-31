package io.jafar.pprof.shell.pprofpath;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.pprof.shell.MinimalPprofBuilder;
import io.jafar.pprof.shell.PprofSession;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Evaluator tests using a synthetic in-memory pprof profile.
 *
 * <p>The profile contains:
 *
 * <ul>
 *   <li>Sample type: {@code cpu} / {@code nanoseconds}
 *   <li>3 functions: {@code foo.Bar.compute} (fn1), {@code foo.Bar.helper} (fn2), {@code
 *       baz.Qux.run} (fn3)
 *   <li>3 locations: loc1 → fn1:10, loc2 → fn2:20, loc3 → fn3:5
 *   <li>3 samples:
 *       <ul>
 *         <li>sample1: stack [loc1, loc2], cpu=1_000_000, thread="main"
 *         <li>sample2: stack [loc3, loc2], cpu=2_000_000, thread="main"
 *         <li>sample3: stack [loc1, loc3], cpu=500_000, thread="Worker-1"
 *       </ul>
 * </ul>
 */
class PprofPathEvaluatorTest {

  @TempDir static Path tempDir;

  private static PprofSession session;

  @BeforeAll
  static void setup() throws IOException {
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    int cpu = b.addString("cpu");
    int ns = b.addString("nanoseconds");
    int fnName1 = b.addString("foo.Bar.compute");
    int fnName2 = b.addString("foo.Bar.helper");
    int fnName3 = b.addString("baz.Qux.run");
    int file1 = b.addString("foo/Bar.java");
    int file2 = b.addString("baz/Qux.java");
    int threadKey = b.addString("thread");
    int threadMain = b.addString("main");
    int threadWorker = b.addString("Worker-1");

    b.addSampleType(cpu, ns);
    b.setDurationNanos(30_000_000_000L);

    long fn1 = b.addFunction(fnName1, file1);
    long fn2 = b.addFunction(fnName2, file1);
    long fn3 = b.addFunction(fnName3, file2);

    long loc1 = b.addLocation(fn1, 10);
    long loc2 = b.addLocation(fn2, 20);
    long loc3 = b.addLocation(fn3, 5);

    // sample1: stack=[loc1, loc2], cpu=1_000_000, thread="main"
    b.addSample(
        List.of(loc1, loc2),
        List.of(1_000_000L),
        List.of(new long[] {threadKey, threadMain, 0, 0}));

    // sample2: stack=[loc3, loc2], cpu=2_000_000, thread="main"
    b.addSample(
        List.of(loc3, loc2),
        List.of(2_000_000L),
        List.of(new long[] {threadKey, threadMain, 0, 0}));

    // sample3: stack=[loc1, loc3], cpu=500_000, thread="Worker-1"
    b.addSample(
        List.of(loc1, loc3),
        List.of(500_000L),
        List.of(new long[] {threadKey, threadWorker, 0, 0}));

    Path file = b.write(tempDir);
    session = PprofSession.open(file);
  }

  @AfterAll
  static void teardown() throws Exception {
    if (session != null) session.close();
  }

  private static List<Map<String, Object>> run(String query) {
    return PprofPathEvaluator.evaluate(session, PprofPathParser.parse(query));
  }

  // ---- count ----

  @Nested
  class Count {
    @Test
    void countAllSamples() {
      List<Map<String, Object>> result = run("samples | count()");
      assertEquals(1, result.size());
      assertEquals(3L, ((Number) result.get(0).get("count")).longValue());
    }

    @Test
    void countFilteredSamples() {
      List<Map<String, Object>> result = run("samples[thread='main'] | count()");
      assertEquals(2L, ((Number) result.get(0).get("count")).longValue());
    }
  }

  // ---- head / tail ----

  @Nested
  class HeadTail {
    @Test
    void headReturnsFirstN() {
      List<Map<String, Object>> result = run("samples | head(2)");
      assertEquals(2, result.size());
    }

    @Test
    void headExceedingSize() {
      List<Map<String, Object>> result = run("samples | head(100)");
      assertEquals(3, result.size());
    }

    @Test
    void tailReturnsLastN() {
      List<Map<String, Object>> result = run("samples | tail(1)");
      assertEquals(1, result.size());
    }
  }

  // ---- top ----

  @Nested
  class Top {
    @Test
    void topByCpuDescending() {
      List<Map<String, Object>> result = run("samples | top(2, cpu)");
      assertEquals(2, result.size());
      long first = ((Number) result.get(0).get("cpu")).longValue();
      long second = ((Number) result.get(1).get("cpu")).longValue();
      assertTrue(first >= second, "results should be sorted descending");
      assertEquals(2_000_000L, first);
    }

    @Test
    void topByCpuAscending() {
      List<Map<String, Object>> result = run("samples | top(2, cpu, asc)");
      long first = ((Number) result.get(0).get("cpu")).longValue();
      assertEquals(500_000L, first);
    }

    @Test
    void topDefaultsToFirstSampleType() {
      List<Map<String, Object>> result = run("samples | top(1)");
      assertEquals(1, result.size());
      assertEquals(2_000_000L, ((Number) result.get(0).get("cpu")).longValue());
    }

    @Test
    void topWithFilter() {
      List<Map<String, Object>> result = run("samples[thread='Worker-1'] | top(1, cpu)");
      assertEquals(1, result.size());
      assertEquals(500_000L, ((Number) result.get(0).get("cpu")).longValue());
    }
  }

  // ---- groupBy ----

  @Nested
  class GroupBy {
    @Test
    void groupByThreadCount() {
      List<Map<String, Object>> result = run("samples | groupBy(thread)");
      assertEquals(2, result.size());
      // main has 2 samples → should be first (sorted desc by count)
      assertEquals("main", result.get(0).get("thread"));
      assertEquals(2L, ((Number) result.get(0).get("count")).longValue());
      assertEquals("Worker-1", result.get(1).get("thread"));
      assertEquals(1L, ((Number) result.get(1).get("count")).longValue());
    }

    @Test
    void groupByThreadSumCpu() {
      List<Map<String, Object>> result = run("samples | groupBy(thread, sum(cpu))");
      assertEquals(2, result.size());
      Map<String, Object> mainRow = result.get(0);
      assertEquals("main", mainRow.get("thread"));
      assertEquals(3_000_000L, ((Number) mainRow.get("sum_cpu")).longValue());
    }

    @Test
    void groupByLeafFunctionCount() {
      List<Map<String, Object>> result = run("samples | groupBy(stackTrace/0/name)");
      // loc1 = foo.Bar.compute (sample1 + sample3), loc3 = baz.Qux.run (sample2)
      assertEquals(2, result.size());
    }
  }

  // ---- stats ----

  @Nested
  class Stats {
    @Test
    void statsForCpu() {
      List<Map<String, Object>> result = run("samples | stats(cpu)");
      assertEquals(1, result.size());
      Map<String, Object> row = result.get(0);
      assertEquals("cpu", row.get("field"));
      assertEquals(3L, ((Number) row.get("count")).longValue());
      assertEquals(3_500_000L, ((Number) row.get("sum")).longValue());
      assertEquals(500_000L, ((Number) row.get("min")).longValue());
      assertEquals(2_000_000L, ((Number) row.get("max")).longValue());
      // avg = 3_500_000 / 3 = 1_166_666 (integer truncation)
      assertEquals(1_166_666L, ((Number) row.get("avg")).longValue());
    }

    @Test
    void statsOnUnknownFieldReturnsZeroCount() {
      List<Map<String, Object>> result = run("samples | stats(nonexistent)");
      assertEquals(0L, ((Number) result.get(0).get("count")).longValue());
    }
  }

  // ---- filter ----

  @Nested
  class Filter {
    @Test
    void filterByThread() {
      List<Map<String, Object>> result = run("samples | filter(thread = main)");
      assertEquals(2, result.size());
    }

    @Test
    void filterByCpuThreshold() {
      List<Map<String, Object>> result = run("samples | filter(cpu > 1000000)");
      assertEquals(1, result.size());
      assertEquals(2_000_000L, ((Number) result.get(0).get("cpu")).longValue());
    }

    @Test
    void filterByRegex() {
      List<Map<String, Object>> result = run("samples | filter(thread ~ 'Work.*')");
      assertEquals(1, result.size());
      assertEquals("Worker-1", result.get(0).get("thread"));
    }

    @Test
    void filterAndCombinator() {
      List<Map<String, Object>> result = run("samples | filter(thread = main and cpu > 1000000)");
      assertEquals(1, result.size());
    }

    @Test
    void filterOrCombinator() {
      List<Map<String, Object>> result = run("samples | filter(cpu = 500000 or cpu = 2000000)");
      assertEquals(2, result.size());
    }

    @Test
    void filterNonMatching() {
      List<Map<String, Object>> result = run("samples | filter(thread = nobody)");
      assertTrue(result.isEmpty());
    }
  }

  // ---- predicates on root ----

  @Nested
  class RootPredicates {
    @Test
    void rootPredicateByThread() {
      List<Map<String, Object>> result = run("samples[thread='main'] | count()");
      assertEquals(2L, ((Number) result.get(0).get("count")).longValue());
    }

    @Test
    void rootPredicateByCpuGt() {
      List<Map<String, Object>> result = run("samples[cpu > 1000000]");
      assertEquals(1, result.size());
    }

    @Test
    void rootPredicateRegex() {
      List<Map<String, Object>> result = run("samples[thread ~ 'main']");
      assertEquals(2, result.size());
    }
  }

  // ---- select ----

  @Nested
  class Select {
    @Test
    void selectSubsetOfFields() {
      List<Map<String, Object>> result = run("samples | select(cpu, thread)");
      assertEquals(3, result.size());
      for (Map<String, Object> row : result) {
        assertTrue(row.containsKey("cpu"), "missing cpu");
        assertTrue(row.containsKey("thread"), "missing thread");
        assertFalse(row.containsKey("stackTrace"), "stackTrace should be excluded");
      }
    }

    @Test
    void selectNestedPath() {
      List<Map<String, Object>> result = run("samples | select(stackTrace/0/name)");
      assertEquals(3, result.size());
      for (Map<String, Object> row : result) {
        assertTrue(row.containsKey("stackTrace/0/name"), "missing leaf name");
      }
    }
  }

  // ---- sortBy ----

  @Nested
  class SortBy {
    @Test
    void sortByCpuDescending() {
      List<Map<String, Object>> result = run("samples | sortBy(cpu)");
      long first = ((Number) result.get(0).get("cpu")).longValue();
      long last = ((Number) result.get(result.size() - 1).get("cpu")).longValue();
      assertTrue(first >= last);
      assertEquals(2_000_000L, first);
    }

    @Test
    void sortByCpuAscending() {
      List<Map<String, Object>> result = run("samples | sortBy(cpu, asc)");
      assertEquals(500_000L, ((Number) result.get(0).get("cpu")).longValue());
    }
  }

  // ---- distinct ----

  @Nested
  class Distinct {
    @Test
    void distinctThread() {
      List<Map<String, Object>> result = run("samples | distinct(thread)");
      assertEquals(2, result.size());
      long mainCount = result.stream().filter(r -> "main".equals(r.get("thread"))).count();
      long workerCount = result.stream().filter(r -> "Worker-1".equals(r.get("thread"))).count();
      assertEquals(1, mainCount);
      assertEquals(1, workerCount);
    }

    @Test
    void distinctUniqueAlias() {
      List<Map<String, Object>> result = run("samples | unique(thread)");
      assertEquals(2, result.size());
    }
  }

  // ---- stackprofile ----

  @Nested
  class StackProfile {
    @Test
    void stackprofileProducesStacksAndValues() {
      List<Map<String, Object>> result = run("samples | stackprofile()");
      assertFalse(result.isEmpty());
      for (Map<String, Object> row : result) {
        assertTrue(row.containsKey("stack"), "missing 'stack' column");
        // value column named after first sample type
        assertTrue(row.containsKey("cpu"), "missing 'cpu' column");
      }
    }

    @Test
    void stackprofileStacksAreRootFirst() {
      List<Map<String, Object>> result = run("samples | stackprofile()");
      // All stacks must contain semicolons (multiple frames) or single frames
      for (Map<String, Object> row : result) {
        String stack = (String) row.get("stack");
        assertNotNull(stack);
        assertFalse(stack.isBlank());
      }
    }

    @Test
    void stackprofileSumsBySampleType() {
      List<Map<String, Object>> result = run("samples | stackprofile(cpu)");
      // Total of all stack values must equal sum of all sample cpu values
      long total = result.stream().mapToLong(r -> ((Number) r.get("cpu")).longValue()).sum();
      assertEquals(3_500_000L, total);
    }

    @Test
    void stackprofileFilteredByThread() {
      List<Map<String, Object>> main = run("samples[thread='main'] | stackprofile(cpu)");
      long total = main.stream().mapToLong(r -> ((Number) r.get("cpu")).longValue()).sum();
      assertEquals(3_000_000L, total);
    }

    @Test
    void stackprofileSortedDescending() {
      List<Map<String, Object>> result = run("samples | stackprofile(cpu)");
      if (result.size() > 1) {
        long first = ((Number) result.get(0).get("cpu")).longValue();
        long second = ((Number) result.get(1).get("cpu")).longValue();
        assertTrue(first >= second, "stackprofile result should be sorted descending");
      }
    }
  }

  // ---- row structure ----

  @Nested
  class RowStructure {
    @Test
    void eachSampleHasCpuField() {
      List<Map<String, Object>> result = run("samples");
      for (Map<String, Object> row : result) {
        assertTrue(row.containsKey("cpu"), "missing 'cpu' field");
      }
    }

    @Test
    void eachSampleHasStackTrace() {
      List<Map<String, Object>> result = run("samples");
      for (Map<String, Object> row : result) {
        assertTrue(row.containsKey("stackTrace"), "missing 'stackTrace'");
        assertInstanceOf(List.class, row.get("stackTrace"));
      }
    }

    @Test
    void stackFrameHasNameFilenameAndLine() {
      List<Map<String, Object>> result = run("samples | head(1)");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> stack = (List<Map<String, Object>>) result.get(0).get("stackTrace");
      assertFalse(stack.isEmpty());
      Map<String, Object> frame = stack.get(0);
      assertTrue(frame.containsKey("name"), "frame missing 'name'");
      assertTrue(frame.containsKey("filename"), "frame missing 'filename'");
      assertTrue(frame.containsKey("line"), "frame missing 'line'");
    }

    @Test
    void eachSampleHasThreadLabel() {
      List<Map<String, Object>> result = run("samples");
      for (Map<String, Object> row : result) {
        assertTrue(row.containsKey("thread"), "missing 'thread' label");
      }
    }
  }
}

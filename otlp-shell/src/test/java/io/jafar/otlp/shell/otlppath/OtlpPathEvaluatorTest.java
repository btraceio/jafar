package io.jafar.otlp.shell.otlppath;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.otlp.shell.MinimalOtlpBuilder;
import io.jafar.otlp.shell.OtlpSession;
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
 * Evaluator tests using a synthetic in-memory OTLP profile.
 *
 * <p>The profile contains:
 *
 * <ul>
 *   <li>Sample type: {@code cpu} / {@code nanoseconds}
 *   <li>3 functions: {@code foo.Bar.compute} (fn1), {@code foo.Bar.helper} (fn2), {@code
 *       baz.Qux.run} (fn3)
 *   <li>3 locations: loc1 → fn1:10, loc2 → fn2:20, loc3 → fn3:5
 *   <li>3 stacks: stack1=[loc1,loc2], stack2=[loc3,loc2], stack3=[loc1,loc3]
 *   <li>2 attributes: thread="main", thread="Worker-1"
 *   <li>3 samples:
 *       <ul>
 *         <li>sample1: stack1, cpu=1_000_000, thread="main"
 *         <li>sample2: stack2, cpu=2_000_000, thread="main"
 *         <li>sample3: stack3, cpu=500_000, thread="Worker-1"
 *       </ul>
 * </ul>
 */
class OtlpPathEvaluatorTest {

  @TempDir static Path tempDir;

  private static OtlpSession session;

  @BeforeAll
  static void setup() throws IOException {
    MinimalOtlpBuilder b = new MinimalOtlpBuilder();
    int typeIdx = b.addString("cpu");
    int unitIdx = b.addString("nanoseconds");
    int fnName1 = b.addString("foo.Bar.compute");
    int fnName2 = b.addString("foo.Bar.helper");
    int fnName3 = b.addString("baz.Qux.run");
    int file1 = b.addString("foo/Bar.java");
    int file2 = b.addString("baz/Qux.java");
    int threadKey = b.addString("thread");

    b.setSampleType(typeIdx, unitIdx);
    b.setDurationNanos(30_000_000_000L);

    int fn1 = b.addFunction(fnName1, file1);
    int fn2 = b.addFunction(fnName2, file1);
    int fn3 = b.addFunction(fnName3, file2);

    int loc1 = b.addLocation(fn1, 10);
    int loc2 = b.addLocation(fn2, 20);
    int loc3 = b.addLocation(fn3, 5);

    int stack1 = b.addStack(List.of(loc1, loc2));
    int stack2 = b.addStack(List.of(loc3, loc2));
    int stack3 = b.addStack(List.of(loc1, loc3));

    int attrMain = b.addAttribute(threadKey, "main");
    int attrWorker = b.addAttribute(threadKey, "Worker-1");

    // sample1: stack1, cpu=1_000_000, thread="main"
    b.addSample(stack1, List.of(attrMain), List.of(1_000_000L));
    // sample2: stack2, cpu=2_000_000, thread="main"
    b.addSample(stack2, List.of(attrMain), List.of(2_000_000L));
    // sample3: stack3, cpu=500_000, thread="Worker-1"
    b.addSample(stack3, List.of(attrWorker), List.of(500_000L));

    Path file = b.write(tempDir);
    session = OtlpSession.open(file);
  }

  @AfterAll
  static void teardown() throws Exception {
    if (session != null) session.close();
  }

  private static List<Map<String, Object>> run(String query) {
    return OtlpPathEvaluator.evaluate(session, OtlpPathParser.parse(query));
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
      // main has 2 samples — should be first (sorted desc by count)
      assertEquals("main", result.get(0).get("thread"));
      assertEquals(2L, ((Number) result.get(0).get("count")).longValue());
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
      List<Map<String, Object>> result = run("samples[thread='main'] | head(10)");
      assertEquals(2, result.size());
    }

    @Test
    void rootPredicateByCpuGt() {
      List<Map<String, Object>> result = run("samples[cpu > 1000000] | count()");
      assertEquals(1L, ((Number) result.get(0).get("count")).longValue());
    }
  }

  // ---- distinct ----

  @Nested
  class Distinct {
    @Test
    void distinctThread() {
      List<Map<String, Object>> result = run("samples | distinct(thread)");
      assertEquals(2, result.size());
    }
  }

  // ---- sampleType meta-field ----

  @Nested
  class SampleTypeField {
    @Test
    void allRowsHaveSampleTypeField() {
      List<Map<String, Object>> result = run("samples | head(10)");
      for (Map<String, Object> row : result) {
        assertEquals("cpu", row.get("sampleType"));
      }
    }

    @Test
    void filterBySampleType() {
      List<Map<String, Object>> result = run("samples[sampleType='cpu'] | count()");
      assertEquals(3L, ((Number) result.get(0).get("count")).longValue());
    }

    @Test
    void filterByUnknownSampleTypeReturnsEmpty() {
      List<Map<String, Object>> result = run("samples[sampleType='wall'] | count()");
      assertEquals(0L, ((Number) result.get(0).get("count")).longValue());
    }

    @Test
    void distinctSampleType() {
      List<Map<String, Object>> result = run("samples | distinct(sampleType)");
      assertEquals(1, result.size());
      assertEquals("cpu", result.get(0).get("sampleType"));
    }
  }

  // ---- stackprofile ----

  @Nested
  class Stackprofile {
    @Test
    void stackprofileReturnsFoldedStacks() {
      List<Map<String, Object>> result = run("samples | stackprofile(cpu)");
      assertFalse(result.isEmpty());
      // Each row has a 'stack' key with ';'-separated frames
      for (Map<String, Object> row : result) {
        assertNotNull(row.get("stack"), "each row should have 'stack'");
        assertTrue(row.get("stack") instanceof String, "stack should be a String");
        assertNotNull(row.get("cpu"), "each row should have value field 'cpu'");
      }
    }
  }

  // ---- select ----

  @Nested
  class Select {
    @Test
    void selectProjectsFields() {
      List<Map<String, Object>> result = run("samples | select(cpu, thread)");
      for (Map<String, Object> row : result) {
        assertTrue(row.containsKey("cpu"));
        assertTrue(row.containsKey("thread"));
      }
    }
  }

  // ---- sortBy ----

  @Nested
  class SortBy {
    @Test
    void sortByCpuDesc() {
      List<Map<String, Object>> result = run("samples | sortBy(cpu)");
      assertEquals(3, result.size());
      long first = ((Number) result.get(0).get("cpu")).longValue();
      long last = ((Number) result.get(2).get("cpu")).longValue();
      assertTrue(first >= last);
    }

    @Test
    void sortByCpuAsc() {
      List<Map<String, Object>> result = run("samples | sortBy(cpu, asc)");
      long first = ((Number) result.get(0).get("cpu")).longValue();
      assertEquals(500_000L, first);
    }
  }
}

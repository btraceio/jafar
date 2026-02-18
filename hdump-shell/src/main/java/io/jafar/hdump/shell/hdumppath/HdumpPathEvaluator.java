package io.jafar.hdump.shell.hdumppath;

import io.jafar.hdump.api.*;
import io.jafar.hdump.shell.HeapSession;
import io.jafar.hdump.shell.hdumppath.HdumpPath.*;
import io.jafar.shell.core.RowSorter;
import io.jafar.shell.core.expr.ValueExpr;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Evaluates HdumpPath queries against a HeapSession.
 *
 * <p>The evaluator converts queries into heap dump operations, applying filters and pipeline
 * transformations to produce result sets.
 */
public final class HdumpPathEvaluator {

  private HdumpPathEvaluator() {}

  /**
   * Evaluates a query against a heap session.
   *
   * @param session the heap session
   * @param query the parsed query
   * @return list of result maps
   */
  public static List<Map<String, Object>> evaluate(HeapSession session, Query query) {
    HeapDump dump = session.getHeapDump();

    // NOTE: Retained sizes are computed lazily when needed (e.g., by checkLeaks operations)
    // Do NOT eagerly compute them here - many queries don't need retained sizes!

    // Optimization: For large heaps with object queries, use streaming to avoid OOM
    // This handles all cases: unfiltered, type patterns, predicates
    if (query.root() == Root.OBJECTS && dump.getObjectCount() > 5_000_000) {

      // If no pipeline, default to top(100) to prevent OOME
      if (query.pipeline().isEmpty()) {
        System.err.println();
        System.err.printf(
            "WARNING: Large heap (%,d objects) - defaulting to first 100 results.%n",
            dump.getObjectCount());
        System.err.println("Use '| top(N)' to specify limit, or '| groupBy(...)' to aggregate.");
        System.err.println();

        TopOp defaultTop = new TopOp(100, null, false);
        return evaluateObjectsStreamingWithFilters(dump, session, query, defaultTop, List.of());
      }

      // Peel any leading FilterOps into the query predicates so they are applied during streaming
      List<Predicate> mergedPredicates = new ArrayList<>(query.predicates());
      int firstNonFilter = 0;
      List<PipelineOp> pipeline = query.pipeline();
      while (firstNonFilter < pipeline.size() && pipeline.get(firstNonFilter) instanceof FilterOp f) {
        mergedPredicates.add(f.predicate());
        firstNonFilter++;
      }
      Query streamingQuery = firstNonFilter > 0
          ? new Query(query.root(), query.typePattern(), query.instanceof_(), mergedPredicates, pipeline.subList(firstNonFilter, pipeline.size()))
          : query;
      List<PipelineOp> streamingPipeline = streamingQuery.pipeline();

      if (streamingPipeline.isEmpty()) {
        // Only filters remain — need a limit
        System.err.println();
        System.err.printf(
            "WARNING: Large heap (%,d objects) - defaulting to first 100 results.%n",
            dump.getObjectCount());
        System.err.println("Use '| top(N)' to specify limit, or '| groupBy(...)' to aggregate.");
        System.err.println();
        TopOp defaultTop = new TopOp(100, null, false);
        return evaluateObjectsStreamingWithFilters(dump, session, streamingQuery, defaultTop, List.of());
      }

      PipelineOp firstOp = streamingPipeline.get(0);
      List<PipelineOp> remaining = streamingPipeline.subList(1, streamingPipeline.size());

      // Check if first operation is an aggregation that can be streamed
      if (firstOp instanceof TopOp topOp) {
        return evaluateObjectsStreamingWithFilters(dump, session, streamingQuery, topOp, remaining);

      } else if (firstOp instanceof GroupByOp groupByOp) {
        // Check if followed by top(N) - if so, use bounded accumulation
        if (!remaining.isEmpty() && remaining.get(0) instanceof TopOp topOp) {
          return evaluateObjectsStreamingGroupByTop(dump, session, streamingQuery, groupByOp, topOp, remaining.subList(1, remaining.size()));
        } else {
          return evaluateObjectsStreamingWithFilters(dump, session, streamingQuery, groupByOp, remaining);
        }

      } else if (firstOp instanceof CountOp countOp) {
        return evaluateObjectsStreamingWithFilters(dump, session, streamingQuery, countOp, remaining);

      } else if (firstOp instanceof SumOp sumOp) {
        return evaluateObjectsStreamingWithFilters(dump, session, streamingQuery, sumOp, remaining);

      } else if (firstOp instanceof StatsOp statsOp) {
        return evaluateObjectsStreamingWithFilters(dump, session, streamingQuery, statsOp, remaining);

      } else {
        // Other operations - default to top(100) to prevent OOME
        System.err.println();
        System.err.printf(
            "WARNING: Large heap (%,d objects) with non-aggregating pipeline - defaulting to first 100 results.%n",
            dump.getObjectCount());
        System.err.println("Use '| top(N)' as first operation to specify limit explicitly.");
        System.err.println();

        TopOp defaultTop = new TopOp(100, null, false);
        List<Map<String, Object>> results =
            evaluateObjectsStreamingWithFilters(dump, session, streamingQuery, defaultTop, List.of());

        // Apply the remaining (non-filter) pipeline ops to the limited results
        for (PipelineOp op : streamingPipeline) {
          results = applyPipelineOp(session, results, op);
        }
        return results;
      }
    }

    // Standard path: materialize all results first
    List<Map<String, Object>> results =
        switch (query.root()) {
          case OBJECTS -> evaluateObjects(dump, query);
          case CLASSES -> evaluateClasses(dump, query);
          case GCROOTS -> evaluateGcRoots(dump, query);
        };

    // Apply pipeline operations
    for (PipelineOp op : query.pipeline()) {
      results = applyPipelineOp(session, results, op);
    }

    return results;
  }

  /**
   * Streaming evaluation from a filtered stream (supports type patterns and predicates).
   */
  private static List<Map<String, Object>> evaluateObjectsStreamingTopFromStream(
      Stream<HeapObject> stream, long totalObjects, TopOp topOp) {
    Comparator<Map<String, Object>> comparator;
    if (topOp.orderBy() != null) {
      comparator = (m1, m2) -> {
        Object v1 = m1.get(topOp.orderBy());
        Object v2 = m2.get(topOp.orderBy());
        int cmp = compareValues(v1, v2);
        return topOp.descending() ? -cmp : cmp;
      };
    } else {
      // No ordering - just take first N
      comparator = (m1, m2) -> 0;
    }

    // Min-heap of size N (removes smallest when full)
    java.util.PriorityQueue<Map<String, Object>> topN =
        new java.util.PriorityQueue<>(topOp.n() + 1, comparator);

    long[] counter = {0};
    long[] lastReport = {System.currentTimeMillis()};

    stream.forEach(obj -> {
      Map<String, Object> map = objectToMap(obj);
      topN.add(map);
      if (topN.size() > topOp.n()) {
        topN.poll(); // Remove smallest
      }

      counter[0]++;
      // Report progress every 500ms
      long now = System.currentTimeMillis();
      if (now - lastReport[0] >= 500) {
        double progress = (double) counter[0] / totalObjects * 100.0;
        System.err.printf("\rScanning objects: %.1f%% (%,d/%,d)",
            progress, counter[0], totalObjects);
        System.err.flush();
        lastReport[0] = now;
      }
    });

    // Clear progress lines
    System.err.print("\r" + " ".repeat(120) + "\r");
    System.err.print("\033[A");
    System.err.print("\r" + " ".repeat(120) + "\r");
    System.err.flush();

    // Convert priority queue to list in correct order
    List<Map<String, Object>> results = new ArrayList<>(topN);
    results.sort(comparator.reversed()); // Reverse to get descending order
    return results;
  }

  /**
   * Streaming evaluation from a filtered stream for groupBy operations.
   * Accumulates groups as we stream to avoid materializing all objects.
   */
  private static List<Map<String, Object>> evaluateObjectsStreamingGroupByFromStream(
      Stream<HeapObject> stream, long totalObjects, GroupByOp op) {
    Map<List<Object>, GroupAccumulator> groups = new LinkedHashMap<>();
    long[] counter = {0};
    long[] lastReport = {System.currentTimeMillis()};

    stream.forEach(obj -> {
      Map<String, Object> map = objectToMap(obj);

      // Extract group key
      List<Object> key = new ArrayList<>();
      for (String field : op.groupFields()) {
        key.add(map.get(field));
      }

      // Get or create accumulator for this group
      GroupAccumulator acc = groups.computeIfAbsent(key, k -> new GroupAccumulator(k, op));
      acc.accumulate(map, op);

      counter[0]++;
      // Report progress every 500ms
      long now = System.currentTimeMillis();
      if (now - lastReport[0] >= 500) {
        double progress = (double) counter[0] / totalObjects * 100.0;
        System.err.printf("\rScanning objects: %.1f%% (%,d/%,d, %,d groups)",
            progress, counter[0], totalObjects, groups.size());
        System.err.flush();
        lastReport[0] = now;
      }
    });

    // Clear progress lines (current line + "Streaming through..." line above)
    System.err.print("\r" + " ".repeat(120) + "\r"); // Clear current line
    System.err.print("\033[A"); // Move up one line
    System.err.print("\r" + " ".repeat(120) + "\r"); // Clear "Streaming through..." line
    System.err.flush();

    // Convert groups to result maps
    List<Map<String, Object>> results = groups.values().stream()
        .map(acc -> acc.toResultMap(op))
        .collect(Collectors.toList());

    // Apply sorting if requested
    if (op.sortBy() != null && !op.sortBy().isEmpty()) {
      // Translate "key" or "value" to actual column name
      String sortColumn;
      if ("key".equals(op.sortBy())) {
        sortColumn = op.groupFields().get(0); // Sort by first group field
      } else if ("value".equals(op.sortBy())) {
        // Use same logic as toResultMap() to determine actual column name
        sortColumn = getAggregationColumnName(op);
      } else {
        sortColumn = op.sortBy(); // Use as-is (shouldn't happen based on parser)
      }

      results.sort((m1, m2) -> {
        Object v1 = m1.get(sortColumn);
        Object v2 = m2.get(sortColumn);
        int cmp = compareValues(v1, v2);
        return op.ascending() ? cmp : -cmp;
      });
    }

    return results;
  }

  /**
   * Optimized streaming evaluation for "groupBy | top(N)" pattern.
   * Uses bounded priority queue to avoid materializing all groups.
   */
  private static List<Map<String, Object>> evaluateObjectsStreamingGroupByTop(
      HeapDump dump, HeapSession session, Query query, GroupByOp groupByOp, TopOp topOp, List<PipelineOp> remainingOps) {

    // Build filtered stream
    Stream<HeapObject> stream = dump.getObjects();
    long totalObjects = dump.getObjectCount();
    String filterDesc = "";

    // Filter by type pattern
    if (query.typePattern() != null) {
      String pattern = normalizeClassName(query.typePattern());
      filterDesc = " matching " + query.typePattern();

      if (query.instanceof_()) {
        Set<String> matchingClasses = findMatchingClasses(dump, pattern, true);
        stream =
            stream.filter(
                obj -> {
                  HeapClass cls = obj.getHeapClass();
                  return cls != null && matchingClasses.contains(cls.getName());
                });
      } else {
        if (pattern.contains("*")) {
          Pattern regex = globToRegex(pattern);
          stream =
              stream.filter(
                  obj -> {
                    HeapClass cls = obj.getHeapClass();
                    return cls != null && regex.matcher(cls.getName()).matches();
                  });
        } else {
          stream =
              stream.filter(
                  obj -> {
                    HeapClass cls = obj.getHeapClass();
                    return cls != null && pattern.equals(cls.getName());
                  });
        }
      }
    }

    // Apply predicates
    if (!query.predicates().isEmpty()) {
      stream = stream.filter(obj -> matchesAllPredicates(objectToMap(obj), query.predicates()));
      if (!filterDesc.isEmpty()) {
        filterDesc += " with predicates";
      } else {
        filterDesc = " with predicates";
      }
    }

    // Print progress message
    System.err.println();
    System.err.printf(
        "Streaming through %,d objects%s (grouping by %s, keeping top %d)...%n",
        totalObjects, filterDesc, String.join(", ", groupByOp.groupFields()), topOp.n());
    System.err.flush();

    // Use bounded accumulation with priority queue
    int bufferSize = Math.max(topOp.n() * 5, 1000); // Keep 5x top N (min 1000)
    BoundedGroupAccumulator accumulator =
        new BoundedGroupAccumulator(groupByOp, bufferSize, topOp.n());

    long[] counter = {0};
    long[] lastReport = {System.currentTimeMillis()};

    stream.forEach(obj -> {
      Map<String, Object> map = objectToMap(obj);
      accumulator.accumulate(map, groupByOp);

      counter[0]++;
      long now = System.currentTimeMillis();
      if (now - lastReport[0] >= 500) {
        double progress = (double) counter[0] / totalObjects * 100.0;
        System.err.printf(
            "\rScanning objects: %.1f%% (%,d/%,d, %,d groups tracked)",
            progress, counter[0], totalObjects, accumulator.size());
        System.err.flush();
        lastReport[0] = now;
      }
    });

    // Clear progress lines
    System.err.print("\r" + " ".repeat(120) + "\r");
    System.err.print("\033[A");
    System.err.print("\r" + " ".repeat(120) + "\r");
    System.err.flush();

    // Get final top N results
    List<Map<String, Object>> results = accumulator.getTopN(topOp.n());

    // Apply sorting from TopOp
    if (topOp.orderBy() != null) {
      Comparator<Map<String, Object>> comparator = (m1, m2) -> {
        Object v1 = m1.get(topOp.orderBy());
        Object v2 = m2.get(topOp.orderBy());
        int cmp = compareValues(v1, v2);
        return topOp.descending() ? -cmp : cmp;
      };
      results.sort(comparator);
    }

    // Apply remaining pipeline operations
    for (PipelineOp op : remainingOps) {
      results = applyPipelineOp(session, results, op);
    }

    return results;
  }

  /**
   * Streaming evaluation from a filtered stream for count operations.
   */
  private static List<Map<String, Object>> evaluateObjectsStreamingCountFromStream(
      Stream<HeapObject> stream, long totalObjects) {
    long[] counter = {0};
    long[] lastReport = {System.currentTimeMillis()};

    stream.forEach(obj -> {
      counter[0]++;
      // Report progress every 500ms
      long now = System.currentTimeMillis();
      if (now - lastReport[0] >= 500) {
        double progress = (double) counter[0] / totalObjects * 100.0;
        System.err.printf("\rCounting objects: %.1f%% (%,d/%,d)",
            progress, counter[0], totalObjects);
        System.err.flush();
        lastReport[0] = now;
      }
    });

    // Clear progress lines
    System.err.print("\r" + " ".repeat(120) + "\r");
    System.err.print("\033[A");
    System.err.print("\r" + " ".repeat(120) + "\r");
    System.err.flush();

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("count", counter[0]);
    return List.of(result);
  }

  /**
   * Streaming evaluation from a filtered stream for sum operations.
   */
  private static List<Map<String, Object>> evaluateObjectsStreamingSumFromStream(
      Stream<HeapObject> stream, long totalObjects, SumOp op) {
    double[] sum = {0.0};
    long[] counter = {0};
    long[] lastReport = {System.currentTimeMillis()};

    stream.forEach(obj -> {
      Map<String, Object> map = objectToMap(obj);
      Object value = map.get(op.field());
      if (value instanceof Number num) {
        sum[0] += num.doubleValue();
      }

      counter[0]++;
      // Report progress every 500ms
      long now = System.currentTimeMillis();
      if (now - lastReport[0] >= 500) {
        double progress = (double) counter[0] / totalObjects * 100.0;
        System.err.printf("\rSumming %s: %.1f%% (%,d/%,d)",
            op.field(), progress, counter[0], totalObjects);
        System.err.flush();
        lastReport[0] = now;
      }
    });

    // Clear progress lines
    System.err.print("\r" + " ".repeat(120) + "\r");
    System.err.print("\033[A");
    System.err.print("\r" + " ".repeat(120) + "\r");
    System.err.flush();

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("sum", sum[0]);
    return List.of(result);
  }

  /**
   * Streaming evaluation from a filtered stream for stats operations.
   */
  private static List<Map<String, Object>> evaluateObjectsStreamingStatsFromStream(
      Stream<HeapObject> stream, long totalObjects, StatsOp op) {
    double[] sum = {0.0};
    double[] min = {Double.MAX_VALUE};
    double[] max = {Double.MIN_VALUE};
    long[] count = {0};
    long[] counter = {0};
    long[] lastReport = {System.currentTimeMillis()};

    stream.forEach(obj -> {
      Map<String, Object> map = objectToMap(obj);
      Object value = map.get(op.field());
      if (value instanceof Number num) {
        double d = num.doubleValue();
        sum[0] += d;
        min[0] = Math.min(min[0], d);
        max[0] = Math.max(max[0], d);
        count[0]++;
      }

      counter[0]++;
      // Report progress every 500ms
      long now = System.currentTimeMillis();
      if (now - lastReport[0] >= 500) {
        double progress = (double) counter[0] / totalObjects * 100.0;
        System.err.printf("\rComputing stats for %s: %.1f%% (%,d/%,d)",
            op.field(), progress, counter[0], totalObjects);
        System.err.flush();
        lastReport[0] = now;
      }
    });

    // Clear progress lines
    System.err.print("\r" + " ".repeat(120) + "\r");
    System.err.print("\033[A");
    System.err.print("\r" + " ".repeat(120) + "\r");
    System.err.flush();

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("count", count[0]);
    result.put("sum", sum[0]);
    result.put("avg", count[0] > 0 ? sum[0] / count[0] : 0.0);
    result.put("min", min[0]);
    result.put("max", max[0]);
    return List.of(result);
  }

  /**
   * Helper class to accumulate aggregation results for a group.
   */
  private static class GroupAccumulator {
    private final List<Object> key;
    private long count = 0;
    private double sum = 0.0;
    private double min = Double.MAX_VALUE;
    private double max = Double.MIN_VALUE;

    GroupAccumulator(List<Object> key, GroupByOp op) {
      this.key = key;
    }

    void accumulate(Map<String, Object> row, GroupByOp op) {
      count++;

      // Get value for aggregation
      Object value;
      if (op.valueExpr() != null) {
        value = op.valueExpr().evaluate(row);
      } else if (op.aggregation() != AggOp.COUNT) {
        // For non-COUNT aggregations, need a value field
        // Use first group field as default
        value = row.get(op.groupFields().get(0));
      } else {
        value = null;
      }

      if (value instanceof Number num) {
        double d = num.doubleValue();
        sum += d;
        min = Math.min(min, d);
        max = Math.max(max, d);
      }
    }

    Map<String, Object> toResultMap(GroupByOp op) {
      Map<String, Object> result = new LinkedHashMap<>();

      // Add group key fields
      for (int i = 0; i < op.groupFields().size(); i++) {
        result.put(op.groupFields().get(i), key.get(i));
      }

      // Determine column name for aggregation result
      // If aggregating a memory field, use the field name to trigger memory formatting
      String aggColumnName = HdumpPathEvaluator.getAggregationColumnName(op);

      // Add aggregation value
      switch (op.aggregation()) {
        case COUNT -> result.put(aggColumnName, count);
        case SUM -> result.put(aggColumnName, sum);
        case AVG -> result.put(aggColumnName, count > 0 ? sum / count : 0.0);
        case MIN -> result.put(aggColumnName, min);
        case MAX -> result.put(aggColumnName, max);
      }

      return result;
    }

    double getAggregationValue(AggOp aggOp) {
      return switch (aggOp) {
        case COUNT -> count;
        case SUM -> sum;
        case AVG -> count > 0 ? sum / count : 0.0;
        case MIN -> min;
        case MAX -> max;
      };
    }
  }

  /**
   * Bounded accumulator for groupBy + top(N) optimization.
   * Keeps only the top groups by periodically trimming to buffer size.
   */
  private static class BoundedGroupAccumulator {
    private final Map<List<Object>, GroupAccumulator> groups;
    private final int bufferSize;
    private final int targetSize;
    private final GroupByOp op;
    private long totalAccumulated = 0;

    BoundedGroupAccumulator(GroupByOp op, int bufferSize, int targetSize) {
      this.op = op;
      this.bufferSize = bufferSize;
      this.targetSize = targetSize;
      this.groups = new LinkedHashMap<>();
    }

    void accumulate(Map<String, Object> row, GroupByOp op) {
      // Extract group key
      List<Object> key = new ArrayList<>();
      for (String field : op.groupFields()) {
        key.add(row.get(field));
      }

      // Get or create accumulator
      GroupAccumulator acc = groups.computeIfAbsent(key, k -> new GroupAccumulator(k, op));
      acc.accumulate(row, op);

      totalAccumulated++;

      // Periodically trim to buffer size (every 10K objects)
      if (totalAccumulated % 10000 == 0 && groups.size() > bufferSize) {
        trimToBufferSize();
      }
    }

    private void trimToBufferSize() {
      // Sort groups by aggregation value and keep only top bufferSize
      List<Map.Entry<List<Object>, GroupAccumulator>> entries = new ArrayList<>(groups.entrySet());
      entries.sort((e1, e2) -> {
        double v1 = e1.getValue().getAggregationValue(op.aggregation());
        double v2 = e2.getValue().getAggregationValue(op.aggregation());
        return Double.compare(v2, v1); // Descending
      });

      // Keep only top bufferSize
      groups.clear();
      for (int i = 0; i < Math.min(bufferSize, entries.size()); i++) {
        groups.put(entries.get(i).getKey(), entries.get(i).getValue());
      }
    }

    int size() {
      return groups.size();
    }

    List<Map<String, Object>> getTopN(int n) {
      String aggField = getAggregationColumnName(op);
      // Final sort and return top N
      List<Map<String, Object>> results = groups.values().stream()
          .map(acc -> acc.toResultMap(op))
          .sorted((m1, m2) -> -compareValues(m1.get(aggField), m2.get(aggField))) // Descending
          .limit(n)
          .collect(Collectors.toList());
      return results;
    }
  }

  /**
   * Streaming evaluation with filter support for large heaps.
   * Applies type pattern and predicate filters during streaming, then delegates to
   * specialized streaming method based on operation type.
   */
  private static List<Map<String, Object>> evaluateObjectsStreamingWithFilters(
      HeapDump dump, Query query, PipelineOp firstOp, List<PipelineOp> remainingOps) {
    return evaluateObjectsStreamingWithFilters(dump, null, query, firstOp, remainingOps);
  }

  private static List<Map<String, Object>> evaluateObjectsStreamingWithFilters(
      HeapDump dump, HeapSession session, Query query, PipelineOp firstOp, List<PipelineOp> remainingOps) {

    // Build filtered stream
    Stream<HeapObject> stream;
    long totalObjects = dump.getObjectCount();
    String filterDesc = "";
    boolean usedClassIndex = false;

    // Try to use class-instances index for type filtering
    if (query.typePattern() != null && dump instanceof io.jafar.hdump.impl.HeapDumpImpl hdumpImpl
        && hdumpImpl.hasClassInstancesIndex()) {
      // FAST PATH: Use class-instances index to directly enumerate matching instances
      String pattern = normalizeClassName(query.typePattern());
      filterDesc = " matching " + query.typePattern() + " (class-instances index)";

      Set<String> matchingClassNames = findMatchingClasses(dump, pattern, query.instanceof_());

      // For each matching class, get instances directly from index
      stream = matchingClassNames.stream()
          .map(name -> dump.getClassByName(name))
          .filter(Optional::isPresent)
          .map(Optional::get)
          .flatMap(cls -> hdumpImpl.getObjectsOfClassFast(cls));

      usedClassIndex = true;
    } else {
      // SLOW PATH: Full scan with per-object filtering
      stream = dump.getObjects();

      // Filter by type pattern
      if (query.typePattern() != null) {
        String pattern = normalizeClassName(query.typePattern());
        filterDesc = " matching " + query.typePattern();

        if (query.instanceof_()) {
          Set<String> matchingClasses = findMatchingClasses(dump, pattern, true);
          stream =
              stream.filter(
                  obj -> {
                    HeapClass cls = obj.getHeapClass();
                    return cls != null && matchingClasses.contains(cls.getName());
                  });
        } else {
          if (pattern.contains("*")) {
            Pattern regex = globToRegex(pattern);
            stream =
                stream.filter(
                    obj -> {
                      HeapClass cls = obj.getHeapClass();
                      return cls != null && regex.matcher(cls.getName()).matches();
                    });
          } else {
            stream =
                stream.filter(
                    obj -> {
                      HeapClass cls = obj.getHeapClass();
                      return cls != null && pattern.equals(cls.getName());
                    });
          }
        }
      }
    }

    // Apply predicates
    if (!query.predicates().isEmpty()) {
      stream = stream.filter(obj -> matchesAllPredicates(objectToMap(obj), query.predicates()));
      if (!filterDesc.isEmpty()) {
        filterDesc += " with predicates";
      } else {
        filterDesc = " with predicates";
      }
    }

    // Print progress message based on operation type
    System.err.println();
    if (firstOp instanceof TopOp topOp) {
      System.err.printf(
          "Streaming through %,d objects%s (top %d)...%n", totalObjects, filterDesc, topOp.n());
    } else if (firstOp instanceof GroupByOp groupByOp) {
      System.err.printf(
          "Streaming through %,d objects%s (grouping by %s)...%n",
          totalObjects, filterDesc, String.join(", ", groupByOp.groupFields()));
    } else if (firstOp instanceof CountOp) {
      System.err.printf("Streaming through %,d objects%s (counting)...%n", totalObjects, filterDesc);
    } else if (firstOp instanceof SumOp sumOp) {
      System.err.printf(
          "Streaming through %,d objects%s (sum of %s)...%n",
          totalObjects, filterDesc, sumOp.field());
    } else if (firstOp instanceof StatsOp statsOp) {
      System.err.printf(
          "Streaming through %,d objects%s (stats for %s)...%n",
          totalObjects, filterDesc, statsOp.field());
    }
    System.err.flush();

    // Delegate to specialized streaming method based on operation type
    List<Map<String, Object>> results;
    if (firstOp instanceof TopOp topOp) {
      results = evaluateObjectsStreamingTopFromStream(stream, dump.getObjectCount(), topOp);
    } else if (firstOp instanceof GroupByOp groupByOp) {
      results = evaluateObjectsStreamingGroupByFromStream(stream, dump.getObjectCount(), groupByOp);
    } else if (firstOp instanceof CountOp) {
      results = evaluateObjectsStreamingCountFromStream(stream, dump.getObjectCount());
    } else if (firstOp instanceof SumOp sumOp) {
      results = evaluateObjectsStreamingSumFromStream(stream, dump.getObjectCount(), sumOp);
    } else if (firstOp instanceof StatsOp statsOp) {
      results = evaluateObjectsStreamingStatsFromStream(stream, dump.getObjectCount(), statsOp);
    } else {
      throw new IllegalStateException("Unsupported streaming operation: " + firstOp.getClass().getSimpleName());
    }

    // Apply remaining pipeline operations
    for (PipelineOp op : remainingOps) {
      results = applyPipelineOp(null, results, op);
    }

    return results;
  }

  private static List<Map<String, Object>> evaluateObjects(HeapDump dump, Query query) {
    Stream<HeapObject> stream = dump.getObjects();

    // Filter by type pattern
    if (query.typePattern() != null) {
      String pattern = normalizeClassName(query.typePattern());
      if (query.instanceof_()) {
        // Include subclasses
        Set<String> matchingClasses = findMatchingClasses(dump, pattern, true);
        stream =
            stream.filter(
                obj -> {
                  HeapClass cls = obj.getHeapClass();
                  return cls != null && matchingClasses.contains(cls.getName());
                });
      } else {
        // Exact match or pattern
        if (pattern.contains("*")) {
          Pattern regex = globToRegex(pattern);
          stream =
              stream.filter(
                  obj -> {
                    HeapClass cls = obj.getHeapClass();
                    return cls != null && regex.matcher(cls.getName()).matches();
                  });
        } else {
          stream =
              stream.filter(
                  obj -> {
                    HeapClass cls = obj.getHeapClass();
                    return cls != null && pattern.equals(cls.getName());
                  });
        }
      }
    }

    // Apply predicates
    if (!query.predicates().isEmpty()) {
      stream = stream.filter(obj -> matchesAllPredicates(objectToMap(obj), query.predicates()));
    }

    // Warn about memory usage for large unfiltered queries
    long totalObjects = dump.getObjectCount();
    if (totalObjects > 10_000_000 && query.typePattern() == null && query.predicates().isEmpty()) {
      System.err.println();
      System.err.printf("WARNING: Loading all %,d objects may cause out-of-memory errors.%n", totalObjects);
      System.err.println("Consider filtering by type: show objects<ClassName>");
      System.err.println("Or viewing classes instead: show classes | top(10, instanceCount)");
      System.err.println();
    }

    return stream.map(HdumpPathEvaluator::objectToMap).collect(Collectors.toList());
  }

  private static List<Map<String, Object>> evaluateClasses(HeapDump dump, Query query) {
    Stream<HeapClass> stream = dump.getClasses().stream();

    // Filter by type pattern
    if (query.typePattern() != null) {
      String pattern = normalizeClassName(query.typePattern());
      if (pattern.contains("*")) {
        Pattern regex = globToRegex(pattern);
        stream =
            stream.filter(cls -> cls.getName() != null && regex.matcher(cls.getName()).matches());
      } else {
        stream = stream.filter(cls -> pattern.equals(cls.getName()));
      }
    }

    // Apply predicates
    List<Map<String, Object>> results =
        stream.map(HdumpPathEvaluator::classToMap).collect(Collectors.toList());

    if (!query.predicates().isEmpty()) {
      results =
          results.stream()
              .filter(map -> matchesAllPredicates(map, query.predicates()))
              .collect(Collectors.toList());
    }

    return results;
  }

  private static List<Map<String, Object>> evaluateGcRoots(HeapDump dump, Query query) {
    Stream<GcRoot> stream = dump.getGcRoots().stream();

    // Filter by type
    if (query.typePattern() != null) {
      String typeStr = query.typePattern().toUpperCase();
      try {
        GcRoot.Type type = GcRoot.Type.valueOf(typeStr);
        stream = stream.filter(root -> root.getType() == type);
      } catch (IllegalArgumentException e) {
        // Invalid type - return empty
        return List.of();
      }
    }

    // Apply predicates
    List<Map<String, Object>> results =
        stream.map(HdumpPathEvaluator::gcRootToMap).collect(Collectors.toList());

    if (!query.predicates().isEmpty()) {
      results =
          results.stream()
              .filter(map -> matchesAllPredicates(map, query.predicates()))
              .collect(Collectors.toList());
    }

    return results;
  }

  // === Object to Map conversions ===

  private static Map<String, Object> objectToMap(HeapObject obj) {
    Map<String, Object> map = new LinkedHashMap<>();
    HeapClass cls = obj.getHeapClass();

    map.put(ObjectFields.ID, obj.getId());
    map.put(ObjectFields.CLASS, cls != null ? cls.getName() : "unknown");
    map.put(ObjectFields.CLASS_NAME, cls != null ? cls.getName() : "unknown");
    map.put(ObjectFields.SHALLOW, obj.getShallowSize());
    map.put(ObjectFields.SHALLOW_SIZE, obj.getShallowSize());

    long retained = obj.getRetainedSize();
    if (retained >= 0) {
      map.put(ObjectFields.RETAINED, retained);
      map.put(ObjectFields.RETAINED_SIZE, retained);
    }

    if (obj.isArray()) {
      map.put(ObjectFields.ARRAY_LENGTH, obj.getArrayLength());
    }

    // Add string value for String objects
    if (cls != null && "java.lang.String".equals(cls.getName())) {
      String value = obj.getStringValue();
      if (value != null) {
        map.put(ObjectFields.STRING_VALUE, value);
      }
    }

    return map;
  }

  private static Map<String, Object> classToMap(HeapClass cls) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(ClassFields.ID, cls.getId());
    map.put(ClassFields.NAME, cls.getName());
    map.put(ClassFields.SIMPLE_NAME, cls.getSimpleName());
    map.put(ClassFields.INSTANCE_COUNT, cls.getInstanceCount());
    map.put(ClassFields.INSTANCE_SIZE, cls.getInstanceSize());

    HeapClass superClass = cls.getSuperClass();
    if (superClass != null) {
      map.put(ClassFields.SUPER_CLASS, superClass.getName());
    }

    map.put(ClassFields.IS_ARRAY, cls.isArray());
    return map;
  }

  private static Map<String, Object> gcRootToMap(GcRoot root) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(GcRootFields.TYPE, root.getType().name());
    map.put(GcRootFields.OBJECT_ID, root.getObjectId());

    HeapObject obj = root.getObject();
    if (obj != null) {
      HeapClass cls = obj.getHeapClass();
      map.put(
          GcRootFields.OBJECT,
          cls != null ? cls.getName() + "@" + Long.toHexString(obj.getId()) : "unknown");

      // Add size fields from the rooted object
      map.put(GcRootFields.SHALLOW, obj.getShallowSize());
      map.put(GcRootFields.SHALLOW_SIZE, obj.getShallowSize());

      long retained = obj.getRetainedSize();
      if (retained >= 0) {
        map.put(GcRootFields.RETAINED, retained);
        map.put(GcRootFields.RETAINED_SIZE, retained);
      }
    }

    int threadSerial = root.getThreadSerial();
    if (threadSerial >= 0) {
      map.put(GcRootFields.THREAD_SERIAL, threadSerial);
    }

    int frameNum = root.getFrameNumber();
    if (frameNum >= 0) {
      map.put(GcRootFields.FRAME_NUMBER, frameNum);
    }

    return map;
  }

  // === Predicate matching ===

  private static boolean matchesAllPredicates(Map<String, Object> map, List<Predicate> predicates) {
    for (Predicate pred : predicates) {
      if (!matchesPredicate(map, pred)) {
        return false;
      }
    }
    return true;
  }

  private static boolean matchesPredicate(Map<String, Object> map, Predicate pred) {
    return switch (pred) {
      case FieldPredicate fp -> matchesFieldPredicate(map, fp);
      case ExprPredicate ep -> evaluateBoolExpr(map, ep.expr());
    };
  }

  private static boolean matchesFieldPredicate(Map<String, Object> map, FieldPredicate fp) {
    Object value = extractFieldValue(map, fp.fieldPath());
    return compareValues(value, fp.op(), fp.literal());
  }

  private static boolean evaluateBoolExpr(Map<String, Object> map, BoolExpr expr) {
    return switch (expr) {
      case CompExpr ce -> {
        Object value = extractFieldValue(map, ce.fieldPath());
        yield compareValues(value, ce.op(), ce.literal());
      }
      case LogicalExpr le -> {
        boolean left = evaluateBoolExpr(map, le.left());
        if (le.op() == LogicalOp.AND) {
          yield left && evaluateBoolExpr(map, le.right());
        } else {
          yield left || evaluateBoolExpr(map, le.right());
        }
      }
      case NotExpr ne -> !evaluateBoolExpr(map, ne.inner());
    };
  }

  private static Object extractFieldValue(Map<String, Object> map, List<String> path) {
    if (path.isEmpty()) return null;

    Object current = map.get(path.get(0));
    for (int i = 1; i < path.size() && current != null; i++) {
      if (current instanceof Map<?, ?> m) {
        current = m.get(path.get(i));
      } else {
        return null;
      }
    }
    return current;
  }

  private static boolean compareValues(Object value, Op op, Object literal) {
    if (value == null) {
      return op == Op.EQ && literal == null || op == Op.NE && literal != null;
    }

    if (literal == null) {
      return op == Op.NE;
    }

    // Regex matching
    if (op == Op.REGEX) {
      String str = String.valueOf(value);
      String pattern = String.valueOf(literal);
      return Pattern.matches(pattern, str);
    }

    // Numeric comparison
    if (value instanceof Number && literal instanceof Number) {
      double v = ((Number) value).doubleValue();
      double l = ((Number) literal).doubleValue();
      return switch (op) {
        case EQ -> v == l;
        case NE -> v != l;
        case GT -> v > l;
        case GE -> v >= l;
        case LT -> v < l;
        case LE -> v <= l;
        case REGEX -> false;
      };
    }

    // String comparison
    String vStr = String.valueOf(value);
    String lStr = String.valueOf(literal);
    int cmp = vStr.compareTo(lStr);
    return switch (op) {
      case EQ -> cmp == 0;
      case NE -> cmp != 0;
      case GT -> cmp > 0;
      case GE -> cmp >= 0;
      case LT -> cmp < 0;
      case LE -> cmp <= 0;
      case REGEX -> Pattern.matches(lStr, vStr);
    };
  }

  // === Pipeline operations ===

  private static List<Map<String, Object>> applyPipelineOp(
      HeapSession session, List<Map<String, Object>> results, PipelineOp op) {
    return switch (op) {
      case SelectOp s -> applySelect(results, s);
      case TopOp t -> applyTop(results, t);
      case GroupByOp g -> applyGroupBy(results, g);
      case CountOp c -> applyCount(results);
      case SumOp s -> applySum(results, s);
      case StatsOp s -> applyStats(results, s);
      case SortByOp s -> applySortBy(results, s);
      case HeadOp h -> results.stream().limit(h.n()).collect(Collectors.toList());
      case TailOp t -> {
        int size = results.size();
        int skip = Math.max(0, size - t.n());
        yield results.stream().skip(skip).collect(Collectors.toList());
      }
      case FilterOp f -> results.stream()
          .filter(map -> matchesPredicate(map, f.predicate()))
          .collect(Collectors.toList());
      case DistinctOp d -> applyDistinct(results, d);
      case LenOp l -> applyStringTransform(results, l.field(), v -> {
        if (v instanceof String s) return (long) s.length();
        if (v instanceof java.util.Collection<?> c) return (long) c.size();
        return v;
      });
      case UppercaseOp u -> applyStringTransform(results, u.field(),
          v -> v instanceof String s ? s.toUpperCase() : v);
      case LowercaseOp l -> applyStringTransform(results, l.field(),
          v -> v instanceof String s ? s.toLowerCase() : v);
      case TrimOp t -> applyStringTransform(results, t.field(),
          v -> v instanceof String s ? s.trim() : v);
      case ReplaceOp r -> applyStringTransform(results, r.field(),
          v -> v instanceof String s ? s.replace(r.target(), r.replacement()) : v);
      case AbsOp a -> applyStringTransform(results, a.field(), v -> {
        if (v instanceof Long l) return Math.abs(l);
        if (v instanceof Double d2) return Math.abs(d2);
        if (v instanceof Number n) return Math.abs(n.doubleValue());
        return v;
      });
      case RoundOp r -> applyStringTransform(results, r.field(), v -> {
        if (v instanceof Double d2) return Math.round(d2);
        if (v instanceof Number n) return Math.round(n.doubleValue());
        return v;
      });
      case FloorOp f -> applyStringTransform(results, f.field(), v -> {
        if (v instanceof Double d2) return (long) Math.floor(d2);
        if (v instanceof Number n) return (long) Math.floor(n.doubleValue());
        return v;
      });
      case CeilOp c -> applyStringTransform(results, c.field(), v -> {
        if (v instanceof Double d2) return (long) Math.ceil(d2);
        if (v instanceof Number n) return (long) Math.ceil(n.doubleValue());
        return v;
      });
      case PathToRootOp p -> {
        if (session == null) {
          throw new IllegalStateException("pathToRoot requires heap session context (not available after streaming aggregation)");
        }
        yield applyPathToRoot(session.getHeapDump(), results);
      }
      case RetentionPathsOp r -> {
        if (session == null) {
          throw new IllegalStateException("retentionPaths requires heap session context (not available after streaming aggregation)");
        }
        yield applyRetentionPaths(session.getHeapDump(), results);
      }
      case RetainedBreakdownOp b -> {
        if (session == null) {
          throw new IllegalStateException("retainedBreakdown requires heap session context (not available after streaming aggregation)");
        }
        yield applyRetainedBreakdown(session.getHeapDump(), results, b.maxDepth());
      }
      case CheckLeaksOp c -> {
        if (session == null) {
          throw new IllegalStateException("checkLeaks requires heap session context (not available after streaming aggregation)");
        }
        yield applyCheckLeaks(session, results, c);
      }
      case DominatorsOp d -> {
        if (session == null) {
          throw new IllegalStateException("dominators requires heap session context (not available after streaming aggregation)");
        }
        yield applyDominators(session, session.getHeapDump(), results, d);
      }
    };
  }

  private static List<Map<String, Object>> applySelect(
      List<Map<String, Object>> results, SelectOp op) {
    return results.stream()
        .map(
            row -> {
              Map<String, Object> selected = new LinkedHashMap<>();
              for (SelectField field : op.fields()) {
                String key = field.alias() != null ? field.alias() : field.field();
                selected.put(key, row.get(field.field()));
              }
              return selected;
            })
        .collect(Collectors.toList());
  }

  private static List<Map<String, Object>> applyTop(List<Map<String, Object>> results, TopOp op) {
    Stream<Map<String, Object>> stream = results.stream();

    if (op.orderBy() != null) {
      // Check for common mistakes: trying to use retainedSize on classes
      if ((op.orderBy().equals("retainedSize") || op.orderBy().equals("retained"))
          && !results.isEmpty()
          && results.get(0).containsKey(ClassFields.NAME)
          && !results.get(0).containsKey(ObjectFields.RETAINED_SIZE)) {
        throw new IllegalArgumentException(
            "Field '" + op.orderBy() + "' is not available on classes. "
                + "To aggregate retained sizes by class, use: "
                + "show objects | groupBy(className, agg=sum, value=retainedSize) | top(10)");
      }

      // Create comparator that compares actual values with null-safe handling
      Comparator<Map<String, Object>> cmp = (m1, m2) -> {
        Object v1 = m1.get(op.orderBy());
        Object v2 = m2.get(op.orderBy());
        return compareValues(v1, v2);
      };

      // top() defaults to descending (largest first) - reverse for ascending if needed
      if (op.descending()) {
        cmp = cmp.reversed();
      }
      stream = stream.sorted(cmp);
    }

    return stream.limit(op.n()).collect(Collectors.toList());
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static int compareValues(Object v1, Object v2) {
    if (v1 == null && v2 == null) return 0;
    if (v1 == null) return 1; // nulls sort last
    if (v2 == null) return -1;

    if (v1 instanceof Comparable && v2 instanceof Comparable) {
      try {
        return ((Comparable) v1).compareTo(v2);
      } catch (ClassCastException e) {
        // Fall back to string comparison for incompatible types
        return String.valueOf(v1).compareTo(String.valueOf(v2));
      }
    }
    return String.valueOf(v1).compareTo(String.valueOf(v2));
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Comparable<Object> toComparable(Object value) {
    if (value == null) return o -> 1; // nulls sort last
    if (value instanceof Comparable comp) {
      // Wrap to handle type mismatch gracefully (e.g., comparing Integer with String)
      return o -> {
        if (o == null) return -1;
        if (value.getClass().isInstance(o)) {
          return comp.compareTo(o);
        }
        // Fall back to string comparison for incompatible types
        return String.valueOf(value).compareTo(String.valueOf(o));
      };
    }
    return o -> String.valueOf(value).compareTo(String.valueOf(o));
  }

  private static List<Map<String, Object>> applyGroupBy(
      List<Map<String, Object>> results, GroupByOp op) {
    // Group by the specified fields
    Map<List<Object>, List<Map<String, Object>>> groups =
        results.stream()
            .collect(
                Collectors.groupingBy(
                    row -> {
                      List<Object> key = new ArrayList<>();
                      for (String field : op.groupFields()) {
                        key.add(row.get(field));
                      }
                      return key;
                    }));

    // Apply aggregation
    List<Map<String, Object>> aggregated = new ArrayList<>();
    for (Map.Entry<List<Object>, List<Map<String, Object>>> entry : groups.entrySet()) {
      Map<String, Object> row = new LinkedHashMap<>();

      // Add group key fields
      List<Object> keyValues = entry.getKey();
      for (int i = 0; i < op.groupFields().size(); i++) {
        row.put(op.groupFields().get(i), keyValues.get(i));
      }

      // Add aggregation result
      List<Map<String, Object>> group = entry.getValue();
      switch (op.aggregation()) {
        case COUNT -> row.put("count", group.size());
        case SUM, AVG, MIN, MAX -> {
          ValueExpr valueExpr = op.valueExpr();
          if (valueExpr != null) {
            // Use provided expression
            double[] values =
                group.stream()
                    .mapToDouble(valueExpr::evaluate)
                    .filter(v -> !Double.isNaN(v))
                    .toArray();
            if (values.length > 0) {
              // Extract field name from valueExpr if it's a simple field reference
              String fieldName = extractFieldNameFromExpr(valueExpr);
              applyAggregation(row, op.aggregation(), values, fieldName);
            }
          } else {
            // Fall back to first numeric field
            String valueField = findFirstNumericField(group);
            if (valueField != null) {
              double[] values =
                  group.stream()
                      .map(m -> m.get(valueField))
                      .filter(v -> v instanceof Number)
                      .mapToDouble(v -> ((Number) v).doubleValue())
                      .toArray();
              if (values.length > 0) {
                applyAggregation(row, op.aggregation(), values, valueField);
              }
            }
          }
        }
      }

      aggregated.add(row);
    }

    // Apply sorting if requested
    if (op.sortBy() != null) {
      String keyField = op.groupFields().isEmpty() ? "key" : op.groupFields().get(0);
      String valueField = getAggregationFieldName(op.aggregation());
      RowSorter.sortGroupByResults(aggregated, op.sortBy(), keyField, valueField, op.ascending());
    }

    return aggregated;
  }

  /** Returns the field name for the given aggregation operation. */
  private static String getAggregationFieldName(AggOp aggOp) {
    return switch (aggOp) {
      case COUNT -> "count";
      case SUM -> "sum";
      case AVG -> "avg";
      case MIN -> "min";
      case MAX -> "max";
    };
  }

  private static String findFirstNumericField(List<Map<String, Object>> rows) {
    if (rows.isEmpty()) return null;
    for (Map.Entry<String, Object> entry : rows.get(0).entrySet()) {
      if (entry.getValue() instanceof Number) {
        return entry.getKey();
      }
    }
    return null;
  }

  private static void applyAggregation(Map<String, Object> row, AggOp aggOp, double[] values, String valueField) {
    // Use field name as column name if it's a memory field, otherwise use aggregation name
    String columnName;
    if (valueField != null && isMemoryFieldName(valueField)) {
      columnName = valueField;
    } else {
      columnName = switch (aggOp) {
        case COUNT -> "count";
        case SUM -> "sum";
        case AVG -> "avg";
        case MIN -> "min";
        case MAX -> "max";
      };
    }

    switch (aggOp) {
      case SUM -> {
        double sum = 0;
        for (double v : values) sum += v;
        row.put(columnName, sum);
      }
      case AVG -> {
        double sum = 0;
        for (double v : values) sum += v;
        row.put(columnName, sum / values.length);
      }
      case MIN -> {
        double min = Double.MAX_VALUE;
        for (double v : values) if (v < min) min = v;
        row.put(columnName, min);
      }
      case MAX -> {
        double max = Double.MIN_VALUE;
        for (double v : values) if (v > max) max = v;
        row.put(columnName, max);
      }
      default -> {}
    }
  }

  /** Checks if a field name indicates memory values. */
  private static boolean isMemoryFieldName(String fieldName) {
    if (fieldName == null) return false;
    String lower = fieldName.toLowerCase();
    return lower.endsWith("size")
        || lower.contains("shallow")
        || lower.contains("retained")
        || lower.contains("memory")
        || lower.equals("bytes");
  }

  /**
   * Gets the column name for an aggregation result.
   * If aggregating a memory field, returns the field name to trigger memory formatting.
   * Otherwise returns standard aggregation names (sum, avg, etc.).
   */
  private static String getAggregationColumnName(GroupByOp op) {
    // Try to extract field name from valueExpr
    String fieldName = extractFieldNameFromExpr(op.valueExpr());

    // If it's a memory-related field, use the field name to trigger memory formatting
    if (fieldName != null && isMemoryFieldName(fieldName)) {
      return fieldName;
    }

    // Otherwise use standard aggregation names
    return switch (op.aggregation()) {
      case COUNT -> "count";
      case SUM -> "sum";
      case AVG -> "avg";
      case MIN -> "min";
      case MAX -> "max";
    };
  }

  /** Extracts field name from a ValueExpr if it's a simple field reference. */
  private static String extractFieldNameFromExpr(io.jafar.shell.core.expr.ValueExpr expr) {
    if (expr instanceof io.jafar.shell.core.expr.FieldRef ref) {
      return ref.field();
    }
    return null;
  }

  private static List<Map<String, Object>> applyCount(List<Map<String, Object>> results) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("count", results.size());
    return List.of(row);
  }

  private static List<Map<String, Object>> applySum(List<Map<String, Object>> results, SumOp op) {
    double sum =
        results.stream()
            .map(m -> m.get(op.field()))
            .filter(v -> v instanceof Number)
            .mapToDouble(v -> ((Number) v).doubleValue())
            .sum();

    Map<String, Object> row = new LinkedHashMap<>();
    // Use field name if it's a memory field, otherwise use "sum"
    String columnName = isMemoryFieldName(op.field()) ? op.field() : "sum";
    row.put(columnName, sum);
    return List.of(row);
  }

  private static List<Map<String, Object>> applyStats(
      List<Map<String, Object>> results, StatsOp op) {
    DoubleSummaryStatistics stats =
        results.stream()
            .map(m -> m.get(op.field()))
            .filter(v -> v instanceof Number)
            .mapToDouble(v -> ((Number) v).doubleValue())
            .summaryStatistics();

    Map<String, Object> row = new LinkedHashMap<>();
    // For stats, use field name as prefix if it's a memory field
    boolean isMemory = isMemoryFieldName(op.field());
    row.put("count", stats.getCount());
    row.put(isMemory ? op.field() + "_sum" : "sum", stats.getSum());
    row.put(isMemory ? op.field() + "_min" : "min", stats.getMin());
    row.put(isMemory ? op.field() + "_max" : "max", stats.getMax());
    row.put(isMemory ? op.field() + "_avg" : "avg", stats.getAverage());
    return List.of(row);
  }

  private static List<Map<String, Object>> applySortBy(
      List<Map<String, Object>> results, SortByOp op) {
    Comparator<Map<String, Object>> cmp = null;

    for (SortField sf : op.fields()) {
      // Check for common mistakes: trying to use retainedSize on classes
      if ((sf.field().equals("retainedSize") || sf.field().equals("retained"))
          && !results.isEmpty()
          && results.get(0).containsKey(ClassFields.NAME)
          && !results.get(0).containsKey(ObjectFields.RETAINED_SIZE)) {
        throw new IllegalArgumentException(
            "Field '" + sf.field() + "' is not available on classes. "
                + "To aggregate retained sizes by class, use: "
                + "show objects | groupBy(className, agg=sum, value=retainedSize) | top(10)");
      }

      Comparator<Map<String, Object>> fieldCmp =
          Comparator.comparing(m -> toComparable(m.get(sf.field())));
      if (sf.descending()) {
        fieldCmp = fieldCmp.reversed();
      }
      cmp = cmp == null ? fieldCmp : cmp.thenComparing(fieldCmp);
    }

    if (cmp != null) {
      results = new ArrayList<>(results);
      results.sort(cmp);
    }

    return results;
  }

  private static List<Map<String, Object>> applyDistinct(
      List<Map<String, Object>> results, DistinctOp op) {
    Set<Object> seen = new HashSet<>();
    return results.stream()
        .filter(
            row -> {
              Object value = row.get(op.field());
              return seen.add(value);
            })
        .collect(Collectors.toList());
  }

  private static List<Map<String, Object>> applyStringTransform(
      List<Map<String, Object>> results, String field, Function<Object, Object> transform) {
    return results.stream()
        .map(row -> {
          Map<String, Object> newRow = new LinkedHashMap<>(row);
          Object val = newRow.get(field);
          if (val != null) {
            newRow.put(field, transform.apply(val));
          }
          return newRow;
        })
        .collect(Collectors.toList());
  }

  // === Utility methods ===

  /**
   * Normalizes a class name from Java format (java.util.HashMap) to internal JVM format (java/util/HashMap).
   * Heap dumps store class names with slashes, but users naturally type them with dots.
   */
  private static String normalizeClassName(String className) {
    return className.replace('.', '/');
  }

  private static Set<String> findMatchingClasses(
      HeapDump dump, String pattern, boolean includeSubclasses) {
    Set<String> result = new HashSet<>();

    // Find the base class
    Optional<HeapClass> baseClass = dump.getClassByName(pattern);
    if (baseClass.isEmpty()) {
      // Try pattern matching
      Pattern regex = globToRegex(pattern);
      for (HeapClass cls : dump.getClasses()) {
        if (cls.getName() != null && regex.matcher(cls.getName()).matches()) {
          result.add(cls.getName());
          if (includeSubclasses) {
            addSubclasses(dump, cls.getName(), result);
          }
        }
      }
    } else {
      result.add(pattern);
      if (includeSubclasses) {
        addSubclasses(dump, pattern, result);
      }
    }

    return result;
  }

  private static void addSubclasses(HeapDump dump, String className, Set<String> result) {
    for (HeapClass cls : dump.getClasses()) {
      HeapClass superCls = cls.getSuperClass();
      while (superCls != null) {
        if (className.equals(superCls.getName())) {
          result.add(cls.getName());
          break;
        }
        superCls = superCls.getSuperClass();
      }
    }
  }

  private static Pattern globToRegex(String glob) {
    StringBuilder regex = new StringBuilder("^");
    for (char c : glob.toCharArray()) {
      switch (c) {
        case '*' -> regex.append(".*");
        case '?' -> regex.append(".");
        case '.' -> regex.append("\\.");
        case '$' -> regex.append("\\$");
        case '[', ']', '(', ')', '{', '}', '|', '^', '+', '\\' -> regex.append("\\").append(c);
        default -> regex.append(c);
      }
    }
    regex.append("$");
    return Pattern.compile(regex.toString());
  }

  private static List<Map<String, Object>> applyPathToRoot(
      HeapDump dump, List<Map<String, Object>> results) {
    List<Map<String, Object>> paths = new ArrayList<>();

    for (Map<String, Object> row : results) {
      Object idObj = row.get("id");
      if (idObj == null) continue;

      long id = idObj instanceof Number ? ((Number) idObj).longValue() : Long.parseLong(idObj.toString());
      HeapObject obj = dump.getObjectById(id).orElse(null);
      if (obj == null) continue;

      // Find path to GC root
      List<PathStep> path = dump.findPathToGcRoot(obj);
      if (path.isEmpty()) continue;

      // Convert path to result format - one row per step in path
      for (int i = 0; i < path.size(); i++) {
        PathStep step = path.get(i);
        HeapObject pathObj = step.object();
        Map<String, Object> pathRow = new LinkedHashMap<>();
        pathRow.put("step", i);
        pathRow.put("id", pathObj.getId());
        pathRow.put("class", pathObj.getHeapClass() != null ? pathObj.getHeapClass().getName() : "unknown");
        pathRow.put("shallow", pathObj.getShallowSize());
        pathRow.put("retained", pathObj.getRetainedSize());
        pathRow.put("description", pathObj.getDescription());
        pathRow.put("referenceType", step.fieldName() != null ? step.fieldName() : "GC Root");

        paths.add(pathRow);
      }
    }

    return paths;
  }

  /**
   * Aggregates paths-to-GC-root at the class level across all input objects.
   *
   * <p>For every input object we find the shortest path to a GC root, collapse each step to its
   * class name, and then merge identical class-level paths. The result is a list of rows sorted
   * by {@code count} descending with columns {@code count}, {@code depth}, {@code retainedSize},
   * and {@code path} (class names joined with " → ", from GC root to the target).
   */
  private static List<Map<String, Object>> applyRetentionPaths(
      HeapDump dump, List<Map<String, Object>> results) {

    // path string → (count, total retainedSize)
    Map<String, long[]> pathStats = new LinkedHashMap<>();

    for (Map<String, Object> row : results) {
      Object idObj = row.get("id");
      if (idObj == null) continue;

      long id = idObj instanceof Number ? ((Number) idObj).longValue() : Long.parseLong(idObj.toString());
      HeapObject obj = dump.getObjectById(id).orElse(null);
      if (obj == null) continue;

      List<PathStep> path = dump.findPathToGcRoot(obj);
      if (path.isEmpty()) continue;

      // Build class-level path string: "GCRoot[TYPE] → ClassName1 → … → TargetClass"
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < path.size(); i++) {
        if (i > 0) sb.append(" → ");
        PathStep step = path.get(i);
        HeapObject stepObj = step.object();
        HeapClass cls = stepObj.getHeapClass();
        if (cls != null) {
          sb.append(cls.getName().replace('/', '.'));
        } else {
          sb.append("unknown");
        }
      }
      String pathKey = sb.toString();

      long retained = obj.getRetainedSize();
      long[] stats = pathStats.computeIfAbsent(pathKey, k -> new long[]{0L, 0L, path.size()});
      stats[0]++;                                  // count
      stats[1] += Math.max(retained, 0L);          // total retainedSize
      // depth is the same for all objects on the same class path, already set at init
    }

    return pathStats.entrySet().stream()
        .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
        .map(e -> {
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("count", e.getValue()[0]);
          result.put("depth", (int) e.getValue()[2]);
          result.put("retainedSize", e.getValue()[1]);
          result.put("path", e.getKey());
          return result;
        })
        .collect(Collectors.toList());
  }

  /**
   * Recursively expands the dominator subtrees of input objects and aggregates at class level.
   *
   * <p>Key: {@code depth + "\0" + className} so the same class at different depths is separate.
   * Values: {@code [count, shallowSize, retainedSize]}.
   */
  private static List<Map<String, Object>> applyRetainedBreakdown(
      HeapDump dump, List<Map<String, Object>> results, int maxDepth) {

    if (!(dump instanceof io.jafar.hdump.impl.HeapDumpImpl heapDumpImpl)
        || !heapDumpImpl.hasFullDominatorTree()) {
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", "retainedBreakdown requires a computed dominator tree — run dominators() first");
      return List.of(err);
    }

    // (depth, className) → [count, shallowSize, retainedSize]
    // Use insertion-ordered map so depth-0 entries stay before depth-1, etc.
    Map<String, long[]> agg = new LinkedHashMap<>();

    // Detect input type: class rows (from classes/ query) have "instanceCount" but no "retained".
    // Object rows (from objects/ query) have "retained".
    boolean classInput = !results.isEmpty()
        && results.get(0).containsKey(ClassFields.INSTANCE_COUNT)
        && !results.get(0).containsKey(ObjectFields.RETAINED);

    if (classInput) {
      // Collect the class names from the class rows
      Set<String> classNames = new java.util.HashSet<>();
      for (Map<String, Object> row : results) {
        Object name = row.get(ClassFields.NAME);
        if (name instanceof String s) classNames.add(normalizeClassName(s));
      }
      if (!classNames.isEmpty()) {
        System.err.println();
        System.err.printf(
            "Scanning instances of %d class(es) for retained breakdown...%n", classNames.size());
        System.err.flush();
        dump.getObjects()
            .filter(obj -> {
              HeapClass cls = obj.getHeapClass();
              return cls != null && classNames.contains(cls.getName());
            })
            .forEach(obj -> walkDominatedSubtree(heapDumpImpl, obj, 0, maxDepth, agg));
      }
    } else {
      for (Map<String, Object> row : results) {
        Object idObj = row.get("id");
        if (idObj == null) continue;
        long id = idObj instanceof Number ? ((Number) idObj).longValue() : Long.parseLong(idObj.toString());
        HeapObject root = dump.getObjectById(id).orElse(null);
        if (root == null) continue;
        walkDominatedSubtree(heapDumpImpl, root, 0, maxDepth, agg);
      }
    }

    if (agg.isEmpty()) {
      return List.of();
    }

    // Build result rows sorted by depth asc, retainedSize desc
    List<Map<String, Object>> rows = agg.entrySet().stream()
        .map(e -> {
          int sep = e.getKey().indexOf('\0');
          int depth = Integer.parseInt(e.getKey().substring(0, sep));
          String className = e.getKey().substring(sep + 1);
          long[] s = e.getValue();
          Map<String, Object> r = new LinkedHashMap<>();
          r.put("depth", depth);
          r.put("className", className);
          r.put("count", s[0]);
          r.put("shallowSize", s[1]);
          r.put("retainedSize", s[2]);
          return r;
        })
        .sorted(Comparator.comparingInt((Map<String, Object> r) -> (int) r.get("depth"))
            .thenComparingLong(r -> -((Long) r.get("retainedSize"))))
        .collect(Collectors.toList());

    // Print ASCII tree to stderr for quick visual inspection
    printRetainedBreakdownTree(rows, maxDepth);

    return rows;
  }

  private static void walkDominatedSubtree(
      io.jafar.hdump.impl.HeapDumpImpl dump,
      HeapObject obj,
      int depth,
      int maxDepth,
      Map<String, long[]> agg) {

    List<HeapObject> children = dump.getDominatedObjects(obj);
    if (children.isEmpty() || depth >= maxDepth) return;

    for (HeapObject child : children) {
      HeapClass cls = child.getHeapClass();
      String className = cls != null ? cls.getName().replace('/', '.') : "unknown";
      String key = depth + "\0" + className;
      long[] s = agg.computeIfAbsent(key, k -> new long[3]);
      s[0]++;
      s[1] += child.getShallowSize();
      s[2] += child.getRetainedSize();
      walkDominatedSubtree(dump, child, depth + 1, maxDepth, agg);
    }
  }

  /** Prints a depth-indented ASCII summary of the breakdown to stderr. */
  private static void printRetainedBreakdownTree(List<Map<String, Object>> rows, int maxDepth) {
    System.err.println();
    System.err.println("=== Retained Breakdown ===");

    // Group rows by depth for display
    int prevDepth = -1;
    for (Map<String, Object> row : rows) {
      int depth = (int) row.get("depth");
      if (depth > prevDepth + 1) continue; // skip orphaned deep entries (shouldn't happen)
      prevDepth = depth;

      String indent = "    ".repeat(depth);
      String connector = depth == 0 ? "├── " : "└── ";
      String className = (String) row.get("className");
      long count = (long) row.get("count");
      long retained = (long) row.get("retainedSize");
      long shallow = (long) row.get("shallowSize");

      System.err.printf(
          "%s%s%s  ×%,d  shallow=%s  retained=%s%n",
          indent, connector, className, count,
          formatBytes(shallow), formatBytes(retained));
    }
    System.err.println();
  }

  private static String formatBytes(long bytes) {
    if (bytes < 1024) return bytes + "B";
    if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
    if (bytes < 1024L * 1024 * 1024) return String.format("%.1fMB", bytes / (1024.0 * 1024));
    return String.format("%.2fGB", bytes / (1024.0 * 1024 * 1024));
  }

  private static List<Map<String, Object>> applyCheckLeaks(
      HeapSession session, List<Map<String, Object>> results, CheckLeaksOp op) {

    if (op.detector() != null) {
      // Special case: "help" detector shows available detectors
      if ("help".equals(op.detector())) {
        return showLeakDetectorHelp();
      }

      // Use built-in detector
      io.jafar.hdump.shell.leaks.LeakDetector detector =
          io.jafar.hdump.shell.leaks.LeakDetectorRegistry.getDetector(op.detector());

      if (detector == null) {
        // Invalid detector name
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", "Unknown detector: " + op.detector());
        error.put(
            "help",
            "Use checkLeaks(detector=\"help\") to see available detectors");
        return List.of(error);
      }

      // Ensure retained sizes are computed (leak detectors need them)
      // Use approximate retained sizes which work in streaming mode (no OOME)
      if (session.getHeapDump() instanceof io.jafar.hdump.impl.HeapDumpImpl heapDumpImpl) {
        heapDumpImpl.ensureRetainedSizesComputed();
      }

      // Run the detector
      return detector.detect(session.getHeapDump(), op.threshold(), op.minSize());

    } else if (op.filter() != null) {
      // Apply custom filter - this would reference a saved query variable
      // For now, return placeholder
      Map<String, Object> placeholder = new LinkedHashMap<>();
      placeholder.put("message", "Custom filter '" + op.filter() + "' requires variable resolution");
      placeholder.put("filter", op.filter());
      return List.of(placeholder);
    }

    return results;
  }

  /**
   * Shows help for leak detectors - lists all available detectors with descriptions.
   */
  private static List<Map<String, Object>> showLeakDetectorHelp() {
    List<Map<String, Object>> help = new ArrayList<>();

    // Add introductory message
    Map<String, Object> intro = new LinkedHashMap<>();
    intro.put("message", "Available leak detectors");
    intro.put(
        "usage",
        "checkLeaks(detector=\"<name>\", threshold=<number>, minSize=<number>)");
    intro.put("note", "Detectors automatically compute retained sizes if needed");
    help.add(intro);

    // Add each detector
    for (io.jafar.hdump.shell.leaks.LeakDetector detector :
        io.jafar.hdump.shell.leaks.LeakDetectorRegistry.getAllDetectors()) {
      Map<String, Object> detectorInfo = new LinkedHashMap<>();
      detectorInfo.put("detector", detector.getName());
      detectorInfo.put("description", detector.getDescription());

      // Add detector-specific parameter hints
      String paramHint = switch (detector.getName()) {
        case "duplicate-strings" -> "threshold = min duplicate count (default: 100)";
        case "growing-collections" -> "minSize = min collection size";
        case "threadlocal-leak", "classloader-leak", "listener-leak", "finalizer-queue" ->
            "no parameters required";
        default -> "";
      };

      if (!paramHint.isEmpty()) {
        detectorInfo.put("parameters", paramHint);
      }

      help.add(detectorInfo);
    }

    // Add examples
    Map<String, Object> examples = new LinkedHashMap<>();
    examples.put("examples", List.of(
        "checkLeaks(detector=\"duplicate-strings\")",
        "checkLeaks(detector=\"duplicate-strings\", threshold=50)",
        "checkLeaks(detector=\"growing-collections\", minSize=10000)",
        "checkLeaks(detector=\"threadlocal-leak\")"
    ));
    help.add(examples);

    return help;
  }

  private static List<Map<String, Object>> applyDominators(
      HeapSession session, HeapDump dump, List<Map<String, Object>> results, DominatorsOp op) {

    // Check if full dominator tree is computed
    if (dump instanceof io.jafar.hdump.impl.HeapDumpImpl heapDumpImpl) {
      if (!heapDumpImpl.hasFullDominatorTree()) {
        // Auto-compute dominator tree
        if (!session.promptAndComputeDominatorTree()) {
          return List.of();
        }
      }
    }

    long minRetained = op.minRetained();

    // groupBy takes priority: heap-wide retained histogram grouped by class or package
    if (op.groupBy() != null) {
      return applyDominatorsGroupBy(dump, op.groupBy(), minRetained);
    }

    String mode = op.mode();
    if (mode == null) {
      // Default: top-retainers view — input objects ranked by retained size, no expansion
      return applyDominatorsTopRetainers(results, minRetained);
    }
    return switch (mode) {
      case "objects" -> applyDominatorsObjects(dump, results, minRetained);
      case "tree" -> applyDominatorsTree(dump, results, minRetained);
      default -> throw new IllegalArgumentException("Unknown dominators mode: " + mode);
    };
  }

  /** Default mode: input objects ranked by retained size, no expansion. */
  private static List<Map<String, Object>> applyDominatorsTopRetainers(
      List<Map<String, Object>> results, long minRetained) {
    return results.stream()
        .filter(row -> retainedOf(row) >= minRetained)
        .sorted((a, b) -> Long.compare(retainedOf(b), retainedOf(a)))
        .limit(10)
        .collect(Collectors.toList());
  }

  private static long retainedOf(Map<String, Object> row) {
    Object v = row.get("retained");
    if (v == null) v = row.get("retainedSize");
    return v instanceof Number ? ((Number) v).longValue() : 0L;
  }

  private static List<Map<String, Object>> applyDominatorsObjects(
      HeapDump dump, List<Map<String, Object>> results, long minRetained) {
    List<Map<String, Object>> dominated = new ArrayList<>();

    for (Map<String, Object> row : results) {
      Object idObj = row.get("id");
      if (idObj == null) continue;

      long id = idObj instanceof Number ? ((Number) idObj).longValue() : Long.parseLong(idObj.toString());
      HeapObject dominator = dump.getObjectById(id).orElse(null);
      if (dominator == null) continue;

      if (dump instanceof io.jafar.hdump.impl.HeapDumpImpl heapDumpImpl) {
        List<HeapObject> dominatedObjects = heapDumpImpl.getDominatedObjects(dominator);

        for (HeapObject obj : dominatedObjects) {
          if (obj.getRetainedSize() < minRetained) continue;
          Map<String, Object> result = objectToMap(obj);
          result.put("dominator", dominator.getId());
          result.put(
              "dominatorClass",
              dominator.getHeapClass() != null ? dominator.getHeapClass().getName() : "unknown");
          dominated.add(result);
        }
      }
    }

    dominated.sort((a, b) -> Long.compare(retainedOf(b), retainedOf(a)));
    return dominated;
  }

  private static List<Map<String, Object>> applyDominatorsGroupBy(
      HeapDump dump, String groupBy, long minRetained) {
    // Produce a retained-size histogram over the entire heap grouped by class or package.
    // The input stream is ignored — it is always capped by the streaming limit and would
    // produce misleading or empty results. The useful answer is always heap-wide.
    boolean byPackage = "package".equalsIgnoreCase(groupBy);
    String keyLabel = byPackage ? "package" : "className";
    Map<String, ClassAggregation> groups = new LinkedHashMap<>();

    dump.getObjects().forEach(obj -> {
      String className =
          obj.getHeapClass() != null
              ? obj.getHeapClass().getName().replace('/', '.')
              : "unknown";
      String key;
      if (byPackage) {
        int lastDot = className.lastIndexOf('.');
        key = lastDot > 0 ? className.substring(0, lastDot) : "(default package)";
      } else {
        key = className;
      }
      ClassAggregation agg = groups.computeIfAbsent(key, k -> new ClassAggregation(k));
      agg.count++;
      agg.shallowSize += obj.getShallowSize();
      agg.retainedSize += obj.getRetainedSize();
    });

    return groups.values().stream()
        .filter(agg -> agg.retainedSize >= minRetained)
        .sorted((a, b) -> Long.compare(b.retainedSize, a.retainedSize))
        .map(agg -> {
          Map<String, Object> result = new LinkedHashMap<>();
          result.put(keyLabel, agg.className);
          result.put("count", agg.count);
          result.put("shallowSize", agg.shallowSize);
          result.put("retainedSize", agg.retainedSize);
          return result;
        })
        .collect(Collectors.toList());
  }

  private static List<Map<String, Object>> applyDominatorsTree(
      HeapDump dump, List<Map<String, Object>> results, long minRetained) {
    List<Map<String, Object>> treeResults = new ArrayList<>();

    // Pre-filter and sort by retained size so only significant objects get expanded.
    List<Map<String, Object>> filtered = results.stream()
        .filter(row -> retainedOf(row) >= minRetained)
        .sorted((a, b) -> Long.compare(retainedOf(b), retainedOf(a)))
        .collect(Collectors.toList());

    for (Map<String, Object> row : filtered) {
      Object idObj = row.get("id");
      if (idObj == null) continue;

      long id =
          idObj instanceof Number
              ? ((Number) idObj).longValue()
              : Long.parseLong(idObj.toString());
      HeapObject dominator = dump.getObjectById(id).orElse(null);
      if (dominator == null) continue;

      if (dump instanceof io.jafar.hdump.impl.HeapDumpImpl heapDumpImpl) {
        DominatorNode root = buildRecursiveDominatorTree(heapDumpImpl, dominator, 0, 3);

        StringBuilder tree = new StringBuilder();
        String dominatorClassName =
            dominator.getHeapClass() != null
                ? dominator.getHeapClass().getName().replace('/', '.')
                : "unknown";
        tree.append(
            String.format(
                "%s (retained: %,d bytes)\n", dominatorClassName, dominator.getRetainedSize()));
        formatDominatorTree(root, tree, "", true);

        int totalDominated = countDominatedObjects(root);

        System.err.println();
        System.err.println("=== Dominator Tree ===");
        System.err.print(tree.toString());
        System.err.println();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dominator", dominator.getId());
        result.put("dominatorClass", dominatorClassName);
        result.put("dominatedCount", totalDominated);
        result.put("retainedSize", dominator.getRetainedSize());
        treeResults.add(result);
      }
    }

    return treeResults;
  }

  private static DominatorNode buildRecursiveDominatorTree(
      io.jafar.hdump.impl.HeapDumpImpl dump, HeapObject root, int depth, int maxDepth) {
    // Stop recursion at max depth to avoid stack overflow
    if (depth >= maxDepth) {
      return new DominatorNode("...");
    }

    List<HeapObject> children = dump.getDominatedObjects(root);
    if (children.isEmpty()) {
      return new DominatorNode("(no children)");
    }

    // Group immediate children by class to avoid per-object recursion
    Map<String, ClassNode> classToDominatorNode = new LinkedHashMap<>();

    for (HeapObject child : children) {
      String className =
          child.getHeapClass() != null
              ? child.getHeapClass().getName().replace('/', '.')
              : "unknown";

      ClassNode classNode =
          classToDominatorNode.computeIfAbsent(className, k -> new ClassNode(k, child));
      classNode.count++;
      classNode.retainedSize += child.getRetainedSize();
    }

    // Convert to DominatorNode with limited recursion
    // Only recurse for top 5 classes to prevent OOM/stack overflow
    DominatorNode rootNode =
        new DominatorNode(
            root.getHeapClass() != null
                ? root.getHeapClass().getName().replace('/', '.')
                : "unknown");

    List<ClassNode> sortedClasses = new ArrayList<>(classToDominatorNode.values());
    sortedClasses.sort((a, b) -> Long.compare(b.retainedSize, a.retainedSize));

    int shown = 0;
    for (ClassNode classNode : sortedClasses) {
      DominatorNode node = new DominatorNode(classNode.className);
      node.count = classNode.count;
      node.retainedSize = classNode.retainedSize;

      // Only recurse for top 5 classes and not beyond depth limit
      if (shown < 5 && depth + 1 < maxDepth && classNode.sampleObject != null) {
        DominatorNode childTree =
            buildRecursiveDominatorTree(dump, classNode.sampleObject, depth + 1, maxDepth);
        if (childTree != null && !childTree.children.isEmpty()) {
          node.children.addAll(childTree.children);
        }
      }

      rootNode.children.add(node);
      shown++;
      if (shown >= 10) {
        // Show at most 10 classes at this level
        if (sortedClasses.size() > 10) {
          DominatorNode more = new DominatorNode("... (" + (sortedClasses.size() - 10) + " more classes)");
          rootNode.children.add(more);
        }
        break;
      }
    }

    return rootNode;
  }

  private static class ClassNode {
    final String className;
    final HeapObject sampleObject; // One object from this class for recursion
    int count = 0;
    long retainedSize = 0;

    ClassNode(String className, HeapObject sampleObject) {
      this.className = className;
      this.sampleObject = sampleObject;
    }
  }

  private static int countDominatedObjects(DominatorNode node) {
    int count = node.count;
    for (DominatorNode child : node.children) {
      count += countDominatedObjects(child);
    }
    return count;
  }

  private static void formatDominatorTree(
      DominatorNode node, StringBuilder sb, String prefix, boolean isLast) {
    if (node.children.isEmpty()) return;

    for (int i = 0; i < node.children.size(); i++) {
      DominatorNode child = node.children.get(i);
      boolean last = i == node.children.size() - 1;

      sb.append(prefix);
      sb.append(last ? "└── " : "├── ");
      sb.append(
          String.format(
              "%s (%,d objects, retained: %,d bytes)\n",
              child.className, child.count, child.retainedSize));

      String childPrefix = prefix + (last ? "    " : "│   ");
      formatDominatorTree(child, sb, childPrefix, last);
    }
  }

  private static class ClassAggregation {
    final String className;
    int count = 0;
    long shallowSize = 0;
    long retainedSize = 0;

    ClassAggregation(String className) {
      this.className = className;
    }
  }

  private static class DominatorNode {
    final String className;
    int count = 0;
    long retainedSize = 0;
    final List<DominatorNode> children = new ArrayList<>();

    DominatorNode(String className) {
      this.className = className;
    }
  }
}

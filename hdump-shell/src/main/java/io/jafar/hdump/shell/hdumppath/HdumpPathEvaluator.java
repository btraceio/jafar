package io.jafar.hdump.shell.hdumppath;

import io.jafar.hdump.api.*;
import io.jafar.hdump.shell.HeapSession;
import io.jafar.hdump.shell.hdumppath.HdumpPath.*;
import io.jafar.hdump.util.ClassNameUtil;
import io.jafar.shell.core.AllocationAggregator;
import io.jafar.shell.core.CrossSessionContext;
import io.jafar.shell.core.QueryEvaluator;
import io.jafar.shell.core.RowSorter;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.SessionResolver;
import io.jafar.shell.core.expr.BinaryExpr;
import io.jafar.shell.core.expr.FieldRef;
import io.jafar.shell.core.expr.NumberLiteral;
import io.jafar.shell.core.expr.ValueExpr;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evaluates HdumpPath queries against a HeapSession.
 *
 * <p>The evaluator converts queries into heap dump operations, applying filters and pipeline
 * transformations to produce result sets.
 */
public final class HdumpPathEvaluator {

  private static final Logger LOG = LoggerFactory.getLogger(HdumpPathEvaluator.class);

  /**
   * Object count above which {@code objects} queries stream instead of materializing every row.
   * Package-private and mutable only so tests can drive the streaming path with a small heap.
   */
  static long streamingThreshold = 5_000_000;

  /** Maps short alias field names to their canonical counterparts. */
  private static final Map<String, String> FIELD_ALIASES =
      Map.of(
          "class", ObjectFields.CLASS_NAME,
          "shallow", ObjectFields.SHALLOW_SIZE,
          "retained", ObjectFields.RETAINED_SIZE);

  /**
   * A LinkedHashMap that resolves field aliases on {@code get} and {@code containsKey}. Alias keys
   * are never stored; lookups for alias names transparently delegate to canonical names.
   */
  private static final class AliasMap extends LinkedHashMap<String, Object> {
    /**
     * Writing to an alias ({@code class}) whose canonical column ({@code className}) is present
     * writes the canonical column, so an alias never becomes a second, diverging column ({@code
     * uppercase(class)} used to add {@code class=COM.EXAMPLE.FOO} next to an unchanged {@code
     * className}). ({@code putAll} does not call this; the rows it copies hold no alias keys.)
     */
    @Override
    public Object put(String key, Object value) {
      String canonical = FIELD_ALIASES.get(key);
      if (canonical != null && !super.containsKey(key) && super.containsKey(canonical)) {
        return super.put(canonical, value);
      }
      return super.put(key, value);
    }

    @Override
    public Object get(Object key) {
      Object v = super.get(key);
      if (v == null && key instanceof String s) {
        String canonical = FIELD_ALIASES.get(s);
        if (canonical != null) v = super.get(canonical);
      }
      return v;
    }

    @Override
    public boolean containsKey(Object key) {
      if (super.containsKey(key)) return true;
      if (key instanceof String s) {
        String canonical = FIELD_ALIASES.get(s);
        return canonical != null && super.containsKey(canonical);
      }
      return false;
    }
  }

  /**
   * Fails loudly when {@code field} is absent from every input row. An operator over a column that
   * does not exist would otherwise return an empty aggregate or an unsorted list that reads like a
   * measurement (issue #226: {@code groupBy(class) | stats(shallow)} reported {@code count: 0}
   * because group rows carry only the key and the aggregate).
   */
  private static void requireField(List<Map<String, Object>> rows, String field, String opName) {
    if (rows.isEmpty()) {
      return;
    }
    for (Map<String, Object> row : rows) {
      if (getField(row, field) != null) {
        return;
      }
    }
    throw new IllegalArgumentException(
        opName
            + ": field '"
            + field
            + "' is not present in its input rows (available: "
            + String.join(", ", rows.get(0).keySet())
            + ")");
  }

  /**
   * The column a {@code groupBy} aggregates when it has no {@code value=}: {@code shallowSize}
   * where the rows carry it (objects), otherwise the first numeric column that is not an
   * identifier. Never {@code id}: summing object ids is meaningless, and it is what {@code
   * groupBy(class, agg=sum)} used to return.
   */
  static String defaultAggregationField(Map<String, Object> row) {
    if (row.get(ObjectFields.SHALLOW_SIZE) instanceof Number) {
      return ObjectFields.SHALLOW_SIZE;
    }
    for (Map.Entry<String, Object> e : row.entrySet()) {
      String k = e.getKey();
      if (e.getValue() instanceof Number
          && !ObjectFields.ID.equals(k)
          && !GcRootFields.OBJECT_ID.equals(k)) {
        return k;
      }
    }
    return null;
  }

  /**
   * Builds the single {@code stats(field)} result row. Shared by the in-memory and the streaming
   * paths so both name the columns the same way. With no numeric value, {@code min}/{@code max} are
   * {@code null} rather than {@code ±Infinity}.
   */
  private static Map<String, Object> statsRow(
      String field, long count, double sum, double min, double max) {
    Map<String, Object> row = new LinkedHashMap<>();
    // For stats, use field name as prefix if it's a memory field
    boolean isMemory = isMemoryFieldName(field);
    row.put("count", count);
    row.put(isMemory ? field + "_sum" : "sum", sum);
    row.put(isMemory ? field + "_min" : "min", count > 0 ? min : null);
    row.put(isMemory ? field + "_max" : "max", count > 0 ? max : null);
    row.put(isMemory ? field + "_avg" : "avg", count > 0 ? sum / count : 0.0);
    return row;
  }

  /**
   * Copies a row for an operator that adds columns, keeping the field aliases ({@code class},
   * {@code shallow}, {@code retained}) resolvable for {@code select()} and anything else that reads
   * the row directly. A plain {@code LinkedHashMap} copy dropped them.
   */
  private static Map<String, Object> aliasCopy(Map<String, Object> row) {
    Map<String, Object> copy = new AliasMap();
    copy.putAll(row);
    return copy;
  }

  /** Gets a value from a map, resolving field aliases. */
  static Object getField(Map<String, Object> map, String field) {
    Object v = map.get(field);
    if (v == null) {
      String canonical = FIELD_ALIASES.get(field);
      if (canonical != null) v = map.get(canonical);
    }
    return v;
  }

  private HdumpPathEvaluator() {}

  /**
   * Evaluates a query with access to other sessions for cross-session operators (e.g. join).
   *
   * @param session the heap session
   * @param query the parsed query
   * @param resolver resolves session references by ID or alias, may be null
   * @return list of result maps
   */
  public static List<Map<String, Object>> evaluate(
      HeapSession session, Query query, SessionResolver resolver) {
    return evaluateInternal(session, query, resolver);
  }

  /**
   * Evaluates a query against a heap session (no cross-session support).
   *
   * @param session the heap session
   * @param query the parsed query
   * @return list of result maps
   */
  public static List<Map<String, Object>> evaluate(HeapSession session, Query query) {
    return evaluateInternal(session, query, null);
  }

  private static List<Map<String, Object>> evaluateInternal(
      HeapSession session, Query query, SessionResolver resolver) {
    HeapDump dump = session.getHeapDump();

    // If the query references retained size, ensure it is computed before evaluation starts.
    // This prevents per-object lazy computation mid-stream (which would be a side-effect of
    // objectToMap() after the switch to getRetainedSizeIfAvailable()).
    if (query.root() == Root.OBJECTS
        && !dump.hasDominators()
        && session != null
        && queryNeedsRetainedSize(query)) {
      session.computeApproximateRetainedSizes();
    }

    // Optimization: For large heaps with object queries, use streaming to avoid OOM
    // This handles all cases: unfiltered, type patterns, predicates
    if (query.root() == Root.OBJECTS && dump.getObjectCount() > streamingThreshold) {

      // If no pipeline, default to top(100) to prevent OOME
      if (query.pipeline().isEmpty()) {
        LOG.warn(
            "Large heap ({} objects): no pipeline, capping at 100. Use '| top(N)' to specify limit.",
            dump.getObjectCount());

        TopOp defaultTop = new TopOp(100, null, false);
        return evaluateObjectsStreamingWithFilters(dump, session, query, List.of(), defaultTop);
      }

      // Peel any leading FilterOps into the query predicates so they are applied during streaming
      List<Predicate> mergedPredicates = new ArrayList<>(query.predicates());
      int firstNonFilter = 0;
      List<PipelineOp> pipeline = query.pipeline();
      while (firstNonFilter < pipeline.size()
          && pipeline.get(firstNonFilter) instanceof FilterOp f) {
        mergedPredicates.add(f.predicate());
        firstNonFilter++;
      }
      Query streamingQuery =
          firstNonFilter > 0
              ? new Query(
                  query.root(),
                  query.typePattern(),
                  query.instanceof_(),
                  mergedPredicates,
                  pipeline.subList(firstNonFilter, pipeline.size()),
                  query.rootParam())
              : query;
      List<PipelineOp> streamingPipeline = streamingQuery.pipeline();

      if (streamingPipeline.isEmpty()) {
        // Only filters remain — need a limit
        LOG.warn(
            "Large heap ({} objects): only filters remain, capping at 100. Use '| top(N)' to specify limit.",
            dump.getObjectCount());
        TopOp defaultTop = new TopOp(100, null, false);
        return evaluateObjectsStreamingWithFilters(
            dump, session, streamingQuery, List.of(), defaultTop);
      }

      // Streamed head: leading row-local ops (waste(), cacheStats(), filter(): each row on its
      // own) run per object, then one op that can consume a stream. Everything after it runs on
      // that op's (small) result, through the resolver so join() works here as elsewhere.
      int rowOpCount = 0;
      while (rowOpCount < streamingPipeline.size()
          && isRowLocal(streamingPipeline.get(rowOpCount))) {
        rowOpCount++;
      }
      List<PipelineOp> rowOps = streamingPipeline.subList(0, rowOpCount);
      PipelineOp head =
          rowOpCount < streamingPipeline.size() ? streamingPipeline.get(rowOpCount) : null;
      List<PipelineOp> rest =
          head == null
              ? List.of()
              : streamingPipeline.subList(rowOpCount + 1, streamingPipeline.size());

      // groupBy | top(N) used a bounded accumulator that trimmed groups every 10K rows (approximate
      // totals for evicted groups) and ranked by the aggregate before top()'s field: it now
      // accumulates every group (one per key, not per object) and top() runs on the result.
      List<Map<String, Object>> results;
      if (isStreamable(head)) {
        results = evaluateObjectsStreamingWithFilters(dump, session, streamingQuery, rowOps, head);
      } else {
        // Nothing here consumes a stream: run every op on 100 objects of the stream
        LOG.warn(
            "Large heap ({} objects): non-aggregating pipeline, capping at 100. Use '| top(N)' as"
                + " first operation.",
            dump.getObjectCount());
        results =
            evaluateObjectsStreamingWithFilters(
                dump, session, streamingQuery, List.of(), new TopOp(100, null, false));
        rest = streamingPipeline;
      }
      for (PipelineOp op : rest) {
        results = applyPipelineOp(session, results, op, query, resolver);
      }
      return results;
    }

    // Standard path: materialize all results first
    List<Map<String, Object>> results =
        switch (query.root()) {
          case OBJECTS -> evaluateObjects(dump, query);
          case CLASSES -> evaluateClasses(dump, query);
          case GCROOTS -> evaluateGcRoots(dump, query);
          case CLUSTERS -> evaluateClusters(session, query);
          case DUPLICATES -> evaluateDuplicates(session, query);
          case AGES -> evaluateAges(session, query);
        };

    // Apply pipeline operations
    for (PipelineOp op : query.pipeline()) {
      results = applyPipelineOp(session, results, op, query, resolver);
    }

    return results;
  }

  /** Streaming evaluation from a filtered stream (supports type patterns and predicates). */
  /**
   * The {@code top(n, field)} rows of a stream, best first, keeping at most {@code n + 1} rows. The
   * same answer as {@link #applyTop}: rows without a value are worst in either direction, ties keep
   * their stream order (as a stable sort followed by {@code limit} would), and a field no row
   * carries fails with the same message. The heap is ordered worst-first so that {@code poll()}
   * evicts the worst row; it used to be best-first, which evicted the best row and returned the n
   * smallest values, ascending. Without an order field this is the first {@code n} rows.
   */
  static List<Map<String, Object>> selectTop(Stream<Map<String, Object>> rows, TopOp topOp) {
    if (topOp.orderBy() == null) {
      return rows.limit(topOp.n()).collect(Collectors.toList());
    }
    record Ranked(Map<String, Object> row, long seq) {}
    Comparator<Map<String, Object>> byValue =
        nullsLastBy(m -> getField(m, topOp.orderBy()), topOp.descending());
    Comparator<Ranked> bestFirst =
        Comparator.comparing(Ranked::row, byValue).thenComparingLong(Ranked::seq);
    java.util.PriorityQueue<Ranked> heap =
        new java.util.PriorityQueue<>(topOp.n() + 1, bestFirst.reversed());
    long[] seq = {0};
    Map<String, Object>[] firstRow = new Map[1];
    boolean[] fieldSeen = {false};
    rows.forEach(
        row -> {
          if (firstRow[0] == null) {
            firstRow[0] = row;
          }
          if (!fieldSeen[0] && getField(row, topOp.orderBy()) != null) {
            fieldSeen[0] = true;
          }
          heap.add(new Ranked(row, seq[0]++));
          if (heap.size() > topOp.n()) {
            heap.poll(); // evict the worst
          }
        });
    if (firstRow[0] != null && !fieldSeen[0]) {
      requireField(List.of(firstRow[0]), topOp.orderBy(), "top");
    }
    List<Ranked> kept = new ArrayList<>(heap);
    kept.sort(bestFirst);
    List<Map<String, Object>> results = new ArrayList<>(kept.size());
    for (Ranked r : kept) {
      results.add(r.row());
    }
    return results;
  }

  /**
   * Streaming evaluation from a filtered stream for groupBy operations. Accumulates groups as we
   * stream to avoid materializing all objects.
   */
  private static List<Map<String, Object>> evaluateObjectsStreamingGroupByFromStream(
      Stream<Map<String, Object>> rows, long totalObjects, GroupByOp op) {
    Map<List<Object>, GroupAccumulator> groups = new LinkedHashMap<>();
    long[] counter = {0};

    rows.forEach(
        map -> {

          // Extract group key
          List<Object> key = new ArrayList<>();
          for (String field : op.groupFields()) {
            key.add(getField(map, field));
          }

          // Get or create accumulator for this group
          GroupAccumulator acc = groups.computeIfAbsent(key, k -> new GroupAccumulator(k, op));
          acc.accumulate(map, op);

          counter[0]++;
        });

    // Convert groups to result maps
    List<Map<String, Object>> results =
        groups.values().stream().map(acc -> acc.toResultMap(op)).collect(Collectors.toList());

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

      results.sort(nullsLastBy(m -> m.get(sortColumn), !op.ascending()));
    }

    return results;
  }

  /** Streaming evaluation from a filtered stream for count operations. */
  private static List<Map<String, Object>> evaluateObjectsStreamingCountFromStream(
      Stream<HeapObject> stream, long totalObjects) {
    long[] counter = {0};

    stream.forEach(
        obj -> {
          counter[0]++;
        });

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("count", counter[0]);
    return List.of(result);
  }

  /** Streaming evaluation from a filtered stream for sum operations. */
  private static List<Map<String, Object>> evaluateObjectsStreamingSumFromStream(
      Stream<Map<String, Object>> rows, long totalObjects, SumOp op) {
    double[] sum = {0.0};
    boolean[] fieldSeen = {false};
    Map<String, Object>[] firstRow = new Map[1];

    rows.forEach(
        map -> {
          if (firstRow[0] == null) {
            firstRow[0] = map;
          }
          Object value = getField(map, op.field());
          if (value != null) {
            fieldSeen[0] = true;
          }
          if (value instanceof Number num) {
            sum[0] += num.doubleValue();
          }
        });

    // Same check and column name as applySum
    if (firstRow[0] != null && !fieldSeen[0]) {
      requireField(List.of(firstRow[0]), op.field(), "sum");
    }
    return List.of(sumRow(op.field(), sum[0]));
  }

  /** Streaming evaluation from a filtered stream for stats operations. */
  private static List<Map<String, Object>> evaluateObjectsStreamingStatsFromStream(
      Stream<Map<String, Object>> rows, long totalObjects, StatsOp op) {
    double[] sum = {0.0};
    double[] min = {Double.POSITIVE_INFINITY};
    double[] max = {Double.NEGATIVE_INFINITY};
    long[] count = {0};
    Map<String, Object>[] firstRow = new Map[1];

    rows.forEach(
        map -> {
          if (firstRow[0] == null) {
            firstRow[0] = map;
          }
          Object value = getField(map, op.field());
          if (value instanceof Number num) {
            double d = num.doubleValue();
            sum[0] += d;
            min[0] = Math.min(min[0], d);
            max[0] = Math.max(max[0], d);
            count[0]++;
          }
        });

    if (count[0] == 0 && firstRow[0] != null) {
      requireField(List.of(firstRow[0]), op.field(), "stats");
    }
    return List.of(statsRow(op.field(), count[0], sum[0], min[0], max[0]));
  }

  /** Helper class to accumulate aggregation results for a group. */
  private static class GroupAccumulator {
    private final List<Object> key;
    private long count = 0;
    private long numericCount = 0;
    private double sum = 0.0;
    private double min = Double.POSITIVE_INFINITY;
    private double max = Double.NEGATIVE_INFINITY;

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
        // Same default as the in-memory path: shallowSize, never an id
        String field = defaultAggregationField(row);
        value = field != null ? row.get(field) : null;
      } else {
        value = null;
      }

      if (value instanceof Number num) {
        double d = num.doubleValue();
        sum += d;
        min = Math.min(min, d);
        max = Math.max(max, d);
        numericCount++;
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
        case AVG -> result.put(aggColumnName, numericCount > 0 ? sum / numericCount : null);
        case MIN -> result.put(aggColumnName, numericCount > 0 ? min : null);
        case MAX -> result.put(aggColumnName, numericCount > 0 ? max : null);
      }

      return result;
    }
  }

  /**
   * Streaming evaluation with filter support for large heaps. Applies type pattern and predicate
   * filters during streaming, then delegates to specialized streaming method based on operation
   * type.
   */
  /**
   * Operators whose output for a row depends on that row alone, so they can run while streaming.
   */
  private static boolean isRowLocal(PipelineOp op) {
    return op instanceof WasteOp || op instanceof CacheStatsOp || op instanceof FilterOp;
  }

  /** Operators {@link #evaluateObjectsStreamingWithFilters} can compute from a stream of rows. */
  private static boolean isStreamable(PipelineOp op) {
    return op instanceof TopOp
        || op instanceof GroupByOp
        || op instanceof CountOp
        || op instanceof SumOp
        || op instanceof StatsOp;
  }

  /**
   * Streams the objects the query's type pattern and predicates select, applies {@code rowOps} (see
   * {@link #isRowLocal}) to each row, and computes {@code head} (see {@link #isStreamable}) from
   * the stream without materializing it.
   */
  private static List<Map<String, Object>> evaluateObjectsStreamingWithFilters(
      HeapDump dump, HeapSession session, Query query, List<PipelineOp> rowOps, PipelineOp head) {
    // Build filtered stream
    Stream<HeapObject> stream;
    String filterDesc = "";

    // Try to use class-instances index for type filtering
    if (query.typePattern() != null && dump.hasClassInstancesIndex()) {
      // FAST PATH: Use class-instances index to directly enumerate matching instances
      String pattern = normalizeClassName(query.typePattern());
      filterDesc = " matching " + query.typePattern() + " (class-instances index)";

      Set<String> matchingClassNames = findMatchingClasses(dump, pattern, query.instanceof_());

      // For each matching class, get instances directly from index
      stream =
          matchingClassNames.stream()
              .map(name -> dump.getClassByName(name))
              .filter(Optional::isPresent)
              .map(Optional::get)
              .flatMap(cls -> dump.getObjectsOfClassFast(cls));

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

    if (head instanceof CountOp && rowOps.isEmpty()) {
      return evaluateObjectsStreamingCountFromStream(stream, dump.getObjectCount());
    }
    Stream<Map<String, Object>> rows = stream.map(HdumpPathEvaluator::objectToMap);
    if (!rowOps.isEmpty()) {
      rows =
          rows.flatMap(
              row -> {
                List<Map<String, Object>> out = List.of(row);
                for (PipelineOp op : rowOps) {
                  out = applyPipelineOp(session, out, op);
                }
                return out.stream();
              });
    }
    return switch (head) {
      case TopOp topOp -> selectTop(rows, topOp);
      case GroupByOp groupByOp ->
          evaluateObjectsStreamingGroupByFromStream(rows, dump.getObjectCount(), groupByOp);
      case CountOp c -> List.of(new LinkedHashMap<>(Map.of("count", rows.count())));
      case SumOp sumOp -> evaluateObjectsStreamingSumFromStream(rows, dump.getObjectCount(), sumOp);
      case StatsOp statsOp ->
          evaluateObjectsStreamingStatsFromStream(rows, dump.getObjectCount(), statsOp);
      default ->
          throw new IllegalStateException(
              "Unsupported streaming operation: " + head.getClass().getSimpleName());
    };
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
      LOG.warn(
          "Loading all {} objects may cause OOM. Consider filtering by type or using 'classes'.",
          totalObjects);
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

  private static List<Map<String, Object>> evaluateClusters(HeapSession session, Query query) {
    ClusterDetector.Result clusterResult = session.getOrComputeClusters();
    List<Map<String, Object>> results = new ArrayList<>(clusterResult.rows());

    if (!query.predicates().isEmpty()) {
      results =
          results.stream()
              .filter(map -> matchesAllPredicates(map, query.predicates()))
              .collect(Collectors.toList());
    }

    return results;
  }

  private static List<Map<String, Object>> evaluateDuplicates(HeapSession session, Query query) {
    int depth = query.rootParam() > 0 ? query.rootParam() : 3;
    SubgraphFingerprinter.Result result = session.getOrComputeDuplicates(depth);
    List<Map<String, Object>> rows = new ArrayList<>(result.rows());
    if (!query.predicates().isEmpty()) {
      rows =
          rows.stream()
              .filter(m -> matchesAllPredicates(m, query.predicates()))
              .collect(Collectors.toList());
    }
    return rows;
  }

  private static List<Map<String, Object>> applyWhatIf(
      HeapSession session, List<Map<String, Object>> results) {
    HeapDump dump = session.getHeapDump();
    if (!dump.hasDominators()) {
      session.computeApproximateRetainedSizes();
    }

    int targetCount = results.size();
    long freedBytes = 0;
    int freedObjects = 0;

    if (session.hasFullDominatorTree()) {
      Set<Long> visited = new java.util.HashSet<>();
      Deque<HeapObject> queue = new java.util.ArrayDeque<>();

      for (Map<String, Object> row : results) {
        long id = rowObjectId(row);
        if (id < 0) continue;
        HeapObject obj = dump.getObjectById(id).orElse(null);
        if (obj == null || !visited.add(id)) continue;
        freedBytes += Math.max(0, obj.getRetainedSizeIfAvailable());
        queue.add(obj);
      }
      while (!queue.isEmpty()) {
        HeapObject cur = queue.poll();
        freedObjects++;
        for (HeapObject child : dump.getDominatedObjects(cur)) {
          if (visited.add(child.getId())) queue.add(child);
        }
      }
    } else {
      for (Map<String, Object> row : results) {
        // Use the retained size already in the row if present, else look up by id
        Object retained = row.get(ObjectFields.RETAINED_SIZE);
        if (retained instanceof Number n) {
          freedBytes += Math.max(0, n.longValue());
        } else {
          long id = rowObjectId(row);
          if (id >= 0) {
            HeapObject obj = dump.getObjectById(id).orElse(null);
            if (obj != null) freedBytes += Math.max(0, obj.getRetainedSizeIfAvailable());
          }
        }
      }
      freedObjects = targetCount;
    }

    long totalHeapSize = dump.getTotalHeapSize();
    double freedPct =
        totalHeapSize > 0 ? Math.round((freedBytes * 1000.0 / totalHeapSize)) / 10.0 : 0.0;

    Map<String, Object> row = new LinkedHashMap<>();
    row.put(HdumpPath.WhatIfFields.ACTION, "remove");
    row.put(HdumpPath.WhatIfFields.TARGET_COUNT, targetCount);
    row.put(HdumpPath.WhatIfFields.FREED_BYTES, freedBytes);
    row.put(HdumpPath.WhatIfFields.FREED_OBJECTS, freedObjects);
    row.put(HdumpPath.WhatIfFields.FREED_PCT, freedPct);
    row.put(HdumpPath.WhatIfFields.REMAINING_RETAINED, totalHeapSize - freedBytes);
    return List.of(row);
  }

  /** Returns the object ID from a row, checking both {@code id} and {@code objectId} fields. */
  private static long rowObjectId(Map<String, Object> row) {
    Object v = row.get(ObjectFields.ID);
    if (v == null) v = row.get(GcRootFields.OBJECT_ID);
    return v instanceof Number n ? n.longValue() : -1L;
  }

  private static List<Map<String, Object>> evaluateAges(HeapSession session, Query query) {
    // Delegate object filtering to evaluateObjects, then enrich with age data
    Query objectsQuery =
        new Query(
            Root.OBJECTS,
            query.typePattern(),
            query.instanceof_(),
            query.predicates(),
            List.of(),
            0);
    List<Map<String, Object>> objectRows = evaluateObjects(session.getHeapDump(), objectsQuery);
    return applyEstimateAge(session, objectRows);
  }

  private static List<Map<String, Object>> applyEstimateAge(
      HeapSession session, List<Map<String, Object>> results) {
    ObjectAgeEstimator.Result ages = session.getOrComputeAgeEstimation();
    List<Map<String, Object>> enriched = new ArrayList<>(results.size());
    for (Map<String, Object> row : results) {
      Map<String, Object> out = new AliasMap();
      out.putAll(row);
      Object idObj = row.get(ObjectFields.ID);
      if (idObj instanceof Number n) {
        ObjectAgeEstimator.AgeData data = ages.getAgeData(n.longValue());
        out.put(HdumpPath.AgeFields.ESTIMATED_AGE, data.score());
        out.put(HdumpPath.AgeFields.AGE_BUCKET, data.bucket());
        out.put(HdumpPath.AgeFields.AGE_SIGNALS, data.signals());
      }
      enriched.add(out);
    }
    return enriched;
  }

  // === Object to Map conversions ===

  public static Map<String, Object> objectToRow(HeapObject obj) {
    return objectToMap(obj);
  }

  private static Map<String, Object> objectToMap(HeapObject obj) {
    Map<String, Object> map = new AliasMap();
    HeapClass cls = obj.getHeapClass();

    String className = cls != null ? ClassNameUtil.toHumanReadable(cls.getName()) : "unknown";
    map.put(ObjectFields.ID, obj.getId());
    map.put(ObjectFields.CLASS_NAME, className);
    map.put(ObjectFields.SHALLOW_SIZE, obj.getShallowSize());

    long retained = obj.getRetainedSizeIfAvailable(); // never triggers computation
    if (retained >= 0) {
      map.put(ObjectFields.RETAINED_SIZE, retained);
    }

    if (obj.isArray()) {
      map.put(ObjectFields.ARRAY_LENGTH, obj.getArrayLength());
    }

    // Add string value for String objects
    if ("java.lang.String".equals(className)) {
      String value = obj.getStringValue();
      if (value != null) {
        map.put(ObjectFields.STRING_VALUE, value);
      }
    }

    // Include instance fields for TUI detail pane rendering
    if (!obj.isArray()) {
      Map<String, Object> fieldValues = obj.getFieldValues();
      if (fieldValues != null && !fieldValues.isEmpty()) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : fieldValues.entrySet()) {
          fields.put(e.getKey(), fieldValueForDisplay(e.getValue()));
        }
        map.put("fields", fields);
      }
    } else if (obj.getArrayLength() > 0 && obj.getArrayLength() <= 100) {
      // For small arrays, include elements
      Object[] elements = obj.getArrayElements();
      if (elements != null) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < elements.length; i++) {
          fields.put("[" + i + "]", fieldValueForDisplay(elements[i]));
        }
        map.put("fields", fields);
      }
    }

    return map;
  }

  private static Object fieldValueForDisplay(Object val) {
    if (val instanceof HeapObject ref) {
      HeapClass refCls = ref.getHeapClass();
      String typeName =
          refCls != null ? ClassNameUtil.toHumanReadable(refCls.getName()) : "unknown";
      // For strings, show the value inline
      if ("java.lang.String".equals(typeName)) {
        String sv = ref.getStringValue();
        if (sv != null) {
          if (sv.length() > 80) sv = sv.substring(0, 77) + "...";
          return "\"" + sv + "\"";
        }
      }
      return typeName + "@" + Long.toHexString(ref.getId());
    }
    return val;
  }

  private static Map<String, Object> classToMap(HeapClass cls) {
    Map<String, Object> map = new LinkedHashMap<>();
    String formattedName = ClassNameUtil.toHumanReadable(cls.getName());
    int lastDot = formattedName.lastIndexOf('.');
    String simpleName = lastDot >= 0 ? formattedName.substring(lastDot + 1) : formattedName;

    map.put(ClassFields.ID, cls.getId());
    map.put(ClassFields.NAME, formattedName);
    map.put(ClassFields.SIMPLE_NAME, simpleName);
    map.put(ClassFields.INSTANCE_COUNT, cls.getInstanceCount());
    map.put(ClassFields.INSTANCE_SIZE, cls.getInstanceSize());

    HeapClass superClass = cls.getSuperClass();
    if (superClass != null) {
      map.put(ClassFields.SUPER_CLASS, ClassNameUtil.toHumanReadable(superClass.getName()));
    }

    map.put(ClassFields.IS_ARRAY, cls.isArray());
    return map;
  }

  private static Map<String, Object> gcRootToMap(GcRoot root) {
    Map<String, Object> map = new AliasMap();
    map.put(GcRootFields.TYPE, root.getType().name());
    map.put(GcRootFields.OBJECT_ID, root.getObjectId());

    HeapObject obj = root.getObject();
    if (obj != null) {
      HeapClass cls = obj.getHeapClass();
      map.put(
          GcRootFields.OBJECT,
          cls != null
              ? ClassNameUtil.toHumanReadable(cls.getName()) + "@" + Long.toHexString(obj.getId())
              : "unknown");

      // Add size fields from the rooted object
      map.put(GcRootFields.SHALLOW_SIZE, obj.getShallowSize());

      long retained = obj.getRetainedSizeIfAvailable(); // never triggers computation
      if (retained >= 0) {
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
      case FuncExpr fe -> evaluateFuncExpr(map, fe);
    };
  }

  private static boolean evaluateFuncExpr(Map<String, Object> map, FuncExpr fe) {
    List<Object> args = fe.args();
    return switch (fe.name()) {
      case "contains" -> {
        if (args.size() < 2) throw new IllegalArgumentException("contains() requires 2 arguments");
        Object val = extractFieldValue(map, List.of(String.valueOf(args.get(0))));
        yield val != null && String.valueOf(val).contains(String.valueOf(args.get(1)));
      }
      case "startswith" -> {
        if (args.size() < 2)
          throw new IllegalArgumentException("startsWith() requires 2 arguments");
        Object val = extractFieldValue(map, List.of(String.valueOf(args.get(0))));
        yield val != null && String.valueOf(val).startsWith(String.valueOf(args.get(1)));
      }
      case "endswith" -> {
        if (args.size() < 2) throw new IllegalArgumentException("endsWith() requires 2 arguments");
        Object val = extractFieldValue(map, List.of(String.valueOf(args.get(0))));
        yield val != null && String.valueOf(val).endsWith(String.valueOf(args.get(1)));
      }
      case "matches" -> {
        if (args.size() < 2) throw new IllegalArgumentException("matches() requires 2 arguments");
        Object val = extractFieldValue(map, List.of(String.valueOf(args.get(0))));
        yield val != null && String.valueOf(val).matches(String.valueOf(args.get(1)));
      }
      case "between" -> {
        if (args.size() < 3) throw new IllegalArgumentException("between() requires 3 arguments");
        Object val = extractFieldValue(map, List.of(String.valueOf(args.get(0))));
        if (val instanceof Number num) {
          double v = num.doubleValue();
          double min = ((Number) args.get(1)).doubleValue();
          double max = ((Number) args.get(2)).doubleValue();
          yield v >= min && v <= max;
        }
        yield false;
      }
      case "exists" -> {
        if (args.isEmpty()) throw new IllegalArgumentException("exists() requires 1 argument");
        Object val = extractFieldValue(map, List.of(String.valueOf(args.get(0))));
        yield val != null;
      }
      case "empty" -> {
        if (args.isEmpty()) throw new IllegalArgumentException("empty() requires 1 argument");
        Object val = extractFieldValue(map, List.of(String.valueOf(args.get(0))));
        yield val == null || String.valueOf(val).isEmpty();
      }
      default -> throw new IllegalArgumentException("Unknown filter function: " + fe.name());
    };
  }

  private static Object extractFieldValue(Map<String, Object> map, List<String> path) {
    if (path.isEmpty()) return null;

    Object current = getField(map, path.get(0));
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
      HeapSession session,
      List<Map<String, Object>> results,
      PipelineOp op,
      Query query,
      SessionResolver resolver) {
    if (op instanceof JoinOp j) {
      return applyJoin(session, results, j, query, resolver);
    }
    return applyPipelineOp(session, results, op);
  }

  private static List<Map<String, Object>> applyPipelineOp(
      HeapSession session, List<Map<String, Object>> results, PipelineOp op) {
    return switch (op) {
      case SelectOp s -> applySelect(results, s);
      case TopOp t -> applyTop(results, t);
      case GroupByOp g -> applyGroupBy(results, g);
      case CountOp _ -> applyCount(results);
      case SumOp s -> applySum(results, s);
      case StatsOp s -> applyStats(results, s);
      case SortByOp s -> applySortBy(results, s);
      case HeadOp h -> results.stream().limit(h.n()).collect(Collectors.toList());
      case TailOp t -> {
        int size = results.size();
        int skip = Math.max(0, size - t.n());
        yield results.stream().skip(skip).collect(Collectors.toList());
      }
      case FilterOp f ->
          results.stream()
              .filter(map -> matchesPredicate(map, f.predicate()))
              .collect(Collectors.toList());
      case DistinctOp d -> applyDistinct(results, d);
      case LenOp l ->
          applyStringTransform(
              results,
              l.field(),
              v -> {
                if (v instanceof String s) return (long) s.length();
                if (v instanceof java.util.Collection<?> c) return (long) c.size();
                return v;
              });
      case UppercaseOp u ->
          applyStringTransform(
              results, u.field(), v -> v instanceof String s ? s.toUpperCase() : v);
      case LowercaseOp l ->
          applyStringTransform(
              results, l.field(), v -> v instanceof String s ? s.toLowerCase() : v);
      case TrimOp t ->
          applyStringTransform(results, t.field(), v -> v instanceof String s ? s.trim() : v);
      case ReplaceOp r ->
          applyStringTransform(
              results,
              r.field(),
              v -> v instanceof String s ? s.replace(r.target(), r.replacement()) : v);
      case AbsOp a ->
          applyStringTransform(
              results,
              a.field(),
              v -> {
                if (v instanceof Long l) return Math.abs(l);
                if (v instanceof Double d2) return Math.abs(d2);
                if (v instanceof Number n) return Math.abs(n.doubleValue());
                return v;
              });
      case RoundOp r ->
          applyStringTransform(
              results,
              r.field(),
              v -> {
                if (v instanceof Double d2) return Math.round(d2);
                if (v instanceof Number n) return Math.round(n.doubleValue());
                return v;
              });
      case FloorOp f ->
          applyStringTransform(
              results,
              f.field(),
              v -> {
                if (v instanceof Double d2) return (long) Math.floor(d2);
                if (v instanceof Number n) return (long) Math.floor(n.doubleValue());
                return v;
              });
      case CeilOp c ->
          applyStringTransform(
              results,
              c.field(),
              v -> {
                if (v instanceof Double d2) return (long) Math.ceil(d2);
                if (v instanceof Number n) return (long) Math.ceil(n.doubleValue());
                return v;
              });
      case PathToRootOp _ -> {
        if (session == null) {
          throw new IllegalStateException(
              "pathToRoot requires heap session context (not available after streaming aggregation)");
        }
        yield applyPathToRoot(session.getHeapDump(), results);
      }
      case RetentionPathsOp _ -> {
        if (session == null) {
          throw new IllegalStateException(
              "retentionPaths requires heap session context (not available after streaming aggregation)");
        }
        yield applyRetentionPaths(session.getHeapDump(), results);
      }
      case RetainedBreakdownOp b -> {
        if (session == null) {
          throw new IllegalStateException(
              "retainedBreakdown requires heap session context (not available after streaming aggregation)");
        }
        yield applyRetainedBreakdown(session.getHeapDump(), results, b.maxDepth());
      }
      case CheckLeaksOp c -> {
        if (session == null) {
          throw new IllegalStateException(
              "checkLeaks requires heap session context (not available after streaming aggregation)");
        }
        yield applyCheckLeaks(session, results, c);
      }
      case DominatorsOp d -> {
        if (session == null) {
          throw new IllegalStateException(
              "dominators requires heap session context (not available after streaming aggregation)");
        }
        yield applyDominators(session, session.getHeapDump(), results, d);
      }
      case WasteOp _ -> {
        if (session == null) {
          throw new IllegalStateException(
              "waste requires heap session context (not available after streaming aggregation)");
        }
        yield applyWaste(session.getHeapDump(), results);
      }
      case ObjectsOp _ -> {
        if (session == null) {
          throw new IllegalStateException(
              "objects requires heap session context (not available after streaming aggregation)");
        }
        yield applyObjectsDrillDown(session, results);
      }
      case ThreadOwnerOp _ -> {
        if (session == null) {
          throw new IllegalStateException(
              "threadOwner requires heap session context (not available after streaming aggregation)");
        }
        yield applyThreadOwner(session, results);
      }
      case DominatedSizeOp _ -> {
        if (session == null) {
          throw new IllegalStateException(
              "dominatedSize requires heap session context (not available after streaming aggregation)");
        }
        yield applyDominatedSize(session, results);
      }
      case EstimateAgeOp _ -> {
        if (session == null) {
          throw new IllegalStateException(
              "estimateAge requires heap session context (not available after streaming aggregation)");
        }
        yield applyEstimateAge(session, results);
      }
      case WhatIfOp _ -> {
        if (session == null) {
          throw new IllegalStateException(
              "whatif() requires heap session context (not available after streaming aggregation)");
        }
        yield applyWhatIf(session, results);
      }
      case CacheStatsOp _ -> {
        if (session == null) {
          throw new IllegalStateException(
              "cacheStats() requires heap session context (not available after streaming aggregation)");
        }
        yield applyCacheStats(session, results);
      }
      case JoinOp _ ->
          throw new IllegalStateException(
              "join() is not supported in this context (no multi-session access available)");
    };
  }

  private static List<Map<String, Object>> applyJoin(
      HeapSession session,
      List<Map<String, Object>> results,
      JoinOp joinOp,
      Query query,
      SessionResolver resolver) {
    if (resolver == null) {
      throw new IllegalStateException(
          "join() requires a session resolver (no multi-session context)");
    }

    // Resolve baseline session
    Optional<SessionManager.SessionRef<? extends Session>> baseRef =
        resolver.resolve(joinOp.sessionRef());
    if (baseRef.isEmpty()) {
      throw new IllegalArgumentException("Cannot resolve session: " + joinOp.sessionRef());
    }
    Session baseSession = baseRef.get().session;

    // Determine join key
    String joinKey = joinOp.byField();
    if (joinKey == null) {
      joinKey = inferJoinKey(query.root());
    }

    if (baseSession instanceof HeapSession baseHeapSession && joinOp.root() == null) {
      // Heap-to-heap diff (existing behaviour)
      return applyHeapJoin(session, results, joinOp, query, baseHeapSession, joinKey);
    } else if (joinOp.root() != null) {
      // Cross-type join (JFR correlation)
      return applyCrossTypeJoin(results, joinOp, baseSession, joinKey, resolver);
    } else {
      throw new IllegalArgumentException(
          "Session "
              + joinOp.sessionRef()
              + " is not a heap session — use root= for cross-type join");
    }
  }

  private static List<Map<String, Object>> applyHeapJoin(
      HeapSession session,
      List<Map<String, Object>> results,
      JoinOp joinOp,
      Query query,
      HeapSession baseHeapSession,
      String joinKey) {
    // Build baseline query: same root/typePattern/predicates, empty pipeline
    Query baselineQuery =
        new Query(
            query.root(),
            query.typePattern(),
            query.instanceof_(),
            query.predicates(),
            List.of(),
            query.rootParam());

    // Evaluate baseline
    List<Map<String, Object>> baselineResults = evaluate(baseHeapSession, baselineQuery);

    // Index baseline results by join key (first occurrence wins for duplicate keys)
    Map<Object, Map<String, Object>> baselineIndex = new LinkedHashMap<>();
    for (Map<String, Object> row : baselineResults) {
      Object key = getField(row, joinKey);
      if (key != null) {
        baselineIndex.putIfAbsent(key, row);
      }
    }

    // Collect numeric field names from baseline for delta computation
    Set<String> numericFields = new LinkedHashSet<>();
    if (!baselineResults.isEmpty()) {
      for (Map.Entry<String, Object> e : baselineResults.get(0).entrySet()) {
        if (e.getValue() instanceof Number) {
          numericFields.add(e.getKey());
        }
      }
    }
    // Also collect from current results
    if (!results.isEmpty()) {
      for (Map.Entry<String, Object> e : results.get(0).entrySet()) {
        if (e.getValue() instanceof Number) {
          numericFields.add(e.getKey());
        }
      }
    }

    // Left-join: for each current row, lookup baseline, add baseline.* and *Delta columns
    List<Map<String, Object>> joined = new ArrayList<>(results.size());
    for (Map<String, Object> row : results) {
      Map<String, Object> out = aliasCopy(row);
      Object key = getField(row, joinKey);
      Map<String, Object> baseRow = key != null ? baselineIndex.get(key) : null;

      out.put("baseline.exists", baseRow != null);

      for (String field : numericFields) {
        Object currentVal = getField(row, field);
        Object baseVal = baseRow != null ? getField(baseRow, field) : null;
        out.put("baseline." + field, baseVal);

        if (currentVal instanceof Number cn && baseVal instanceof Number bn) {
          if (currentVal instanceof Long && baseVal instanceof Long) {
            out.put(field + "Delta", cn.longValue() - bn.longValue());
          } else {
            out.put(field + "Delta", cn.doubleValue() - bn.doubleValue());
          }
        } else if (currentVal instanceof Number cn) {
          if (currentVal instanceof Long) {
            out.put(field + "Delta", cn.longValue());
          } else {
            out.put(field + "Delta", cn.doubleValue());
          }
        } else {
          out.put(field + "Delta", null);
        }
      }

      joined.add(out);
    }

    return joined;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> applyCrossTypeJoin(
      List<Map<String, Object>> results,
      JoinOp joinOp,
      Session baseSession,
      String joinKey,
      SessionResolver resolver) {
    if (!(resolver instanceof CrossSessionContext ctx)) {
      throw new IllegalStateException(
          "Cross-type join requires a CrossSessionContext (not available in this shell context)");
    }

    Optional<QueryEvaluator> evalOpt = ctx.evaluatorFor(baseSession);
    if (evalOpt.isEmpty()) {
      throw new IllegalArgumentException(
          "No query evaluator found for session type: " + baseSession.getType());
    }
    QueryEvaluator jfrEvaluator = evalOpt.get();

    // Query all events of the specified root type from the JFR session
    String jfrQueryStr = "events/" + joinOp.root();
    List<Map<String, Object>> jfrRows;
    try {
      Object parsed = jfrEvaluator.parse(jfrQueryStr);
      Object jfrResult = jfrEvaluator.evaluate(baseSession, parsed);
      if (jfrResult instanceof List<?> list) {
        jfrRows = (List<Map<String, Object>>) list;
      } else {
        jfrRows = List.of();
      }
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to query JFR session for " + joinOp.root() + ": " + e.getMessage(), e);
    }

    // Aggregate JFR events by class
    Map<String, Map<String, Object>> allocStats = AllocationAggregator.aggregate(jfrRows);

    // Left-join: enrich each heap row with allocation statistics
    List<Map<String, Object>> joined = new ArrayList<>(results.size());
    for (Map<String, Object> row : results) {
      Map<String, Object> out = aliasCopy(row);
      Object keyObj = getField(row, joinKey);
      String className = keyObj != null ? String.valueOf(keyObj) : null;

      Map<String, Object> stats = className != null ? allocStats.get(className) : null;

      if (stats != null) {
        out.put("allocCount", stats.get("allocCount"));
        out.put("allocWeight", stats.get("allocWeight"));
        out.put("allocRate", stats.get("allocRate"));
        out.put("topAllocSite", stats.get("topAllocSite"));

        // Compute survivalRatio = instanceCount / allocCount
        Object instanceCountObj = getField(row, "instanceCount");
        Object allocCountObj = stats.get("allocCount");
        if (instanceCountObj instanceof Number ic
            && allocCountObj instanceof Number ac
            && ac.longValue() > 0) {
          out.put("survivalRatio", ic.doubleValue() / ac.doubleValue());
        } else {
          out.put("survivalRatio", null);
        }
      } else {
        out.put("allocCount", null);
        out.put("allocWeight", null);
        out.put("allocRate", null);
        out.put("topAllocSite", null);
        out.put("survivalRatio", null);
      }

      joined.add(out);
    }

    return joined;
  }

  private static String inferJoinKey(Root root) {
    return switch (root) {
      case CLASSES -> ClassFields.NAME;
      case OBJECTS -> ObjectFields.CLASS_NAME;
      case GCROOTS -> GcRootFields.TYPE;
      case CLUSTERS -> ClusterFields.ID;
      case DUPLICATES -> HdumpPath.DuplicateFields.ID;
      case AGES -> ObjectFields.ID;
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
            "Field '"
                + op.orderBy()
                + "' is not available on classes. "
                + "To aggregate retained sizes by class, use: "
                + "show objects | groupBy(className, agg=sum, value=retainedSize) | top(10)");
      }
      requireField(results, op.orderBy(), "top");

      // top() defaults to descending (largest first). Rows without a value sort last in both
      // directions: reversing a nulls-last comparator would rank them first (issue #225, where
      // waste() rows with null wastedBytes headed the "top waste" list).
      Comparator<Map<String, Object>> cmp =
          nullsLastBy(m -> getField(m, op.orderBy()), op.descending());
      stream = stream.sorted(cmp);
    }

    return stream.limit(op.n()).collect(Collectors.toList());
  }

  /**
   * Orders rows by {@code key}, ascending or descending, with rows whose key is null always last.
   */
  private static Comparator<Map<String, Object>> nullsLastBy(
      Function<Map<String, Object>, Object> key, boolean descending) {
    return (m1, m2) -> {
      Object v1 = key.apply(m1);
      Object v2 = key.apply(m2);
      if (v1 == null || v2 == null) {
        return v1 == null ? (v2 == null ? 0 : 1) : -1;
      }
      int c = compareValues(v1, v2);
      return descending ? -c : c;
    };
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

  private static List<Map<String, Object>> applyGroupBy(
      List<Map<String, Object>> results, GroupByOp op) {
    // Group by the specified fields
    // Groups in first-seen order, as the streaming path keeps them (a plain groupingBy HashMap
    // made ties after groupBy | top come out in hash order, differently on the two paths)
    Map<List<Object>, List<Map<String, Object>>> groups =
        results.stream()
            .collect(
                Collectors.groupingBy(
                    row -> {
                      List<Object> key = new ArrayList<>();
                      for (String field : op.groupFields()) {
                        key.add(getField(row, field));
                      }
                      return key;
                    },
                    LinkedHashMap::new,
                    Collectors.toList()));

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
            // No value=: shallowSize for objects, else the first non-id numeric column.
            // The column keeps the aggregation's name (sum/avg/min/max), as in streaming.
            String valueField = group.isEmpty() ? null : defaultAggregationField(group.get(0));
            if (valueField != null) {
              double[] values =
                  group.stream()
                      .map(m -> m.get(valueField))
                      .filter(v -> v instanceof Number)
                      .mapToDouble(v -> ((Number) v).doubleValue())
                      .toArray();
              if (values.length > 0) {
                applyAggregation(row, op.aggregation(), values, null);
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

  private static void applyAggregation(
      Map<String, Object> row, AggOp aggOp, double[] values, String valueField) {
    // Use field name as column name if it's a memory field, otherwise use aggregation name
    String columnName;
    if (valueField != null && isMemoryFieldName(valueField)) {
      columnName = valueField;
    } else {
      columnName =
          switch (aggOp) {
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
        double max = Double.NEGATIVE_INFINITY;
        for (double v : values) if (v > max) max = v;
        row.put(columnName, max);
      }
      default -> {}
    }
  }

  // === Retained size dependency analysis ===

  private static boolean isRetainedField(String field) {
    return ObjectFields.RETAINED.equals(field) || ObjectFields.RETAINED_SIZE.equals(field);
  }

  private static boolean boolExprReferencesRetained(BoolExpr expr) {
    return switch (expr) {
      case CompExpr ce -> ce.fieldPath().stream().anyMatch(HdumpPathEvaluator::isRetainedField);
      case LogicalExpr le ->
          boolExprReferencesRetained(le.left()) || boolExprReferencesRetained(le.right());
      case NotExpr ne -> boolExprReferencesRetained(ne.inner());
      case FuncExpr fe ->
          fe.args().stream()
              .filter(a -> a instanceof String)
              .map(a -> (String) a)
              .anyMatch(HdumpPathEvaluator::isRetainedField);
    };
  }

  private static boolean predicateReferencesRetained(Predicate pred) {
    return switch (pred) {
      case FieldPredicate fp ->
          fp.fieldPath().stream().anyMatch(HdumpPathEvaluator::isRetainedField);
      case ExprPredicate ep -> boolExprReferencesRetained(ep.expr());
    };
  }

  private static boolean valueExprReferencesRetained(ValueExpr expr) {
    return switch (expr) {
      case FieldRef fr -> isRetainedField(fr.field());
      case BinaryExpr be ->
          valueExprReferencesRetained(be.left()) || valueExprReferencesRetained(be.right());
      case NumberLiteral _ -> false;
    };
  }

  private static boolean opReferencesRetained(PipelineOp op) {
    return switch (op) {
      case FilterOp f -> predicateReferencesRetained(f.predicate());
      case SortByOp s ->
          s.fields().stream().map(SortField::field).anyMatch(HdumpPathEvaluator::isRetainedField);
      case TopOp t -> t.orderBy() != null && isRetainedField(t.orderBy());
      case SumOp s -> isRetainedField(s.field());
      case StatsOp s -> isRetainedField(s.field());
      case SelectOp s ->
          s.fields().stream().map(SelectField::field).anyMatch(HdumpPathEvaluator::isRetainedField);
      case GroupByOp g ->
          g.groupFields().stream().anyMatch(HdumpPathEvaluator::isRetainedField)
              || (g.valueExpr() != null && valueExprReferencesRetained(g.valueExpr()));
      // Ops that always produce or consume retained sizes
      case PathToRootOp _ -> true;
      case RetentionPathsOp _ -> true;
      case CheckLeaksOp _ -> true;
      case CacheStatsOp _ -> true;
      default -> false;
    };
  }

  /**
   * Returns true if any part of the query references retained or retainedSize fields. Used to
   * decide whether to compute retained sizes before streaming object evaluation.
   */
  public static boolean queryNeedsRetainedSize(Query query) {
    for (Predicate pred : query.predicates()) {
      if (predicateReferencesRetained(pred)) return true;
    }
    for (PipelineOp op : query.pipeline()) {
      if (opReferencesRetained(op)) return true;
    }
    return false;
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
   * Gets the column name for an aggregation result. If aggregating a memory field, returns the
   * field name to trigger memory formatting. Otherwise returns standard aggregation names (sum,
   * avg, etc.).
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
  private static String extractFieldNameFromExpr(ValueExpr expr) {
    if (expr instanceof FieldRef ref) {
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
    requireField(results, op.field(), "sum");
    double sum =
        results.stream()
            .map(m -> getField(m, op.field()))
            .filter(v -> v instanceof Number)
            .mapToDouble(v -> ((Number) v).doubleValue())
            .sum();
    return List.of(sumRow(op.field(), sum));
  }

  /** The {@code sum(field)} result row, for both paths: named after a memory field, else "sum". */
  private static Map<String, Object> sumRow(String field, double sum) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put(isMemoryFieldName(field) ? field : "sum", sum);
    return row;
  }

  private static List<Map<String, Object>> applyStats(
      List<Map<String, Object>> results, StatsOp op) {
    requireField(results, op.field(), "stats");
    DoubleSummaryStatistics stats =
        results.stream()
            .map(m -> getField(m, op.field()))
            .filter(v -> v instanceof Number)
            .mapToDouble(v -> ((Number) v).doubleValue())
            .summaryStatistics();
    return List.of(
        statsRow(op.field(), stats.getCount(), stats.getSum(), stats.getMin(), stats.getMax()));
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
            "Field '"
                + sf.field()
                + "' is not available on classes. "
                + "To aggregate retained sizes by class, use: "
                + "show objects | groupBy(className, agg=sum, value=retainedSize) | top(10)");
      }

      requireField(results, sf.field(), "sortBy");
      Comparator<Map<String, Object>> fieldCmp =
          nullsLastBy(m -> getField(m, sf.field()), sf.descending());
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
        .map(
            row -> {
              Map<String, Object> newRow = aliasCopy(row);
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
   * Normalizes a class name from Java format (java.util.HashMap) to internal JVM format
   * (java/util/HashMap). Heap dumps store class names with slashes, but users naturally type them
   * with dots.
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

    if (includeSubclasses) {
      checkInstanceofTarget(dump, pattern, result);
    }
    return result;
  }

  /**
   * Fails loudly when {@code instanceof/<type>} cannot match anything (issue #225). HPROF class
   * records carry only the superclass, never the implemented interfaces (see the
   * HPROF_GC_CLASS_DUMP layout in HotSpot's {@code heapDumper.cpp}), so subtype matching walks
   * superclass chains only. An interface is in the dump as a class of its own: no instance fields,
   * instance size 0, no instances, and no class names it as a superclass. When every class the
   * target matched (itself, its subclasses, or every class a glob matched) looks like that, the
   * query would silently return nothing, as it would for a name that is not in the dump at all.
   *
   * <p>A concrete class with no instance fields and no live instances (a static utility class)
   * looks the same in a heap dump; the message says so and names the query that answers 0 for it.
   */
  private static void checkInstanceofTarget(HeapDump dump, String pattern, Set<String> matched) {
    String shown = ClassNameUtil.toHumanReadable(pattern);
    boolean glob = pattern.contains("*") || pattern.contains("?");
    if (matched.isEmpty()) {
      throw new IllegalArgumentException(
          "instanceof/"
              + shown
              + ": no class "
              + (glob ? "matches" : "named")
              + " '"
              + shown
              + "' in this heap dump");
    }
    for (String name : matched) {
      HeapClass cls = dump.getClassByName(name).orElse(null);
      if (cls == null
          || cls.isArray()
          || cls.getInstanceCount() > 0
          || cls.getInstanceSize() != 0
          || !cls.getInstanceFields().isEmpty()) {
        return; // something here can have instances: an empty result is a real answer
      }
    }
    throw new IllegalArgumentException(
        "instanceof/"
            + shown
            + ": "
            + (glob ? "every class matching '" + shown + "' has" : "'" + shown + "' has")
            + " no instances, no instance fields and no subclasses in this heap dump, which is"
            + " what an interface looks like. A heap dump records each class's superclass but not"
            + " the interfaces it implements, so instanceof cannot match through an interface:"
            + " name a class the implementations extend instead (for java.util.Map, for example,"
            + " java.util.AbstractMap or java.util.HashMap). If "
            + (glob ? "these are classes" : "it is a class")
            + " with no live instances (a static utility class looks the same), the answer is 0:"
            + " query objects/"
            + shown
            + " without instanceof.");
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

  private static List<Map<String, Object>> applyObjectsDrillDown(
      HeapSession session, List<Map<String, Object>> results) {
    ClusterDetector.Result clusterResult = session.getOrComputeClusters();
    HeapDump dump = session.getHeapDump();
    List<Map<String, Object>> objectRows = new ArrayList<>();

    for (Map<String, Object> row : results) {
      Object idObj = row.get(HdumpPath.ClusterFields.ID);
      if (idObj == null) continue;
      int groupId =
          idObj instanceof Number
              ? ((Number) idObj).intValue()
              : Integer.parseInt(idObj.toString());

      // Try clusters first
      long[] memberIds = clusterResult.membership().get(groupId);

      // If not in clusters, check any cached duplicate results
      if (memberIds == null) {
        for (SubgraphFingerprinter.Result dupResult : session.getAllCachedDuplicates().values()) {
          memberIds = dupResult.memberIds().get(groupId);
          if (memberIds != null) break;
        }
      }

      if (memberIds == null) continue;

      for (long memberId : memberIds) {
        HeapObject obj = dump.getObjectById(memberId).orElse(null);
        if (obj != null) {
          objectRows.add(objectToMap(obj));
        }
      }
    }

    return objectRows;
  }

  private static List<Map<String, Object>> applyThreadOwner(
      HeapSession session, List<Map<String, Object>> results) {
    ThreadOwnershipAnalyzer.Result ownership = session.getOrComputeThreadOwnership();
    List<Map<String, Object>> enriched = new ArrayList<>(results.size());
    for (Map<String, Object> row : results) {
      Map<String, Object> out = new AliasMap();
      out.putAll(row);
      Object idObj = row.get(ObjectFields.ID);
      String owner = null;
      if (idObj instanceof Number n) {
        owner = ownership.ownerNameByObjectId().get(n.longValue());
      }
      if (owner == null) owner = "shared";
      out.put("ownerThread", owner);
      out.put("ownership", "shared".equals(owner) ? "shared" : "exclusive");
      enriched.add(out);
    }
    return enriched;
  }

  private static List<Map<String, Object>> applyDominatedSize(
      HeapSession session, List<Map<String, Object>> results) {
    ThreadOwnershipAnalyzer.Result ownership = session.getOrComputeThreadOwnership();
    List<Map<String, Object>> enriched = new ArrayList<>(results.size());
    for (Map<String, Object> row : results) {
      Object idObj = row.get(GcRootFields.OBJECT_ID);
      if (idObj == null) {
        enriched.add(row);
        continue;
      }
      long objId = idObj instanceof Number n ? n.longValue() : Long.parseLong(idObj.toString());
      ThreadOwnershipAnalyzer.Stats stats = ownership.statsByThreadObjId().get(objId);
      if (stats == null) {
        enriched.add(row);
        continue;
      }
      Map<String, Object> out = new AliasMap();
      out.putAll(row);
      out.put("threadName", stats.threadName());
      out.put("dominated", stats.dominated());
      out.put("dominatedCount", stats.dominatedCount());
      enriched.add(out);
    }
    return enriched;
  }

  private static List<Map<String, Object>> applyWaste(
      HeapDump dump, List<Map<String, Object>> results) {
    int refSize = dump.getIdSize();
    List<Map<String, Object>> enriched = new ArrayList<>(results.size());

    for (Map<String, Object> row : results) {
      Object idObj = row.get("id");
      if (idObj == null) {
        Map<String, Object> copy = aliasCopy(row);
        CollectionWasteAnalyzer.addNullWasteColumns(copy);
        enriched.add(copy);
        continue;
      }

      long id =
          idObj instanceof Number ? ((Number) idObj).longValue() : Long.parseLong(idObj.toString());
      HeapObject obj = dump.getObjectById(id).orElse(null);
      if (obj == null) {
        Map<String, Object> copy = aliasCopy(row);
        CollectionWasteAnalyzer.addNullWasteColumns(copy);
        enriched.add(copy);
        continue;
      }

      Map<String, Object> enrichedRow = aliasCopy(row);
      if (!CollectionWasteAnalyzer.analyze(obj, enrichedRow, refSize)) {
        CollectionWasteAnalyzer.addNullWasteColumns(enrichedRow);
      }
      enriched.add(enrichedRow);
    }

    return enriched;
  }

  private static List<Map<String, Object>> applyCacheStats(
      HeapSession session, List<Map<String, Object>> results) {
    HeapDump dump = session.getHeapDump();
    List<Map<String, Object>> enriched = new ArrayList<>(results.size());

    for (Map<String, Object> row : results) {
      Object idObj = row.get("id");
      if (idObj == null) {
        Map<String, Object> copy = aliasCopy(row);
        CacheStatsAnalyzer.addNullColumns(copy);
        enriched.add(copy);
        continue;
      }

      long id =
          idObj instanceof Number ? ((Number) idObj).longValue() : Long.parseLong(idObj.toString());
      HeapObject obj = dump.getObjectById(id).orElse(null);
      if (obj == null) {
        Map<String, Object> copy = aliasCopy(row);
        CacheStatsAnalyzer.addNullColumns(copy);
        enriched.add(copy);
        continue;
      }

      Object retainedObj = row.get(ObjectFields.RETAINED_SIZE);
      if (retainedObj == null) retainedObj = row.get("retained");
      long retainedSize =
          retainedObj instanceof Number n ? n.longValue() : obj.getRetainedSizeIfAvailable();

      Map<String, Object> enrichedRow = aliasCopy(row);
      if (!CacheStatsAnalyzer.analyze(obj, enrichedRow, retainedSize)) {
        CacheStatsAnalyzer.addNullColumns(enrichedRow);
      }
      enriched.add(enrichedRow);
    }

    return enriched;
  }

  private static List<Map<String, Object>> applyPathToRoot(
      HeapDump dump, List<Map<String, Object>> results) {
    List<Map<String, Object>> paths = new ArrayList<>();

    for (Map<String, Object> row : results) {
      Object idObj = row.get("id");
      if (idObj == null) continue;

      long id =
          idObj instanceof Number ? ((Number) idObj).longValue() : Long.parseLong(idObj.toString());
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
        pathRow.put(
            "class", pathObj.getHeapClass() != null ? pathObj.getHeapClass().getName() : "unknown");
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
   * class name, and then merge identical class-level paths. The result is a list of rows sorted by
   * {@code count} descending with columns {@code count}, {@code depth}, {@code retainedSize}, and
   * {@code path} (class names joined with " → ", from GC root to the target).
   */
  private static List<Map<String, Object>> applyRetentionPaths(
      HeapDump dump, List<Map<String, Object>> results) {

    // path string → (count, total retainedSize, depth)
    Map<String, long[]> pathStats = new LinkedHashMap<>();
    // path string → structured detail map (first-seen only)
    Map<String, LinkedHashMap<String, Object>> pathDetails = new LinkedHashMap<>();
    // path string → [gcRoot class name, leaf class name]
    Map<String, String[]> pathEndpoints = new LinkedHashMap<>();

    for (Map<String, Object> row : results) {
      Object idObj = row.get("id");
      if (idObj == null) continue;

      long id =
          idObj instanceof Number ? ((Number) idObj).longValue() : Long.parseLong(idObj.toString());
      HeapObject obj = dump.getObjectById(id).orElse(null);
      if (obj == null) continue;

      List<PathStep> path = dump.findPathToGcRoot(obj);
      if (path.isEmpty()) continue;

      // Build class-level path string: "GCRoot[TYPE] → ClassName1 → … → TargetClass"
      StringBuilder sb = new StringBuilder();
      LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
      for (int i = 0; i < path.size(); i++) {
        if (i > 0) sb.append(" → ");
        PathStep step = path.get(i);
        HeapObject stepObj = step.object();
        HeapClass cls = stepObj.getHeapClass();
        String className = cls != null ? ClassNameUtil.toHumanReadable(cls.getName()) : "unknown";
        sb.append(className);
        String value = step.fieldName() != null ? step.fieldName() : "GC Root";
        detail.put((i + 1) + ". " + className, value);
      }
      String pathKey = sb.toString();

      pathDetails.putIfAbsent(pathKey, detail);
      pathEndpoints.putIfAbsent(
          pathKey,
          new String[] {
            ClassNameUtil.toHumanReadable(
                path.get(0).object().getHeapClass() != null
                    ? path.get(0).object().getHeapClass().getName()
                    : "unknown"),
            ClassNameUtil.toHumanReadable(
                path.get(path.size() - 1).object().getHeapClass() != null
                    ? path.get(path.size() - 1).object().getHeapClass().getName()
                    : "unknown")
          });

      long retained = obj.getRetainedSize();
      long[] stats = pathStats.computeIfAbsent(pathKey, k -> new long[] {0L, 0L, path.size()});
      stats[0]++; // count
      stats[1] += Math.max(retained, 0L); // total retainedSize
      // depth is the same for all objects on the same class path, already set at init
    }

    return pathStats.entrySet().stream()
        .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
        .map(
            e -> {
              Map<String, Object> result = new LinkedHashMap<>();
              String[] endpoints = pathEndpoints.get(e.getKey());
              result.put("gcRoot", endpoints != null ? endpoints[0] : "");
              result.put("leaf", endpoints != null ? endpoints[1] : "");
              result.put("count", e.getValue()[0]);
              result.put("depth", (int) e.getValue()[2]);
              result.put("retainedSize", e.getValue()[1]);
              result.put("path", e.getKey());
              result.put("pathDetail", pathDetails.get(e.getKey()));
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

    if (!dump.hasFullDominatorTree()) {
      Map<String, Object> err = new LinkedHashMap<>();
      err.put(
          "error", "retainedBreakdown requires a computed dominator tree — run dominators() first");
      return List.of(err);
    }

    // (depth, className) → [count, shallowSize, retainedSize]
    // Use insertion-ordered map so depth-0 entries stay before depth-1, etc.
    Map<String, long[]> agg = new LinkedHashMap<>();

    // Detect input type: class rows (from classes/ query) have "instanceCount" but no "retained".
    // Object rows (from objects/ query) have "retained".
    boolean classInput =
        !results.isEmpty()
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
        LOG.debug(
            "Scanning instances of {} class(es) for retained breakdown...", classNames.size());
        dump.getObjects()
            .filter(
                obj -> {
                  HeapClass cls = obj.getHeapClass();
                  return cls != null && classNames.contains(cls.getName());
                })
            .forEach(obj -> walkDominatedSubtree(dump, obj, 0, maxDepth, agg));
      }
    } else {
      for (Map<String, Object> row : results) {
        Object idObj = row.get("id");
        if (idObj == null) continue;
        long id =
            idObj instanceof Number
                ? ((Number) idObj).longValue()
                : Long.parseLong(idObj.toString());
        HeapObject root = dump.getObjectById(id).orElse(null);
        if (root == null) continue;
        walkDominatedSubtree(dump, root, 0, maxDepth, agg);
      }
    }

    if (agg.isEmpty()) {
      return List.of();
    }

    // Build result rows sorted by depth asc, retainedSize desc
    List<Map<String, Object>> rows =
        agg.entrySet().stream()
            .map(
                e -> {
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
            .sorted(
                Comparator.comparingInt((Map<String, Object> r) -> (int) r.get("depth"))
                    .thenComparingLong(r -> -((Long) r.get("retainedSize"))))
            .collect(Collectors.toList());

    // Print ASCII tree to stderr for quick visual inspection
    printRetainedBreakdownTree(rows, maxDepth);

    return rows;
  }

  private static void walkDominatedSubtree(
      HeapDump dump, HeapObject obj, int depth, int maxDepth, Map<String, long[]> agg) {

    List<HeapObject> children = dump.getDominatedObjects(obj);
    if (children.isEmpty() || depth >= maxDepth) return;

    for (HeapObject child : children) {
      HeapClass cls = child.getHeapClass();
      String className = cls != null ? ClassNameUtil.toHumanReadable(cls.getName()) : "unknown";
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
    if (!LOG.isDebugEnabled()) return;
    StringBuilder sb = new StringBuilder("\n=== Retained Breakdown ===\n");
    int prevDepth = -1;
    for (Map<String, Object> row : rows) {
      int depth = (int) row.get("depth");
      if (depth > prevDepth + 1) continue;
      prevDepth = depth;
      String indent = "    ".repeat(depth);
      String connector = depth == 0 ? "├── " : "└── ";
      sb.append(
          String.format(
              "%s%s%s  ×%,d  shallow=%s  retained=%s%n",
              indent,
              connector,
              row.get("className"),
              row.get("count"),
              formatBytes((long) row.get("shallowSize")),
              formatBytes((long) row.get("retainedSize"))));
    }
    LOG.debug("{}", sb);
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
        error.put("help", "Use checkLeaks(detector=\"help\") to see available detectors");
        return List.of(error);
      }

      // Ensure retained sizes are computed (leak detectors need them)
      // Use approximate retained sizes which work in streaming mode (no OOME)
      session.getHeapDump().ensureRetainedSizesComputed();

      // Run the detector
      return detector.detect(session.getHeapDump(), op.threshold(), op.minSize());

    } else if (op.filter() != null) {
      // Apply custom filter - this would reference a saved query variable
      // For now, return placeholder
      Map<String, Object> placeholder = new LinkedHashMap<>();
      placeholder.put(
          "message", "Custom filter '" + op.filter() + "' requires variable resolution");
      placeholder.put("filter", op.filter());
      return List.of(placeholder);
    }

    return results;
  }

  /** Shows help for leak detectors - lists all available detectors with descriptions. */
  private static List<Map<String, Object>> showLeakDetectorHelp() {
    List<Map<String, Object>> help = new ArrayList<>();

    // Add introductory message
    Map<String, Object> intro = new LinkedHashMap<>();
    intro.put("message", "Available leak detectors");
    intro.put("usage", "checkLeaks(detector=\"<name>\", threshold=<number>, minSize=<number>)");
    intro.put("note", "Detectors automatically compute retained sizes if needed");
    help.add(intro);

    // Add each detector
    for (io.jafar.hdump.shell.leaks.LeakDetector detector :
        io.jafar.hdump.shell.leaks.LeakDetectorRegistry.getAllDetectors()) {
      Map<String, Object> detectorInfo = new LinkedHashMap<>();
      detectorInfo.put("detector", detector.getName());
      detectorInfo.put("description", detector.getDescription());

      // Add detector-specific parameter hints
      String paramHint =
          switch (detector.getName()) {
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
    examples.put(
        "examples",
        List.of(
            "checkLeaks(detector=\"duplicate-strings\")",
            "checkLeaks(detector=\"duplicate-strings\", threshold=50)",
            "checkLeaks(detector=\"growing-collections\", minSize=10000)",
            "checkLeaks(detector=\"threadlocal-leak\")"));
    help.add(examples);

    return help;
  }

  private static List<Map<String, Object>> applyDominators(
      HeapSession session, HeapDump dump, List<Map<String, Object>> results, DominatorsOp op) {

    // Check if full dominator tree is computed
    if (!dump.hasFullDominatorTree()) {
      // Auto-compute dominator tree
      if (!session.promptAndComputeDominatorTree()) {
        return List.of();
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
      return applyDominatorsTopRetainers(dump, results, minRetained);
    }
    return switch (mode) {
      case "objects" -> applyDominatorsObjects(dump, results, minRetained);
      case "tree" -> applyDominatorsTree(dump, results, minRetained);
      default -> throw new IllegalArgumentException("Unknown dominators mode: " + mode);
    };
  }

  /** Default mode: input objects ranked by retained size, no expansion. */
  private static List<Map<String, Object>> applyDominatorsTopRetainers(
      HeapDump dump, List<Map<String, Object>> results, long minRetained) {
    List<Map<String, Object>> filtered =
        results.stream()
            .filter(row -> retainedOf(row) >= minRetained)
            .sorted((a, b) -> Long.compare(retainedOf(b), retainedOf(a)))
            .limit(10)
            .collect(Collectors.toList());
    if (filtered.isEmpty() && minRetained > 0 && !results.isEmpty()) {
      // minRetained filtered everything — fall back to top 10 by retained size
      filtered =
          results.stream()
              .sorted((a, b) -> Long.compare(retainedOf(b), retainedOf(a)))
              .limit(10)
              .collect(Collectors.toList());
    }
    Iterator<Map<String, Object>> it = filtered.iterator();
    while (it.hasNext()) {
      Map<String, Object> row = it.next();
      Object idObj = row.get("id");
      if (idObj instanceof Number num) {
        Map<String, Object> pathDetail = buildGcRootPathDetail(dump, num.longValue());
        if (pathDetail != null) {
          row.put("gcRootPath", pathDetail);
        } else {
          it.remove();
        }
      }
    }
    return filtered;
  }

  private static Map<String, Object> buildGcRootPathDetail(HeapDump dump, long objectId) {
    HeapObject obj = dump.getObjectById(objectId).orElse(null);
    if (obj == null) return null;
    List<PathStep> path = dump.findPathToGcRoot(obj);
    if (path.size() < 2) return null;
    LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
    for (int i = 0; i < path.size(); i++) {
      PathStep step = path.get(i);
      HeapObject stepObj = step.object();
      HeapClass cls = stepObj.getHeapClass();
      String className = cls != null ? ClassNameUtil.toHumanReadable(cls.getName()) : "unknown";
      String key = (i + 1) + ". " + className;
      String value = step.fieldName() != null ? step.fieldName() : "GC Root";
      detail.put(key, value);
    }
    return detail;
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

      long id =
          idObj instanceof Number ? ((Number) idObj).longValue() : Long.parseLong(idObj.toString());
      HeapObject dominator = dump.getObjectById(id).orElse(null);
      if (dominator == null) continue;

      List<HeapObject> dominatedObjects = dump.getDominatedObjects(dominator);

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

    dump.getObjects()
        .forEach(
            obj -> {
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
        .map(
            agg -> {
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
    List<Map<String, Object>> filtered =
        results.stream()
            .filter(row -> retainedOf(row) >= minRetained)
            .sorted((a, b) -> Long.compare(retainedOf(b), retainedOf(a)))
            .collect(Collectors.toList());

    for (Map<String, Object> row : filtered) {
      Object idObj = row.get("id");
      if (idObj == null) continue;

      long id =
          idObj instanceof Number ? ((Number) idObj).longValue() : Long.parseLong(idObj.toString());
      HeapObject dominator = dump.getObjectById(id).orElse(null);
      if (dominator == null) continue;

      DominatorNode root = buildRecursiveDominatorTree(dump, dominator, 0, 3);

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

      LOG.debug("=== Dominator Tree ===\n{}", tree);

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("dominator", dominator.getId());
      result.put("dominatorClass", dominatorClassName);
      result.put("dominatedCount", totalDominated);
      result.put("retainedSize", dominator.getRetainedSize());
      treeResults.add(result);
    }

    return treeResults;
  }

  private static DominatorNode buildRecursiveDominatorTree(
      HeapDump dump, HeapObject root, int depth, int maxDepth) {
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
          DominatorNode more =
              new DominatorNode("... (" + (sortedClasses.size() - 10) + " more classes)");
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
